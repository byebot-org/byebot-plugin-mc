package dev.ronaldzav.byebot.updater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.ronaldzav.byebot.ByeBot;
import dev.ronaldzav.byebot.config.ByeBotConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class UpdateChecker {

    // -------------------------------------------------------------------------
    // Developer toggle — set to true to skip Modrinth and simulate the latest
    // version locally. End users never see this option.
    // -------------------------------------------------------------------------
    static final boolean OFFLINE_MODE = false;

    private static final String MODRINTH_SLUG      = "byebot";
    private static final String OFFLINE_LATEST     = "26.5";
    private static final String MODRINTH_API        = "https://api.modrinth.com/v2/project/%s/version";
    private static final String MODRINTH_PAGE       = "https://modrinth.com/plugin/" + MODRINTH_SLUG;
    private static final String USER_AGENT          =
            "ByeBot/" + ByeBotConfig.CURRENT_VERSION + " (github.com/byebot-org/byebot-plugin-mc)";

    private final ByeBot plugin;

    public UpdateChecker(ByeBot plugin) {
        this.plugin = plugin;
    }

    public UpdateResult check() {
        if (!plugin.getConfig().isCheckUpdatesEnabled()) {
            if (plugin.getConfig().isDebug()) {
                plugin.getLogger().debug("[Updater] Update check is disabled in config.");
            }
            return UpdateResult.FAILED;
        }

        if (OFFLINE_MODE) {
            return handleOfflineMode();
        }

        try {
            return checkOnline();
        } catch (Exception e) {
            plugin.getLogger().warn("[Updater] Could not reach Modrinth: {}", e.getMessage());
            if (plugin.getConfig().isDebug()) {
                plugin.getLogger().debug("[Updater] Stack trace:", e);
            }
            return UpdateResult.FAILED;
        }
    }

    // -------------------------------------------------------------------------
    // Offline mode — simulates that the plugin is on OFFLINE_LATEST
    // -------------------------------------------------------------------------

    private UpdateResult handleOfflineMode() {
        VersionParser current   = VersionParser.parse(ByeBotConfig.CURRENT_VERSION);
        VersionParser simulated = VersionParser.parse(OFFLINE_LATEST);

        plugin.getLogger().info("[Updater] Offline mode — simulated latest: {}.", simulated);

        if (current.isSameAs(simulated)) {
            plugin.getLogger().info("[Updater] UP TO DATE ({}).", current);
        } else if (simulated.isNewerThan(current)) {
            plugin.getLogger().warn("[Updater] OUTDATED — running {}, simulated latest is {}.", current, simulated);
        } else {
            plugin.getLogger().info("[Updater] Development build ({} > simulated {}).", current, simulated);
        }
        return UpdateResult.OFFLINE;
    }

    // -------------------------------------------------------------------------
    // Online mode — queries Modrinth API
    // -------------------------------------------------------------------------

    private UpdateResult checkOnline() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(MODRINTH_API, MODRINTH_SLUG)))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 404) {
            plugin.getLogger().warn("[Updater] Project not found on Modrinth — skipping update check.");
            return UpdateResult.FAILED;
        }

        if (response.statusCode() != 200) {
            plugin.getLogger().warn("[Updater] Unexpected HTTP {} from Modrinth.", response.statusCode());
            return UpdateResult.FAILED;
        }

        VersionParser latest = findLatestVersion(response.body());
        if (latest == null) {
            plugin.getLogger().warn("[Updater] Could not parse any valid version from Modrinth response.");
            return UpdateResult.FAILED;
        }

        return reportResult(latest);
    }

    private VersionParser findLatestVersion(String json) {
        try {
            JsonArray versions = JsonParser.parseString(json).getAsJsonArray();
            VersionParser best = null;

            for (JsonElement el : versions) {
                String versionNumber = el.getAsJsonObject()
                        .get("version_number")
                        .getAsString();

                VersionParser parsed = VersionParser.tryParse(versionNumber);
                if (parsed == null) continue;

                if (best == null || parsed.isNewerThan(best)) {
                    best = parsed;
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private UpdateResult reportResult(VersionParser latest) {
        VersionParser current = VersionParser.parse(ByeBotConfig.CURRENT_VERSION);

        if (latest.isNewerThan(current)) {
            plugin.getLogger().warn("[Updater] A new version is available: {} (you are on {}).", latest, current);
            plugin.getLogger().warn("[Updater] Download: {}", MODRINTH_PAGE);
            return UpdateResult.OUTDATED;
        }

        if (current.isNewerThan(latest)) {
            plugin.getLogger().info("[Updater] Development build ({} > latest stable {}).", current, latest);
            return UpdateResult.UP_TO_DATE;
        }

        plugin.getLogger().info("[Updater] ByeBot is up to date ({}).", current);
        return UpdateResult.UP_TO_DATE;
    }
}
