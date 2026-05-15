package dev.ronaldzav.byebot.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import dev.ronaldzav.byebot.ByeBot;
import dev.ronaldzav.byebot.config.ByeBotConfig;
import dev.ronaldzav.byebot.lang.LangLoader;
import dev.ronaldzav.byebot.lang.Messages;
import dev.ronaldzav.byebot.updater.UpdateChecker;
import net.kyori.adventure.text.Component;

import java.util.List;

public final class ByeBotCommand implements SimpleCommand {

    private final ByeBot plugin;

    public ByeBotCommand(ByeBot plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendHelp(source);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "version", "ver" -> sendVersion(source);
            case "reload"         -> handleReload(source);
            case "check"          -> handleUpdateCheck(source);
            default               -> sendHelp(source);
        }
    }

    // -------------------------------------------------------------------------

    private void sendVersion(CommandSource source) {
        LangLoader lang = plugin.getLang();
        source.sendMessage(lang.prefixed(Messages.CMD_VERSION, "version", ByeBotConfig.CURRENT_VERSION));
    }

    private void handleReload(CommandSource source) {
        if (!source.hasPermission("byebot.reload")) {
            source.sendMessage(plugin.getLang().prefixed(Messages.NO_PERMISSION));
            return;
        }
        // TODO: hot-reload config + lang
        source.sendMessage(plugin.getLang().prefixed(Messages.RELOAD_NOT_IMPLEMENTED));
    }

    private void handleUpdateCheck(CommandSource source) {
        if (!source.hasPermission("byebot.admin")) {
            source.sendMessage(plugin.getLang().prefixed(Messages.NO_PERMISSION));
            return;
        }
        source.sendMessage(plugin.getLang().prefixed(Messages.CHECKING_UPDATES));
        plugin.getServer().getScheduler()
                .buildTask(plugin, () -> new UpdateChecker(plugin).check())
                .schedule();
    }

    private void sendHelp(CommandSource source) {
        LangLoader lang = plugin.getLang();
        source.sendMessage(Component.text()
                .append(lang.get(Messages.CMD_HELP_HEADER, "version", ByeBotConfig.CURRENT_VERSION))
                .appendNewline()
                .append(lang.get(Messages.CMD_HELP_VERSION))
                .appendNewline()
                .append(lang.get(Messages.CMD_HELP_RELOAD))
                .appendNewline()
                .append(lang.get(Messages.CMD_HELP_CHECK))
                .build());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length <= 1) {
            return List.of("version", "reload", "check");
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        CommandSource s = invocation.source();
        return s.hasPermission("byebot.use") || s.hasPermission("byebot.admin");
    }
}
