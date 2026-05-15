package dev.ronaldzav.byebot.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loads player-facing messages from plugins/byebot/lang/{language}.yml.
 *
 * Resolution order for any key:
 *   1. Selected language file on disk (editable by server admins)
 *   2. Bundled en.yml from the JAR (covers missing keys in outdated translations)
 *
 * Placeholders use {key} syntax, e.g. "{version}" → "26.5".
 * Message values must be valid MiniMessage strings.
 */
public final class LangLoader {

    /** Languages bundled inside the JAR and extracted to disk on first run. */
    private static final List<String> BUNDLED = List.of("en", "es");
    private static final MiniMessage  MM      = MiniMessage.miniMessage();

    private final Map<String, Object> primary;  // selected language (disk)
    private final Map<String, Object> fallback; // bundled en.yml (JAR)

    private LangLoader(Map<String, Object> primary, Map<String, Object> fallback) {
        this.primary  = primary;
        this.fallback = fallback;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static LangLoader load(Path dataDirectory, String language, Logger logger) {
        Path langDir = dataDirectory.resolve("lang");
        try {
            Files.createDirectories(langDir);
            extractBundled(langDir);
        } catch (IOException e) {
            logger.error("[Lang] Could not create lang/ directory: {}", e.getMessage());
        }

        Map<String, Object> fallback = loadFromJar("en");
        Map<String, Object> primary  = loadFromDisk(langDir, language, logger);

        if (primary == null) {
            logger.warn("[Lang] '{}' not found — falling back to 'en'.", language);
            primary = fallback != null ? fallback : Map.of();
        }

        return new LangLoader(primary, fallback != null ? fallback : Map.of());
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the message for {@code key} as a Component.
     * Placeholders: vararg pairs — {@code "version", "26.5", "player", "Steve"}.
     */
    public Component get(String key, Object... replacements) {
        String raw = resolve(key);
        if (raw == null) {
            return Component.text("<missing: " + key + ">", NamedTextColor.RED);
        }
        return MM.deserialize(replacePlaceholders(raw, replacements));
    }

    /**
     * Same as {@link #get} but prepends the {@code prefix} key from the lang file.
     */
    public Component prefixed(String key, Object... replacements) {
        String prefixRaw = resolve(Messages.PREFIX);
        Component prefix = prefixRaw != null ? MM.deserialize(prefixRaw) : Component.empty();
        return Component.text().append(prefix).append(get(key, replacements)).build();
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private String resolve(String key) {
        String val = navigate(primary, key);
        return val != null ? val : navigate(fallback, key);
    }

    @SuppressWarnings("unchecked")
    private static String navigate(Map<String, Object> map, String key) {
        String[] parts = key.split("\\.", -1);
        Map<String, Object> current = map;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) return null;
            current = (Map<String, Object>) next;
        }
        Object val = current.get(parts[parts.length - 1]);
        return val instanceof String s ? s : null;
    }

    private static String replacePlaceholders(String raw, Object... replacements) {
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            raw = raw.replace("{" + replacements[i] + "}", String.valueOf(replacements[i + 1]));
        }
        return raw;
    }

    /** Extracts each bundled lang file to disk only if it doesn't already exist. */
    private static void extractBundled(Path langDir) throws IOException {
        for (String lang : BUNDLED) {
            Path dest = langDir.resolve(lang + ".yml");
            if (Files.exists(dest)) continue;
            try (InputStream in = LangLoader.class.getResourceAsStream("/lang/" + lang + ".yml")) {
                if (in != null) Files.copy(in, dest);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadFromDisk(Path langDir, String language, Logger logger) {
        Path file = langDir.resolve(language + ".yml");
        if (!Files.exists(file)) return null;
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, Object> map = new Yaml().load(reader);
            return map != null ? map : Map.of();
        } catch (IOException e) {
            logger.error("[Lang] Failed to read {}.yml: {}", language, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadFromJar(String language) {
        try (InputStream in = LangLoader.class.getResourceAsStream("/lang/" + language + ".yml")) {
            if (in == null) return null;
            Map<String, Object> map = new Yaml().load(in);
            return map != null ? map : Map.of();
        } catch (IOException e) {
            return null;
        }
    }
}
