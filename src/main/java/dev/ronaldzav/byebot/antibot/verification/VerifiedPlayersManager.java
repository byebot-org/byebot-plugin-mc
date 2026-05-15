package dev.ronaldzav.byebot.antibot.verification;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ronaldzav.byebot.ByeBot;

import java.net.InetAddress;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Caches verified players fetched from the ByeBot API.
 * Full sync on startup; delta sync every 3 minutes using the {@code since} parameter
 * so only new or updated records are transferred on subsequent calls.
 */
public final class VerifiedPlayersManager {

    private static final long DELTA_INTERVAL_SECONDS = 180;

    private final ByeBot plugin;
    private final ConcurrentHashMap<String, VerifiedPlayer> byUsername    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VerifiedPlayer> byIp          = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>           localRoomCache = new ConcurrentHashMap<>();

    private volatile String lastSyncTime = null;

    public VerifiedPlayersManager(ByeBot plugin) {
        this.plugin = plugin;
        fullSync();
        scheduleDeltaSync();
    }

    /** Returns {@code true} if either the IP or the username appear in the verified cache. */
    public boolean isVerified(InetAddress address, String username) {
        Long localExpiry = localRoomCache.get(address.getHostAddress() + ":" + username.toLowerCase());
        if (localExpiry != null && System.currentTimeMillis() < localExpiry) return true;

        VerifiedPlayer ipMatch = byIp.get(address.getHostAddress());
        if (ipMatch != null && !ipMatch.isExpired()) return true;

        VerifiedPlayer nameMatch = byUsername.get(username.toLowerCase());
        return nameMatch != null && !nameMatch.isExpired();
    }

    /**
     * Marks a player as verified immediately after they exit the waiting room.
     * This bypasses the API sync delay so the player is recognised on their next
     * connection without having to wait up to 3 minutes for the delta sync.
     */
    public void markVerifiedLocally(InetAddress address, String username) {
        long expiry = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24);
        localRoomCache.put(address.getHostAddress() + ":" + username.toLowerCase(), expiry);
        plugin.getLogger().info("[Room] {} ({}) — marked as verified locally.", username, address.getHostAddress());
    }

    // -------------------------------------------------------------------------

    private void fullSync() {
        if (plugin.getApi() == null) return;
        plugin.getApi().fetchVerifiedPlayers(null).ifPresent(json -> {
            byUsername.clear();
            byIp.clear();
            lastSyncTime = Instant.now().toString();
            apply(json, false);
            plugin.getLogger().info("[VerifiedPlayers] Full sync: {} players cached.", byUsername.size());
        });
    }

    private void deltaSync() {
        if (plugin.getApi() == null || lastSyncTime == null) return;
        plugin.getApi().fetchVerifiedPlayers(lastSyncTime).ifPresent(json -> {
            lastSyncTime = Instant.now().toString();
            apply(json, true);
        });
    }

    private void apply(JsonObject json, boolean logNewPlayers) {
        if (!json.has("players")) return;
        JsonArray players = json.getAsJsonArray("players");
        for (JsonElement el : players) {
            VerifiedPlayer vp = VerifiedPlayer.fromJson(el.getAsJsonObject());
            if (vp.isExpired()) {
                byUsername.remove(vp.username().toLowerCase());
                byIp.remove(vp.normalizedIp());
            } else {
                boolean isNew = !byUsername.containsKey(vp.username().toLowerCase());
                byUsername.put(vp.username().toLowerCase(), vp);
                byIp.put(vp.normalizedIp(), vp);
                if (logNewPlayers && isNew) {
                    plugin.getLogger().info("[Verified] {} is now verified (sync).", vp.username());
                }
            }
        }
    }

    /** Called instantly by the webhook when a player verifies on the website. */
    public void applyWebhookEvent(VerifiedPlayer vp) {
        if (vp.isExpired()) {
            byUsername.remove(vp.username().toLowerCase());
            byIp.remove(vp.normalizedIp());
        } else {
            byUsername.put(vp.username().toLowerCase(), vp);
            byIp.put(vp.normalizedIp(), vp);
            plugin.getLogger().info("[Verified] {} is now verified (webhook).", vp.username());
        }
    }

    /** Called by the webhook when a player's verification is explicitly revoked. */
    public void revokeByUsername(String username) {
        VerifiedPlayer vp = byUsername.remove(username.toLowerCase());
        if (vp != null) byIp.remove(vp.normalizedIp());
    }

    private void scheduleDeltaSync() {
        plugin.getServer().getScheduler()
                .buildTask(plugin, this::deltaSync)
                .delay(DELTA_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .repeat(DELTA_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .schedule();
    }
}
