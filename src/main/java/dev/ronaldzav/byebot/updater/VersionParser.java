package dev.ronaldzav.byebot.updater;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version format: YY.M[.P[L]]
 *   YY = year (e.g. 26), M = month (1–12)
 *   P  = optional patch number (>= 1)
 *   L  = optional letter suffix (a–z) for minimal changes
 *
 * Order: 26.5 < 26.5.1 < 26.5.1a < 26.5.1b < 26.6 < 27.1
 */
public final class VersionParser implements Comparable<VersionParser> {

    // Matches: "26.5", "26.5.1", "26.5.1a"
    private static final Pattern PATTERN =
            Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+)([a-z]?))?$");

    private final int year;
    private final int month;
    private final int patch;   // 0 means no patch (base release)
    private final char letter; // '\0' means no letter suffix
    private final String raw;

    private VersionParser(int year, int month, int patch, char letter, String raw) {
        this.year   = year;
        this.month  = month;
        this.patch  = patch;
        this.letter = letter;
        this.raw    = raw;
    }

    public static VersionParser parse(String version) {
        if (version == null) throw new IllegalArgumentException("Version cannot be null");
        Matcher m = PATTERN.matcher(version.trim());
        if (!m.matches()) throw new IllegalArgumentException("Invalid version string: " + version);

        int  year   = Integer.parseInt(m.group(1));
        int  month  = Integer.parseInt(m.group(2));
        int  patch  = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        char letter = (m.group(4) != null && !m.group(4).isEmpty()) ? m.group(4).charAt(0) : '\0';

        return new VersionParser(year, month, patch, letter, version.trim());
    }

    /** Returns null instead of throwing when the string is malformed. */
    public static VersionParser tryParse(String version) {
        try {
            return parse(version);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public boolean isNewerThan(VersionParser other) { return compareTo(other) > 0; }
    public boolean isSameAs(VersionParser other)    { return compareTo(other) == 0; }

    @Override
    public int compareTo(VersionParser other) {
        if (year  != other.year)  return Integer.compare(year, other.year);
        if (month != other.month) return Integer.compare(month, other.month);
        if (patch != other.patch) return Integer.compare(patch, other.patch);
        return Character.compare(letter, other.letter);
    }

    public String getRaw() { return raw; }

    @Override public String toString() { return raw; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VersionParser v)) return false;
        return year == v.year && month == v.month && patch == v.patch && letter == v.letter;
    }

    @Override
    public int hashCode() { return Objects.hash(year, month, patch, letter); }
}
