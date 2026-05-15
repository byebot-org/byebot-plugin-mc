package dev.ronaldzav.byebot.updater;

public enum UpdateResult {
    /** Running the latest version. */
    UP_TO_DATE,

    /** A newer version is available on Modrinth. */
    OUTDATED,

    /** Running in offline/simulation mode — no network request was made. */
    OFFLINE,

    /** Check could not be completed (network error, unexpected response, parse failure). */
    FAILED
}
