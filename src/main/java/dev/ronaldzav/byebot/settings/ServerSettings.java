package dev.ronaldzav.byebot.settings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of server-specific antibot settings.
 * Loaded from the ByeBot API (byebot mode) or from local settings.yml (local mode).
 */
public record ServerSettings(
        FirewallSettings firewall,
        RegionSettings   regions,
        AdvancedSettings advanced,
        ListSettings     lists,
        ServerInfo       server
) {

    // -------------------------------------------------------------------------
    // Inner records
    // -------------------------------------------------------------------------

    public record FirewallSettings(
            boolean blockAws,
            boolean blockGoogle,
            boolean blockOvh,
            boolean blockHetzner,
            boolean blockDigitalOcean,
            boolean blockAzure
    ) {
        static FirewallSettings defaults() {
            return new FirewallSettings(true, true, true, true, true, true);
        }
        public boolean anyEnabled() {
            return blockAws || blockGoogle || blockOvh || blockHetzner || blockDigitalOcean || blockAzure;
        }
    }

    public record RegionSettings(String mode, List<String> countries) {
        static RegionSettings defaults() { return new RegionSettings("block", List.of()); }
    }

    public record AdvancedSettings(
            boolean rateLimitEnabled,
            int     rateLimitMaxJoinsPerMinute,
            int     rateLimitBanDurationSeconds,
            boolean nameEntropyEnabled,
            int     nameEntropyMinScore,
            boolean nameBlockNumericOnly,
            boolean nameBlockRepeatedChars,
            boolean nameBlockSequential,
            int     nameMinLength,
            int     nameMaxLength,
            boolean proxyBlockVpn,
            boolean proxyBlockTor,
            boolean proxyBlockResidential,
            boolean pingFloodEnabled,
            int     pingMaxPerMinute,
            int     pingBanDurationSeconds
    ) {
        static AdvancedSettings defaults() {
            return new AdvancedSettings(true, 30, 300, false, 2,
                    false, false, false, 3, 16,
                    false, false, false, false, 60, 60);
        }
    }

    public record ListSettings(List<String> whitelist, List<String> blacklist) {
        static ListSettings defaults() { return new ListSettings(List.of(), List.of()); }
    }

    public record ServerInfo(String id, String name) {
        static ServerInfo defaults() { return new ServerInfo("", "My Server"); }
    }

    // -------------------------------------------------------------------------
    // Factories
    // -------------------------------------------------------------------------

    public static ServerSettings defaults() {
        return new ServerSettings(
                FirewallSettings.defaults(),
                RegionSettings.defaults(),
                AdvancedSettings.defaults(),
                ListSettings.defaults(),
                ServerInfo.defaults()
        );
    }

    /** Parses from the ByeBot API JSON response (`GET /api/v1/server/settings`). */
    public static ServerSettings fromJson(JsonObject json) {
        return new ServerSettings(
                parseFirewall    (json.getAsJsonObject("firewall")),
                parseRegions     (json.getAsJsonObject("regions")),
                parseAdvanced    (json.getAsJsonObject("advanced")),
                parseLists       (json.getAsJsonObject("lists")),
                parseServerInfo  (json.has("server") ? json.getAsJsonObject("server") : null)
        );
    }

    /** Parses from a YAML map produced by SnakeYAML (local settings.yml). */
    public static ServerSettings fromYaml(Map<String, Object> yaml) {
        return new ServerSettings(
                parseFirewallYaml   (section(yaml, "firewall")),
                parseRegionsYaml    (section(yaml, "regions")),
                parseAdvancedYaml   (section(yaml, "advanced")),
                parseListsYaml      (section(yaml, "lists")),
                parseServerInfoYaml (section(yaml, "server"))
        );
    }

    /** Returns a nested Map suitable for SnakeYAML serialization. */
    public Map<String, Object> toYaml() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("server",   serverToMap());
        root.put("firewall",  firewallToMap());
        root.put("regions",   regionsToMap());
        root.put("advanced",  advancedToMap());
        root.put("lists",     listsToMap());
        return root;
    }

    // -------------------------------------------------------------------------
    // JSON parsing
    // -------------------------------------------------------------------------

    private static FirewallSettings parseFirewall(JsonObject j) {
        if (j == null) return FirewallSettings.defaults();
        return new FirewallSettings(
                jBool(j, "block_aws",         true),
                jBool(j, "block_google",      true),
                jBool(j, "block_ovh",         true),
                jBool(j, "block_hetzner",     true),
                jBool(j, "block_digitalocean",true),
                jBool(j, "block_azure",       true)
        );
    }

    private static RegionSettings parseRegions(JsonObject j) {
        if (j == null) return RegionSettings.defaults();
        String mode = j.has("mode") ? j.get("mode").getAsString() : "block";
        List<String> countries = new ArrayList<>();
        if (j.has("countries")) {
            for (JsonElement el : j.getAsJsonArray("countries")) countries.add(el.getAsString());
        }
        return new RegionSettings(mode, List.copyOf(countries));
    }

    private static AdvancedSettings parseAdvanced(JsonObject j) {
        if (j == null) return AdvancedSettings.defaults();
        return new AdvancedSettings(
                jBool(j, "rate_limit_enabled",              true),
                jInt (j, "rate_limit_max_joins_per_minute", 30),
                jInt (j, "rate_limit_ban_duration_seconds", 300),
                jBool(j, "name_entropy_enabled",            false),
                jInt (j, "name_entropy_min_score",          2),
                jBool(j, "name_block_numeric_only",         false),
                jBool(j, "name_block_repeated_chars",       false),
                jBool(j, "name_block_sequential",           false),
                jInt (j, "name_min_length",                 3),
                jInt (j, "name_max_length",                 16),
                jBool(j, "proxy_block_vpn",                 false),
                jBool(j, "proxy_block_tor",                 false),
                jBool(j, "proxy_block_residential",         false),
                jBool(j, "ping_flood_enabled",              false),
                jInt (j, "ping_max_per_minute",             60),
                jInt (j, "ping_ban_duration_seconds",       60)
        );
    }

    private static ListSettings parseLists(JsonObject j) {
        if (j == null) return ListSettings.defaults();
        return new ListSettings(
                jsonStringList(j.has("whitelist") ? j.getAsJsonArray("whitelist") : null),
                jsonStringList(j.has("blacklist") ? j.getAsJsonArray("blacklist") : null)
        );
    }

    private static List<String> jsonStringList(JsonArray arr) {
        if (arr == null) return List.of();
        List<String> list = new ArrayList<>();
        for (JsonElement el : arr) list.add(el.getAsString());
        return List.copyOf(list);
    }

    private static ServerInfo parseServerInfo(JsonObject j) {
        if (j == null) return ServerInfo.defaults();
        String id   = j.has("id")   ? j.get("id").getAsString()   : "";
        String name = j.has("name") ? j.get("name").getAsString() : "My Server";
        return new ServerInfo(id, name);
    }

    private static boolean jBool(JsonObject j, String key, boolean def) {
        return j.has(key) ? j.get(key).getAsBoolean() : def;
    }

    private static int jInt(JsonObject j, String key, int def) {
        return j.has(key) ? j.get(key).getAsInt() : def;
    }

    // -------------------------------------------------------------------------
    // YAML parsing (hyphen-style keys: block-aws)
    // -------------------------------------------------------------------------

    private static FirewallSettings parseFirewallYaml(Map<String, Object> m) {
        return new FirewallSettings(
                yBool(m, "block-aws",         true),
                yBool(m, "block-google",      true),
                yBool(m, "block-ovh",         true),
                yBool(m, "block-hetzner",     true),
                yBool(m, "block-digitalocean",true),
                yBool(m, "block-azure",       true)
        );
    }

    private static RegionSettings parseRegionsYaml(Map<String, Object> m) {
        String mode = yStr(m, "mode", "block");
        Object raw  = m.get("countries");
        List<String> countries = raw instanceof List<?> ?
                ((List<?>) raw).stream().map(Object::toString).toList() : List.of();
        return new RegionSettings(mode, countries);
    }

    private static AdvancedSettings parseAdvancedYaml(Map<String, Object> m) {
        return new AdvancedSettings(
                yBool(m, "rate-limit-enabled",              true),
                yInt (m, "rate-limit-max-joins-per-minute", 30),
                yInt (m, "rate-limit-ban-duration-seconds", 300),
                yBool(m, "name-entropy-enabled",            false),
                yInt (m, "name-entropy-min-score",          2),
                yBool(m, "name-block-numeric-only",         false),
                yBool(m, "name-block-repeated-chars",       false),
                yBool(m, "name-block-sequential",           false),
                yInt (m, "name-min-length",                 3),
                yInt (m, "name-max-length",                 16),
                yBool(m, "proxy-block-vpn",                 false),
                yBool(m, "proxy-block-tor",                 false),
                yBool(m, "proxy-block-residential",         false),
                yBool(m, "ping-flood-enabled",              false),
                yInt (m, "ping-max-per-minute",             60),
                yInt (m, "ping-ban-duration-seconds",       60)
        );
    }

    @SuppressWarnings("unchecked")
    private static ListSettings parseListsYaml(Map<String, Object> m) {
        Object wl = m.get("whitelist"), bl = m.get("blacklist");
        List<String> whitelist = wl instanceof List<?> ?
                ((List<?>) wl).stream().map(Object::toString).toList() : List.of();
        List<String> blacklist = bl instanceof List<?> ?
                ((List<?>) bl).stream().map(Object::toString).toList() : List.of();
        return new ListSettings(whitelist, blacklist);
    }

    private static ServerInfo parseServerInfoYaml(Map<String, Object> m) {
        return new ServerInfo(
                yStr(m, "id",   ""),
                yStr(m, "name", "My Server")
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        return (v instanceof Map<?, ?>) ? (Map<String, Object>) v : Map.of();
    }

    private static boolean yBool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key); return v instanceof Boolean b ? b : def;
    }
    private static int yInt(Map<String, Object> m, String key, int def) {
        Object v = m.get(key); return v instanceof Number n ? n.intValue() : def;
    }
    private static String yStr(Map<String, Object> m, String key, String def) {
        Object v = m.get(key); return v instanceof String s ? s : def;
    }

    // -------------------------------------------------------------------------
    // YAML serialization
    // -------------------------------------------------------------------------

    private Map<String, Object> firewallToMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("block-aws",         firewall.blockAws());
        m.put("block-google",      firewall.blockGoogle());
        m.put("block-ovh",         firewall.blockOvh());
        m.put("block-hetzner",     firewall.blockHetzner());
        m.put("block-digitalocean",firewall.blockDigitalOcean());
        m.put("block-azure",       firewall.blockAzure());
        return m;
    }

    private Map<String, Object> regionsToMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode",      regions.mode());
        m.put("countries", new ArrayList<>(regions.countries()));
        return m;
    }

    private Map<String, Object> advancedToMap() {
        AdvancedSettings a = advanced;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rate-limit-enabled",              a.rateLimitEnabled());
        m.put("rate-limit-max-joins-per-minute", a.rateLimitMaxJoinsPerMinute());
        m.put("rate-limit-ban-duration-seconds", a.rateLimitBanDurationSeconds());
        m.put("name-entropy-enabled",            a.nameEntropyEnabled());
        m.put("name-entropy-min-score",          a.nameEntropyMinScore());
        m.put("name-block-numeric-only",         a.nameBlockNumericOnly());
        m.put("name-block-repeated-chars",       a.nameBlockRepeatedChars());
        m.put("name-block-sequential",           a.nameBlockSequential());
        m.put("name-min-length",                 a.nameMinLength());
        m.put("name-max-length",                 a.nameMaxLength());
        m.put("proxy-block-vpn",                 a.proxyBlockVpn());
        m.put("proxy-block-tor",                 a.proxyBlockTor());
        m.put("proxy-block-residential",         a.proxyBlockResidential());
        m.put("ping-flood-enabled",              a.pingFloodEnabled());
        m.put("ping-max-per-minute",             a.pingMaxPerMinute());
        m.put("ping-ban-duration-seconds",       a.pingBanDurationSeconds());
        return m;
    }

    private Map<String, Object> listsToMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("whitelist", new ArrayList<>(lists.whitelist()));
        m.put("blacklist", new ArrayList<>(lists.blacklist()));
        return m;
    }

    private Map<String, Object> serverToMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",   server.id());
        m.put("name", server.name());
        return m;
    }
}
