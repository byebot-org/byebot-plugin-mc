package dev.ronaldzav.byebot.antibot.datacenter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ronaldzav.byebot.ByeBot;
import dev.ronaldzav.byebot.api.ByeBotApi;
import dev.ronaldzav.byebot.settings.ServerSettings.FirewallSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the datacenter IP block list.
 *
 * Ranges are fetched from {@code GET /api/v1/providers/ip-ranges} and cached on disk
 * under {@code datacenters-ips/<provider>.txt} (one file per provider) for 24 h,
 * matching the API's own cache TTL.
 */
public final class DatacenterUpdater {

    private static final String CACHE_DIR        = "datacenters-ips";
    private static final String TIMESTAMP_HEADER = "# byebot-updated:";
    private static final long   TTL_HOURS        = 24L;
    private static final long   TTL_MS           = TTL_HOURS * 3_600_000L;

    private final ByeBot          plugin;
    private final ByeBotApi       api;
    private final FirewallSettings firewall;
    private final Path            cacheDir;

    private final AtomicReference<DatacenterList> listRef =
            new AtomicReference<>(DatacenterList.empty());

    public DatacenterUpdater(ByeBot plugin, ByeBotApi api, FirewallSettings firewall) {
        this.plugin   = plugin;
        this.api      = api;
        this.firewall = firewall;
        this.cacheDir = plugin.getDataDirectory().resolve(CACHE_DIR);
    }

    // -------------------------------------------------------------------------

    public void initialize() {
        if (api == null) {
            plugin.getLogger().info("[Datacenter] No API token — datacenter check disabled.");
            return;
        }

        boolean fresh = tryLoadFromDisk();
        if (!fresh) {
            plugin.getServer().getScheduler()
                    .buildTask(plugin, this::update)
                    .schedule();
        }

        plugin.getServer().getScheduler()
                .buildTask(plugin, this::update)
                .delay(TTL_HOURS, TimeUnit.HOURS)
                .repeat(TTL_HOURS, TimeUnit.HOURS)
                .schedule();
    }

    public void update() {
        if (api == null) return;

        plugin.getLogger().info("[Datacenter] Fetching IP ranges...");

        api.fetchIpRanges().ifPresentOrElse(
                json -> {
                    Map<String, List<String>> byProvider = extractCidrsByProvider(json);
                    List<String> all = byProvider.values().stream()
                            .flatMap(List::stream).toList();
                    DatacenterList list = DatacenterList.fromCidrs(all);
                    listRef.set(list);
                    saveToDisk(byProvider);
                    plugin.getLogger().info("[Datacenter] {} CIDR blocks loaded ({} providers).",
                            list.size(), byProvider.size());
                },
                () -> plugin.getLogger().warn(
                        "[Datacenter] Could not fetch ranges — using cached list ({} entries).",
                        listRef.get().size())
        );
    }

    public DatacenterList getList() { return listRef.get(); }

    // -------------------------------------------------------------------------
    // Disk cache — one file per provider under datacenters-ips/
    // -------------------------------------------------------------------------

