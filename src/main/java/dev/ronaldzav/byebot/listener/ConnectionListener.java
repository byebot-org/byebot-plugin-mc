package dev.ronaldzav.byebot.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import dev.ronaldzav.byebot.ByeBot;
import dev.ronaldzav.byebot.antibot.check.CheckResult;
import net.kyori.adventure.text.Component;

import java.net.InetAddress;

public final class ConnectionListener {

    private final ByeBot plugin;

    public ConnectionListener(ByeBot plugin) {
        this.plugin = plugin;
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onPreLogin(PreLoginEvent event) {
        if (!event.getResult().isAllowed()) return;

        InetAddress address = event.getConnection().getRemoteAddress().getAddress();
        String      username = event.getUsername();

        CheckResult result = plugin.getAntibotManager().test(address, username);

        if (result.blocked()) {
            Component reason = result.customReason() != null
                    ? result.customReason()
                    : plugin.getLang().get(result.messageKey());
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(reason));
        }
    }
}
