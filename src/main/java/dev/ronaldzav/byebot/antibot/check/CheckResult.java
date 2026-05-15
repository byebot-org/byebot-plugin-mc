package dev.ronaldzav.byebot.antibot.check;

import net.kyori.adventure.text.Component;

/**
 * Result returned by each {@link AntibotCheck}.
 * {@code messageKey} is resolved through LangLoader for standard blocks.
 * {@code customReason} is used directly for rich kick messages (e.g. QR codes).
 */
public record CheckResult(boolean blocked, String messageKey, Component customReason) {

    public static final CheckResult PASS = new CheckResult(false, null, null);

    public static CheckResult block(String messageKey) {
        return new CheckResult(true, messageKey, null);
    }

    public static CheckResult blockWithComponent(Component reason) {
        return new CheckResult(true, null, reason);
    }
}
