package dev.ronaldzav.byebot;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.ronaldzav.byebot.antibot.AntibotManager;
import dev.ronaldzav.byebot.antibot.datacenter.DatacenterUpdater;
import dev.ronaldzav.byebot.antibot.verification.VerifiedPlayersManager;
import dev.ronaldzav.byebot.api.ByeBotApi;
import dev.ronaldzav.byebot.command.ByeBotCommand;
import dev.ronaldzav.byebot.config.ByeBotConfig;
import dev.ronaldzav.byebot.config.ConfigLoader;
import dev.ronaldzav.byebot.lang.LangLoader;
import dev.ronaldzav.byebot.listener.ConnectionListener;
import dev.ronaldzav.byebot.settings.ServerSettings;
import dev.ronaldzav.byebot.settings.SettingsLoader;
import dev.ronaldzav.byebot.updater.UpdateChecker;
import dev.ronaldzav.byebot.room.RoomManager;
import dev.ronaldzav.byebot.webhook.WebhookServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id          = "byebot",
        name        = "ByeBot",
        version     = "26.5",
        description = "Professional open-source antibot for Velocity",
        url         = "https://github.com/byebot-org/byebot-plugin-mc",
        authors     = {"RonaldZav"},
        dependencies = {
                @Dependency(id = "viaversion",   optional = true),
                @Dependency(id = "viabackwards", optional = true),
                @Dependency(id = "librelogin",   optional = true)
        }
)
public final class ByeBot {

    private final ProxyServer server;
    private final Logger      logger;
    private final Path        dataDirectory;

    private ByeBotConfig           config;
    private LangLoader             lang;
    private ByeBotApi              api;            // null when no token is configured
    private ServerSettings         serverSettings;
    private AntibotManager         antibotManager;
    private VerifiedPlayersManager verifiedPlayersManager; // null when no api token
    private RoomManager            roomManager;            // null when api is not configured
    private WebhookServer          webhookServer;          // null when disabled

    @Inject
    public ByeBot(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server        = server;
        this.logger        = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        installQrFilter();
        logger.info("Starting ByeBot v{}...", ByeBotConfig.CURRENT_VERSION);

        // 1. Meta-config
        config = ConfigLoader.load(dataDirectory, logger);

        // 2. Language
        lang = LangLoader.load(dataDirectory, config.getLanguage(), logger);

        // 3. API client (only if token is provided)
        if (!config.getApiToken().isBlank()) {
            api = new ByeBotApi(config.getApiToken(), logger);
        } else {
            logger.info("[API] No api-token set — API features disabled.");
        }

        // 4. Server settings (from API or local settings.yml)
        serverSettings = SettingsLoader.load(this, api);

        // 5. Datacenter IP ranges
        DatacenterUpdater datacenterUpdater =
                new DatacenterUpdater(this, api, serverSettings.firewall());
        datacenterUpdater.initialize();

        // 6. Antibot engine
        antibotManager = new AntibotManager(this, serverSettings, datacenterUpdater);

        // 7. Verified players cache (requires API)
        if (api != null) {
            verifiedPlayersManager = new VerifiedPlayersManager(this);
        }

        // 8. Room manager
        if (api != null && serverSettings.room().enabled()) {
            roomManager = new RoomManager(this);
        }

        // 9. Webhook server
        if (config.isWebhookEnabled()) {
            if (api == null) {
                logger.warn("[Webhook] Webhook requires an api-token — not started.");
            } else if (config.getWebhookSecret().isBlank()) {
                logger.warn("[Webhook] webhook.secret is empty — not started. Set a secret in config.yml.");
            } else {
                webhookServer = new WebhookServer(this);
                webhookServer.start(config.getWebhookPort(), config.isWebhookSsl());
            }
        }

        // 10. Commands & listeners
        server.getCommandManager().register(
                server.getCommandManager()
                        .metaBuilder("byebot").aliases("bb").plugin(this).build(),
                new ByeBotCommand(this)
        );
        server.getEventManager().register(this, new ConnectionListener(this));

        // 10. Update checker
        server.getScheduler()
                .buildTask(this, () -> new UpdateChecker(this).check())
                .schedule();

        logger.info("ByeBot v{} is active. [mode: {}]",
                ByeBotConfig.CURRENT_VERSION, config.getSettingsMode());
    }

    private void installQrFilter() {
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            AbstractFilter filter = new AbstractFilter() {
                @Override
                public Result filter(LogEvent event) {
                    String msg = event.getMessage().getFormattedMessage();
                    return msg.indexOf('█') >= 0 ? Result.DENY : Result.NEUTRAL;
                }
            };
            filter.start();
            ctx.getConfiguration().getRootLogger().addFilter(filter);
            ctx.updateLoggers();
        } catch (Exception e) {
            logger.warn("[ByeBot] Could not install QR console filter: {}", e.getMessage());
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (webhookServer != null) webhookServer.stop();
        logger.info("ByeBot has been disabled.");
    }

    public ProxyServer             getServer()                  { return server;                  }
    public Logger                  getLogger()                  { return logger;                  }
    public Path                    getDataDirectory()           { return dataDirectory;           }
    public ByeBotConfig            getConfig()                  { return config;                  }
    public LangLoader              getLang()                    { return lang;                    }
    public ByeBotApi               getApi()                     { return api;                     }
    public ServerSettings          getServerSettings()          { return serverSettings;          }
    public AntibotManager          getAntibotManager()          { return antibotManager;          }
    public VerifiedPlayersManager  getVerifiedPlayersManager()  { return verifiedPlayersManager;  }
    public RoomManager             getRoomManager()             { return roomManager;             }
    public WebhookServer           getWebhookServer()           { return webhookServer;           }
}
