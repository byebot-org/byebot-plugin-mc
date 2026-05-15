package dev.ronaldzav.byebot.antibot;

import dev.ronaldzav.byebot.ByeBot;
import dev.ronaldzav.byebot.antibot.check.AntibotCheck;
import dev.ronaldzav.byebot.antibot.check.CheckResult;
import dev.ronaldzav.byebot.antibot.check.DatacenterCheck;
import dev.ronaldzav.byebot.antibot.check.RateLimitCheck;
import dev.ronaldzav.byebot.antibot.datacenter.DatacenterUpdater;
import dev.ronaldzav.byebot.antibot.verification.QrCodeRenderer;
import dev.ronaldzav.byebot.lang.Messages;
import dev.ronaldzav.byebot.settings.ServerSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates all antibot checks.
 * Checks run in registration order; the first BLOCK result short-circuits the rest.
 * Whitelist and blacklist are evaluated before any check.
 */
public final class AntibotManager {

    // Hardcoded CPS threshold for local attack-mode detection
    private static final int CPS_ATTACK_THRESHOLD  = 50;
    private static final int CPS_RECOVER_THRESHOLD = 10;

    private final ByeBot             plugin;
    private final List<AntibotCheck> checks = new ArrayList<>();
    private final Set<String>        whitelist;
    private final Set<String>        blacklist;

    // Global CPS tracking
    private final AtomicInteger globalCps    = new AtomicInteger(0);
    private final AtomicLong    lastCpsReset = new AtomicLong(System.currentTimeMillis());
    private volatile boolean    attackMode   = false;

    private RateLimitCheck rateLimitCheck;

    public AntibotManager(ByeBot plugin, ServerSettings settings, DatacenterUpdater datacenterUpdater) {
        this.plugin    = plugin;
        this.whitelist = ConcurrentHashMap.newKeySet();
        this.blacklist = ConcurrentHashMap.newKeySet();
        whitelist.addAll(settings.lists().whitelist());
        blacklist.addAll(settings.lists().blacklist());

        ServerSettings.AdvancedSettings adv = settings.advanced();

        if (adv.rateLimitEnabled()) {
            rateLimitCheck = new RateLimitCheck(
                    adv.rateLimitMaxJoinsPerMinute(),
                    adv.rateLimitBanDurationSeconds()
            );
            checks.add(rateLimitCheck);
        }

        // Always register the datacenter check; a no-op when the list is empty
        checks.add(new DatacenterCheck(datacenterUpdater));

        // Sync attack mode from API on startup
        if (plugin.getApi() != null) {
            plugin.getApi().fetchAttackMode().ifPresent(mode -> {
                this.attackMode = mode;
                if (mode) plugin.getLogger().warn("[AntiBot] Server is currently in ATTACK MODE (from API).");
            });
        }

        scheduleBackgroundTasks();
    }

    // -------------------------------------------------------------------------

    public CheckResult test(InetAddress address, String username) {
        String ip = address.getHostAddress();

        if (whitelist.contains(ip)) return CheckResult.PASS;
        if (blacklist.contains(ip)) return CheckResult.block(Messages.BLOCK_BLACKLISTED);

        trackGlobalCps();

        if (attackMode) {
            return buildAttackModeResult(address, username);
        }

        for (AntibotCheck check : checks) {
            CheckResult result = check.test(address, username);
            if (result.blocked()) {
                if (plugin.getConfig().isDebug()) {
                    plugin.getLogger().debug("[AntiBot] BLOCKED {} ({}) — {}",
                            username != null ? username : "?", ip, check.getName());
                }
                return result;
            }
        }
        return CheckResult.PASS;
    }

    private CheckResult buildAttackModeResult(InetAddress address, String username) {
        var vpm = plugin.getVerifiedPlayersManager();
        if (vpm != null && vpm.isVerified(address, username)) {
            return CheckResult.PASS;
        }

        String serverId = plugin.getServerSettings().server().id();
        if (serverId == null || serverId.isBlank() || vpm == null) {
            return CheckResult.block(Messages.BLOCK_ATTACK_MODE);
        }

        String url = "https://byebot.org/verify-me?server=" + serverId;
        return CheckResult.blockWithComponent(buildVerificationKick(url));
    }

