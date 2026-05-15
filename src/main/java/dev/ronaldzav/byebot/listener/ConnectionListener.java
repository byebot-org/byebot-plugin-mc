package dev.ronaldzav.byebot.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.ronaldzav.byebot.ByeBot;
import dev.ronaldzav.byebot.antibot.check.CheckResult;
import dev.ronaldzav.byebot.room.RoomManager;
import dev.ronaldzav.byebot.room.RoomServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.net.InetAddress;
import java.util.Optional;

public final class ConnectionListener {

    private final ByeBot plugin;

    public ConnectionListener(ByeBot plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Pre-login: critical gates only (attack mode, blacklist, rate-limit).
    // Always runs first, regardless of room availability.
    // -------------------------------------------------------------------------

    @Subscribe(order = PostOrder.EARLY)
    public void onPreLogin(PreLoginEvent event) {
        if (!event.getResult().isAllowed()) return;

        InetAddress address  = event.getConnection().getRemoteAddress().getAddress();
        String      username = event.getUsername();

        CheckResult result = plugin.getAntibotManager().testCriticalOnly(address, username);
        if (result.blocked()) deny(event, result);
    }

    // -------------------------------------------------------------------------
    // Initial server selection: route unverified players to the waiting room.
    // Fires after login but before any backend connection — ViaVersion and other
    // plugins cannot interfere at this stage.
    // -------------------------------------------------------------------------

    // PostOrder.LAST so ByeBot wins over plugins like LibreLogin that also set the
    // initial server (they typically run at NORMAL or LATE).
    @Subscribe(order = PostOrder.LAST)
    public void onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player      player   = event.getPlayer();
        InetAddress address  = player.getRemoteAddress().getAddress();
        String      username = player.getUsername();
        boolean     debug    = plugin.getConfig().isDebug();

        if (plugin.getAntibotManager().isWhitelisted(address)) {
            if (debug) plugin.getLogger().debug("[Room] {} — whitelisted.", username);
            return;
        }
        var vpm = plugin.getVerifiedPlayersManager();
        if (vpm != null && vpm.isVerified(address, username)) {
            if (debug) plugin.getLogger().debug("[Room] {} — already verified.", username);
            return;
        }

        RoomManager roomMgr = plugin.getRoomManager();
        if (debug) {
            plugin.getLogger().debug("[Room] {} — initial pick, configured={} allDownCached={}",
                    username,
                    roomMgr != null ? roomMgr.isConfigured() : "n/a",
                    roomMgr != null ? roomMgr.isAllDownCached() : "n/a");
        }

        if (roomMgr != null && roomMgr.isConfigured() && !roomMgr.isAllDownCached()) {
            Optional<RoomServer> roomOpt = roomMgr.pickServer();
            if (roomOpt.isPresent()) {
                RoomServer room = roomOpt.get();
                roomMgr.getRegistered(room).ifPresent(rs -> {
                    java.net.InetSocketAddress addr = rs.getServerInfo().getAddress();
                    plugin.getLogger().info("[Room] Routing {} ({}) → {} ({}:{}).",
                            username, address.getHostAddress(),
                            room.name(), addr.getHostString(), addr.getPort());
                    event.setInitialServer(rs);
                });
            }
            // If pickServer() returned empty, all rooms just went down.
            // Velocity will use its configured default server; onServerConnected
            // will run inline checks as fallback.
        }
    }

    // -------------------------------------------------------------------------
    // Universal server interception: catches ANY attempt to reach a non-room
    // backend — both initial connections (getCurrentServer empty) and switches.
    // This is the real catch-all: even if PlayerChooseInitialServerEvent is
    // overridden by LibreLogin or another plugin, this fires afterwards and
    // re-routes the player to the room before Velocity opens the TCP connection.
    // -------------------------------------------------------------------------

