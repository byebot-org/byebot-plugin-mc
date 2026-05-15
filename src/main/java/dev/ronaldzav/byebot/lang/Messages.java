package dev.ronaldzav.byebot.lang;

/** Keys for every translatable message. Use these constants instead of raw strings. */
public final class Messages {

    public static final String PREFIX = "prefix";

    // General
    public static final String NO_PERMISSION          = "general.no-permission";
    public static final String RELOAD_NOT_IMPLEMENTED = "general.reload-not-implemented";
    public static final String CHECKING_UPDATES       = "general.checking-updates";

    // Command
    public static final String CMD_VERSION            = "command.version";
    public static final String CMD_HELP_HEADER        = "command.help-header";
    public static final String CMD_HELP_VERSION       = "command.help-version";
    public static final String CMD_HELP_RELOAD        = "command.help-reload";
    public static final String CMD_HELP_CHECK         = "command.help-check";

    // Antibot — denial reasons shown to the blocked player
    public static final String BLOCK_RATE_LIMIT       = "antibot.block-rate-limit";
    public static final String BLOCK_DATACENTER       = "antibot.block-datacenter";
    public static final String BLOCK_BLACKLISTED      = "antibot.block-blacklisted";
    public static final String BLOCK_ATTACK_MODE      = "antibot.block-attack-mode";

    // Attack mode — verification kick screen (rich component, shown when serverId is configured)
    public static final String ATK_VERIFICATION_TITLE        = "attack-mode.verification-required";
    public static final String ATK_VERIFICATION_INSTRUCTIONS = "attack-mode.scan-instructions";
    public static final String ATK_VERIFICATION_URL          = "attack-mode.verify-link";
    public static final String ATK_VERIFICATION_RECONNECT    = "attack-mode.reconnect-note";

    private Messages() {}
}