    private Component buildVerificationKick(String url) {
        var lang = plugin.getLang();
        List<Component> lines = new ArrayList<>();

        lines.add(lang.get(Messages.ATK_VERIFICATION_TITLE));

        try {
            lines.addAll(QrCodeRenderer.render(url));
        } catch (Exception e) {
            plugin.getLogger().warn("[AntiBot] QR render failed: {}", e.getMessage());
        }

        lines.add(lang.get(Messages.ATK_VERIFICATION_INSTRUCTIONS));
        lines.add(lang.get(Messages.ATK_VERIFICATION_URL, "url", url));
        lines.add(lang.get(Messages.ATK_VERIFICATION_RECONNECT));

        return Component.join(JoinConfiguration.newlines(), lines);
    }

    public boolean isAttackMode() { return attackMode; }

    // -------------------------------------------------------------------------

    private void trackGlobalCps() {
        long now  = System.currentTimeMillis();
        long last = lastCpsReset.get();

        if (now - last >= 1000L && lastCpsReset.compareAndSet(last, now)) {
            int cps = globalCps.getAndSet(0);

            if (!attackMode && cps > CPS_ATTACK_THRESHOLD) {
                setAttackMode(true, cps);
            } else if (attackMode && cps <= CPS_RECOVER_THRESHOLD
                    && !plugin.getConfig().isAttackModeAutoRefresh()) {
                // Only auto-recover locally when the API isn't managing attack-mode state.
                // With auto-refresh enabled the API poll is the sole authority for disabling.
                setAttackMode(false, cps);
            }
        }
        globalCps.incrementAndGet();
    }

    private void setAttackMode(boolean enabled, int cps) {
        this.attackMode = enabled;
        if (enabled) {
            plugin.getLogger().warn("[AntiBot] Attack detected! {} conn/s — enabling attack mode.", cps);
        } else {
            plugin.getLogger().info("[AntiBot] Attack subsided ({} conn/s) — disabling attack mode.", cps);
        }
        // Sync to API in background
        if (plugin.getApi() != null) {
            plugin.getServer().getScheduler()
                    .buildTask(plugin, () -> plugin.getApi().setAttackMode(enabled))
                    .schedule();
        }
    }

    private void scheduleBackgroundTasks() {
        plugin.getServer().getScheduler()
                .buildTask(plugin, () -> { if (rateLimitCheck != null) rateLimitCheck.cleanup(); })
                .delay(5, TimeUnit.MINUTES)
                .repeat(5, TimeUnit.MINUTES)
                .schedule();

        var cfg = plugin.getConfig();
        if (plugin.getApi() != null && cfg.isAttackModeAutoRefresh()) {
            long interval = Math.max(1, cfg.getAttackModeRefreshInterval());
            plugin.getServer().getScheduler()
                    .buildTask(plugin, this::refreshAttackModeFromApi)
                    .delay(interval, TimeUnit.MINUTES)
                    .repeat(interval, TimeUnit.MINUTES)
                    .schedule();
        }
    }

    /** Called by the periodic API poll. */
    private void refreshAttackModeFromApi() {
        plugin.getApi().fetchAttackMode().ifPresent(apiMode -> {
            if (attackMode != apiMode) {
                plugin.getLogger().info("[AntiBot] Attack mode synced from API: {} → {}.",
                        attackMode, apiMode);
                attackMode = apiMode;
            }
        });
    }

    /** Called instantly by the webhook when the dashboard toggles attack mode. */
    public void applyAttackModeFromWebhook(boolean enabled) {
        if (attackMode != enabled) {
            plugin.getLogger().info("[AntiBot] Attack mode {} via webhook.",
                    enabled ? "ENABLED" : "DISABLED");
            attackMode = enabled;
        }
    }
}