    /**
     * Loads cache from disk if all enabled providers have fresh files.
     * Returns {@code true} if the cache was usable, {@code false} if a re-fetch is needed.
     */
    private boolean tryLoadFromDisk() {
        List<String> enabled = enabledProviders();
        if (enabled.isEmpty()) return true;

        // All provider files must exist and be within TTL
        for (String provider : enabled) {
            Path file = cacheDir.resolve(provider + ".txt");
            if (!Files.exists(file)) return false;
            try {
                long savedAt = readTimestamp(Files.readString(file));
                if (System.currentTimeMillis() - savedAt > TTL_MS) {
                    plugin.getLogger().info("[Datacenter] Cache for '{}' is stale — will refresh.", provider);
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }

        // All fresh — load them
        List<String> all = new ArrayList<>();
        for (String provider : enabled) {
            List<String> cidrs = readProviderFile(provider);
            all.addAll(cidrs);
            plugin.getLogger().info("[Datacenter] Loaded {} CIDRs from {}.txt.", cidrs.size(), provider);
        }
        DatacenterList loaded = DatacenterList.fromCidrs(all);
        listRef.set(loaded);
        plugin.getLogger().info("[Datacenter] {} total CIDR blocks loaded from cache.", loaded.size());
        return true;
    }

    private void saveToDisk(Map<String, List<String>> byProvider) {
        try {
            Files.createDirectories(cacheDir);
            long now = System.currentTimeMillis();
            for (Map.Entry<String, List<String>> entry : byProvider.entrySet()) {
                StringBuilder sb = new StringBuilder()
                        .append(TIMESTAMP_HEADER).append(now).append('\n');
                for (String cidr : entry.getValue()) sb.append(cidr).append('\n');
                Files.writeString(cacheDir.resolve(entry.getKey() + ".txt"), sb.toString());
            }
        } catch (IOException e) {
            plugin.getLogger().warn("[Datacenter] Could not write cache: {}", e.getMessage());
        }
    }

    private List<String> readProviderFile(String provider) {
        Path file = cacheDir.resolve(provider + ".txt");
        try {
            List<String> cidrs = new ArrayList<>();
            for (String line : Files.readString(file).split("\\r?\\n")) {
                String l = line.strip();
                if (!l.isEmpty() && !l.startsWith("#")) cidrs.add(l);
            }
            return cidrs;
        } catch (IOException e) {
            plugin.getLogger().warn("[Datacenter] Could not read {}.txt: {}", provider, e.getMessage());
            return List.of();
        }
    }

    private static long readTimestamp(String content) {
        for (String line : content.split("\\r?\\n", 5)) {
            if (line.startsWith(TIMESTAMP_HEADER)) {
                try { return Long.parseLong(line.substring(TIMESTAMP_HEADER.length()).strip()); }
                catch (NumberFormatException ignored) {}
            }
        }
        return 0L;
    }

    // -------------------------------------------------------------------------
    // CIDR extraction
    // -------------------------------------------------------------------------

    private Map<String, List<String>> extractCidrsByProvider(JsonObject json) {
        JsonObject providers = json.has("providers")
                ? json.getAsJsonObject("providers") : json;

        Map<String, List<String>> result = new LinkedHashMap<>();
        if (firewall.blockAws())          result.put("aws",          collectProvider(providers, "aws"));
        if (firewall.blockGoogle())       result.put("google",       collectProvider(providers, "google"));
        if (firewall.blockOvh())          result.put("ovh",          collectProvider(providers, "ovh"));
        if (firewall.blockHetzner())      result.put("hetzner",      collectProvider(providers, "hetzner"));
        if (firewall.blockDigitalOcean()) result.put("digitalocean", collectProvider(providers, "digitalocean"));
        if (firewall.blockAzure())        result.put("azure",        collectProvider(providers, "azure"));
        return result;
    }

    private static List<String> collectProvider(JsonObject providers, String key) {
        List<String> cidrs = new ArrayList<>();
        JsonElement el = providers.get(key);
        if (el != null && el.isJsonArray()) {
            for (JsonElement range : el.getAsJsonArray()) {
                if (range.isJsonPrimitive()) cidrs.add(range.getAsString());
            }
        }
        return cidrs;
    }

    private List<String> enabledProviders() {
        List<String> enabled = new ArrayList<>();
        if (firewall.blockAws())          enabled.add("aws");
        if (firewall.blockGoogle())       enabled.add("google");
        if (firewall.blockOvh())          enabled.add("ovh");
        if (firewall.blockHetzner())      enabled.add("hetzner");
        if (firewall.blockDigitalOcean()) enabled.add("digitalocean");
        if (firewall.blockAzure())        enabled.add("azure");
        return enabled;
    }
}
