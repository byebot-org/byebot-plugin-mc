package dev.ronaldzav.byebot.settings;

import dev.ronaldzav.byebot.ByeBot;
import dev.ronaldzav.byebot.api.ByeBotApi;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class SettingsLoader {

    private static final String FILE_NAME = "settings.yml";

    private SettingsLoader() {}

    /**
     * Loads server settings according to the configured mode.
     * <ul>
     *   <li><b>byebot</b> — fetches from API, saves result to settings.yml</li>
     *   <li><b>local</b>  — reads settings.yml directly</li>
     * </ul>
     */
    public static ServerSettings load(ByeBot plugin, ByeBotApi api) {
        String mode = plugin.getConfig().getSettingsMode();

        if ("byebot".equalsIgnoreCase(mode)) {
            return loadFromApi(plugin, api);
        }
        return loadFromLocal(plugin);
    }

    // -------------------------------------------------------------------------

    private static ServerSettings loadFromApi(ByeBot plugin, ByeBotApi api) {
        if (api == null) {
            plugin.getLogger().error(
                    "[Settings] settings-mode is 'byebot' but no api-token is set — falling back to local.");
            return loadFromLocal(plugin);
        }

        return api.fetchSettings()
                .map(json -> {
                    ServerSettings s = ServerSettings.fromJson(json);
                    saveToDisk(plugin, s);
                    plugin.getLogger().info("[Settings] Settings synced from ByeBot API.");
                    return s;
                })
                .orElseGet(() -> {
                    plugin.getLogger().warn(
                            "[Settings] API unavailable — falling back to local settings.yml.");
                    return loadFromLocal(plugin);
                });
    }

    private static ServerSettings loadFromLocal(ByeBot plugin) {
        Path file = plugin.getDataDirectory().resolve(FILE_NAME);

        if (!Files.exists(file)) {
            extractDefault(plugin, file);
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, Object> yaml = new Yaml().load(reader);
            if (yaml == null) {
                plugin.getLogger().warn("[Settings] settings.yml is empty — using defaults.");
                return ServerSettings.defaults();
            }
            plugin.getLogger().info("[Settings] Loaded settings from local settings.yml.");
            return ServerSettings.fromYaml(yaml);
        } catch (IOException e) {
            plugin.getLogger().error("[Settings] Failed to read settings.yml: {} — using defaults.", e.getMessage());
            return ServerSettings.defaults();
        }
    }

    /** Serializes settings to settings.yml (called after every API sync). */
    static void saveToDisk(ByeBot plugin, ServerSettings settings) {
        Path file = plugin.getDataDirectory().resolve(FILE_NAME);
        try {
            DumperOptions opts = new DumperOptions();
            opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            opts.setPrettyFlow(true);

            String header =
                    "# ByeBot Server Settings\n" +
                    "# settings-mode: byebot — auto-generated from the API. Manual edits will be overwritten.\n" +
                    "# To edit manually, set  settings-mode: local  in config.yml\n\n";

            Files.writeString(file, header + new Yaml(opts).dump(settings.toYaml()));
        } catch (IOException e) {
            plugin.getLogger().error("[Settings] Failed to save settings.yml: {}", e.getMessage());
        }
    }

    private static void extractDefault(ByeBot plugin, Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (InputStream in = SettingsLoader.class.getResourceAsStream("/settings.yml")) {
                if (in != null) {
                    Files.copy(in, file);
                    plugin.getLogger().info("[Settings] Generated default settings.yml.");
                }
            }
        } catch (IOException e) {
            plugin.getLogger().error("[Settings] Failed to extract default settings.yml: {}", e.getMessage());
        }
    }
}
