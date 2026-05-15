package dev.ronaldzav.byebot.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** Utility for quick MiniMessage parsing. Player-facing messages should go through LangLoader. */
public final class ChatUtil {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ChatUtil() {}

    public static Component parse(String miniMessage) {
        return MM.deserialize(miniMessage);
    }
}
