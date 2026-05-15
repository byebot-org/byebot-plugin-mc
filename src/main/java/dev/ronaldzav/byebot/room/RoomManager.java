package dev.ronaldzav.byebot.room;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.ronaldzav.byebot.ByeBot;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manages the list of available byebot waiting-room servers fetched from the API.
 *
 * Room servers are pre-registered in Velocity on startup so ViaVersion can detect
 * their protocol version before any player connects.
 *
 * Server selection: preferred region first, then others.
 * When all servers are unreachable, the "all-down" state is cached for 15 minutes
 * so the plugin falls back to inline checks instead of hammering the API.
 */
public final class RoomManager {

    private static final long FALLBACK_CACHE_MS = 15 * 60 * 1000L;

    private final ByeBot plugin;
    private volatile List<RoomRegion> regions = List.of();
    private volatile long allDownSince = -1L;

    public RoomManager(ByeBot plugin) {
        this.plugin = plugin;

        if (!plugin.getServer().getPluginManager().isLoaded("viaversion") ||
            !plugin.getServer().getPluginManager().isLoaded("viabackwards")) {
            plugin.getLogger().warn(
                    "[Room] ViaVersion and/or ViaBackwards are not installed. " +
                    "The room feature requires both to handle cross-version connections.");
        }

        reload();
    }

    public void reload() {
        // Unregister previously loaded room servers before refreshing.
        regions.forEach(region -> region.servers().forEach(this::unregisterInVelocity));

        if (plugin.getApi() == null) {
            loadFromCache();
            return;
        }

        plugin.getApi().fetchRoomConfig().ifPresentOrElse(
                json -> {
                    regions = parseRegions(json);
                    // Pre-register all servers so ViaVersion detects their version upfront.
                    regions.forEach(region -> region.servers().forEach(this::registerInVelocity));
                    saveCache(json);
                    if (regions.isEmpty()) {
                        plugin.getLogger().warn(
                                "[Room] No regions configured in the dashboard — room feature inactive.");
                    } else {
                        plugin.getLogger().info("[Room] Loaded {} region(s).", regions.size());
                    }
                },
                () -> {
                    plugin.getLogger().warn("[Room] Could not fetch room config from API — trying local cache.");
                    loadFromCache();
                }
        );
    }

    /** True when the dashboard has at least one region configured. */
    public boolean isConfigured() { return !regions.isEmpty(); }

    /**
     * Returns the best available room server: preferred region first, then others.
     * Returns empty if not configured, or if the all-down cache is still active.
     */
    public Optional<RoomServer> pickServer() {
        if (!isConfigured() || isAllDownCached()) return Optional.empty();

        List<RoomRegion> ordered = new ArrayList<>(regions.size());
        for (RoomRegion r : regions) { if ( r.preferred()) ordered.add(r); }
        for (RoomRegion r : regions) { if (!r.preferred()) ordered.add(r); }

        for (RoomRegion region : ordered) {
            if (!region.servers().isEmpty()) return Optional.of(region.servers().get(0));
        }

        markAllDown();
        return Optional.empty();
    }

    /** Returns the Velocity-registered server for the given room (pre-registered on startup). */
    public Optional<RegisteredServer> getRegistered(RoomServer room) {
        return plugin.getServer().getServer("byebot-room-" + room.id());
    }

    /** Looks up the RoomServer record by Velocity server name (e.g. "byebot-room-abc123"). */
    public Optional<RoomServer> findByVelocityName(String velocityName) {
        if (!velocityName.startsWith("byebot-room-")) return Optional.empty();
        String roomId = velocityName.substring("byebot-room-".length());
        return regions.stream()
                .flatMap(r -> r.servers().stream())
                .filter(s -> s.id().equals(roomId))
                .findFirst();
    }

    /** Returns true if all servers were recently found unreachable and the 15-min window hasn't expired. */
    public boolean isAllDownCached() {
        return allDownSince > 0 && System.currentTimeMillis() - allDownSince < FALLBACK_CACHE_MS;
    }

    public void markAllDown() {
        boolean firstTime = allDownSince <= 0;
        allDownSince = System.currentTimeMillis();
        if (firstTime) {
            plugin.getLogger().warn(
                    "[Room] All room servers unreachable — falling back to inline checks for 15 min.");
        }
    }

    public void markAvailable() {
        allDownSince = -1L;
    }

    // -------------------------------------------------------------------------

    private void saveCache(JsonObject json) {
        try {
            Path cache = plugin.getDataDirectory().resolve("rooms-cache.json");
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, json.toString());
        } catch (IOException e) {
            plugin.getLogger().warn("[Room] Could not write rooms cache: {}", e.getMessage());
        }
    }

    private void loadFromCache() {
        Path cache = plugin.getDataDirectory().resolve("rooms-cache.json");
        if (!Files.isReadable(cache)) {
            plugin.getLogger().warn("[Room] No rooms cache found — room feature inactive.");
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(cache)).getAsJsonObject();
            regions = parseRegions(json);
            regions.forEach(region -> region.servers().forEach(this::registerInVelocity));
            if (regions.isEmpty()) {
                plugin.getLogger().warn("[Room] Rooms cache loaded but no regions found — room feature inactive.");
            } else {
                plugin.getLogger().info("[Room] Loaded {} region(s) from local cache (offline).", regions.size());
            }
        } catch (Exception e) {
            plugin.getLogger().error("[Room] Failed to load rooms cache: {}", e.getMessage());
        }
    }

    private void registerInVelocity(RoomServer room) {
        String name = "byebot-room-" + room.id();
        if (plugin.getServer().getServer(name).isEmpty()) {
            plugin.getServer().registerServer(
                    new ServerInfo(name, new InetSocketAddress(room.ip(), room.port())));
            plugin.getLogger().info("[Room] Registered {} → {}:{}.", name, room.ip(), room.port());
        }
    }

    private void unregisterInVelocity(RoomServer room) {
        String name = "byebot-room-" + room.id();
        plugin.getServer().getServer(name).ifPresent(rs ->
                plugin.getServer().unregisterServer(rs.getServerInfo()));
    }

    private List<RoomRegion> parseRegions(JsonObject json) {
        if (!json.has("regions")) return List.of();
        List<RoomRegion> result = new ArrayList<>();
        for (JsonElement el : json.getAsJsonArray("regions")) {
            JsonObject r = el.getAsJsonObject();
            result.add(new RoomRegion(
                    str(r, "id",   ""),
                    str(r, "name", ""),
                    str(r, "code", ""),
                    r.has("preferred") && r.get("preferred").getAsBoolean(),
                    parseServers(r.has("servers") ? r.getAsJsonArray("servers") : new JsonArray())
            ));
        }
        return List.copyOf(result);
    }

    private List<RoomServer> parseServers(JsonArray arr) {
        List<RoomServer> result = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject s = el.getAsJsonObject();
            result.add(new RoomServer(
                    str(s, "id",     ""),
                    str(s, "name",   ""),
                    str(s, "ip",     ""),
                    s.has("port") ? s.get("port").getAsInt() : 25566,
                    str(s, "secret", "")
            ));
        }
        return result;
    }

    private static String str(JsonObject j, String key, String def) {
        return j.has(key) ? j.get(key).getAsString() : def;
    }
}