    @Subscribe(order = PostOrder.LAST)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!event.getResult().isAllowed()) return;

        Player player = event.getPlayer();
        RegisteredServer target = event.getResult().getServer().orElse(null);
        if (target == null) return;

        // Already going to the room — nothing to do.
        if (target.getServerInfo().getName().startsWith("byebot-room-")) return;

        // Coming FROM a room server: player just finished verification, let through.
        boolean comingFromRoom = player.getCurrentServer()
                .map(c -> c.getServerInfo().getName().startsWith("byebot-room-"))
                .orElse(false);
        if (comingFromRoom) return;

        InetAddress address  = player.getRemoteAddress().getAddress();
        String      username = player.getUsername();

        if (plugin.getAntibotManager().isWhitelisted(address)) return;
        var vpm = plugin.getVerifiedPlayersManager();
        if (vpm != null && vpm.isVerified(address, username)) return;

        RoomManager roomMgr = plugin.getRoomManager();
        if (roomMgr == null || !roomMgr.isConfigured() || roomMgr.isAllDownCached()) return;

        Optional<RoomServer> roomOpt = roomMgr.pickServer();
        if (roomOpt.isEmpty()) { roomMgr.markAllDown(); return; }

        RoomServer room = roomOpt.get();
        roomMgr.getRegistered(room).ifPresent(rs -> {
            java.net.InetSocketAddress addr = rs.getServerInfo().getAddress();
            plugin.getLogger().info("[Room] Intercepting {} ({}) → {} ({}:{}) [was: {}].",
                    username, address.getHostAddress(),
                    room.name(), addr.getHostString(), addr.getPort(),
                    target.getServerInfo().getName());
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(rs));
        });
    }

    // -------------------------------------------------------------------------
    // Room exit: when a player is kicked from a room server, redirect them to
    // the lobby. If the room refused the connection (duringServerConnect),
    // mark it as down so fallback inline checks activate.
    // -------------------------------------------------------------------------

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        if (!event.getServer().getServerInfo().getName().startsWith("byebot-room-")) return;

        Player player = event.getPlayer();

        // Treat both "kicked during connect" AND "no kick reason" as a connection error.
        // A missing reason means the room closed the TCP socket without a proper disconnect
        // packet (e.g. MAC mismatch, server crash). That means the room is broken, not that
        // the player failed verification — and we must markAllDown to break any retry loop.
        boolean isConnectionError = event.kickedDuringServerConnect()
                || event.getServerKickReason().isEmpty();

        if (isConnectionError) {
            RoomManager roomMgr = plugin.getRoomManager();
            if (roomMgr != null) roomMgr.markAllDown();
            redirectToLobby(event);
            return;
        }

        // The room sent a proper disconnect packet — decide based on the reason text.
        String reason = PlainTextComponentSerializer.plainText()
                .serialize(event.getServerKickReason().get());

        if (reason.contains("byebot:verified")) {
            var vpm = plugin.getVerifiedPlayersManager();
            if (vpm != null) vpm.markVerifiedLocally(
                    player.getRemoteAddress().getAddress(), player.getUsername());
            // Disconnect from proxy entirely so every plugin sees a fresh session on reconnect.
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                    Component.text("Verification complete! Reconnect to continue.", NamedTextColor.GREEN)));
            plugin.getLogger().info("[Room] {} verified — proxy session closed for fresh reconnect.",
                    player.getUsername());
        } else {
            // Normal kick: timeout, failed check, player left voluntarily, etc.
            redirectToLobby(event);
        }
    }

    private void redirectToLobby(KickedFromServerEvent event) {
        plugin.getServer().getAllServers().stream()
                .filter(s -> !s.getServerInfo().getName().startsWith("byebot-room-"))
                .findFirst()
                .ifPresent(fb -> event.setResult(KickedFromServerEvent.RedirectPlayer.create(fb)));
    }

    // -------------------------------------------------------------------------
    // Server connected: inline checks fallback when no room is available.
    // Trusted paths (on room / came from room / verified) are skipped.
    // -------------------------------------------------------------------------

    @Subscribe(order = PostOrder.EARLY)
    public void onServerConnected(ServerConnectedEvent event) {
        Player      player   = event.getPlayer();
        InetAddress address  = player.getRemoteAddress().getAddress();
        String      username = player.getUsername();

        if (event.getServer().getServerInfo().getName().startsWith("byebot-room-")) return;
        Optional<RegisteredServer> prev = event.getPreviousServer();
        if (prev.isPresent() && prev.get().getServerInfo().getName().startsWith("byebot-room-")) return;

        if (plugin.getAntibotManager().isWhitelisted(address)) return;
        var vpm = plugin.getVerifiedPlayersManager();
        if (vpm != null && vpm.isVerified(address, username)) return;

        // Room is active → PlayerChooseInitialServerEvent handled routing.
        // Only run inline checks when the room is down or not configured.
        RoomManager roomMgr = plugin.getRoomManager();
        if (roomMgr != null && roomMgr.isConfigured() && !roomMgr.isAllDownCached()) return;

        runInlineChecks(player, address, username);
    }

    // -------------------------------------------------------------------------

    private void runInlineChecks(Player player, InetAddress address, String username) {
        CheckResult result = plugin.getAntibotManager().testChecksOnly(address, username);
        if (result.blocked()) {
            Component reason = result.customReason() != null
                    ? result.customReason()
                    : plugin.getLang().get(result.messageKey());
            player.disconnect(reason);
        }
    }

    private void deny(PreLoginEvent event, CheckResult result) {
        Component reason = result.customReason() != null
                ? result.customReason()
                : plugin.getLang().get(result.messageKey());
        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(reason));
    }
}
