package dev.ronaldzav.byebot.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ConfigLoader {

    private ConfigLoader() {}

    @SuppressWarnings("unchecked")
    public static ByeBotConfig load(Path dataDirectory, Logger logger) {
        Path configPath = dataDirectory.resolve("config.yml");

        try {
            Files.createDirectories(dataDirectory);

            if (!Files.exists(configPath)) {
                try (InputStream in = ConfigLoader.class.getResourceAsStream("/config.yml")) {
                    if (in != null) {
                        Files.copy(in, configPath);
                        logger.info("Generated default config.yml");
                    }
                }
            }

            Yaml yaml = new Yaml();
            try (Reader reader = Files.newBufferedReader(configPath)) {
                Map<String, Object> root = yaml.load(reader);
                if (root == null) {
                    logger.warn("config.yml is empty — using defaults.");
                    return defaults();
                }

                Map<String, Object> updater    = section(root, "updater");
                Map<String, Object> attackMode = section(root, "attack-mode");
                Map<String, Object> webhook    = section(root, "webhook");
                Map<String, Object> general    = section(root, "general");

                return new ByeBotConfig(
                        str (root,       "api-token",        ""),
                        str (root,       "settings-mode",    "local"),
                        bool(updater,    "check-updates",    true),
                        bool(attackMode, "auto-refresh",     true),
                        intv(attackMode, "refresh-interval", 5),
                        bool(webhook,    "enabled",          false),
                        intv(webhook,    "port",             8080),
                        bool(webhook,    "ssl",              false),
                        str (webhook,    "secret",           ""),
                        str (general,    "language",         "en"),
                        bool(general,    "debug",            false)
                );
            }

        } catch (IOException e) {
            logger.error("Failed to load config.yml — using defaults: {}", e.getMessage());
            return defaults();
        }
    }

    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String key) {
        Object val = parent.get(key);
        return (val instanceof Map<?, ?>) ? (Map<String, Object>) val : Map.of();
    }

    private static boolean bool(Map<String, Object> map, String key, boolean def) {
        Object v = map.get(key); return v instanceof Boolean b ? b : def;
    }

    private static String str(Map<String, Object> map, String key, String def) {
        Object v = map.get(key); return v instanceof String s ? s : def;
    }

    private static int intv(Map<String, Object> map, String key, int def) {
        Object v = map.get(key); return v instanceof Number n ? n.intValue() : def;
    }

    private static ByeBotConfig defaults() {
        return new ByeBotConfig("", "local", true, true, 5, false, 8080, false, "", "en", false);
    }
}
