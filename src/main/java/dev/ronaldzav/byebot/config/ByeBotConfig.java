package dev.ronaldzav.byebot.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Plugin meta-config loaded from config.yml.
 * Server-specific antibot settings (firewall, rate-limit, etc.) live in ServerSettings.
 */
public final class ByeBotConfig {

    public static final String CURRENT_VERSION;

    static {
        String v = "26.5";
        try (InputStream in = ByeBotConfig.class.getResourceAsStream("/plugin.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                v = p.getProperty("version", "26.5");
            }
        } catch (IOException ignored) {}
        CURRENT_VERSION = v;
    }

    private final String  apiToken;
    private final String  settingsMode;  // "local" or "byebot"
    private final boolean checkUpdates;
    private final boolean attackModeAutoRefresh;
    private final int     attackModeRefreshInterval; // minutes
    private final boolean webhookEnabled;
    private final int     webhookPort;
    private final boolean webhookSsl;
    private final String  webhookSecret;
    private final String  language;
    private final boolean debug;

    public ByeBotConfig(
            String  apiToken,
            String  settingsMode,
            boolean checkUpdates,
            boolean attackModeAutoRefresh,
            int     attackModeRefreshInterval,
            boolean webhookEnabled,
            int     webhookPort,
            boolean webhookSsl,
            String  webhookSecret,
            String  language,
            boolean debug
    ) {
        this.apiToken                   = apiToken;
        this.settingsMode               = settingsMode;
        this.checkUpdates               = checkUpdates;
        this.attackModeAutoRefresh      = attackModeAutoRefresh;
        this.attackModeRefreshInterval  = attackModeRefreshInterval;
        this.webhookEnabled             = webhookEnabled;
        this.webhookPort                = webhookPort;
        this.webhookSsl                 = webhookSsl;
        this.webhookSecret              = webhookSecret;
        this.language                   = language;
        this.debug                      = debug;
    }

    public String  getApiToken()                  { return apiToken;                  }
    public String  getSettingsMode()              { return settingsMode;              }
    public boolean isCheckUpdatesEnabled()        { return checkUpdates;              }
    public boolean isAttackModeAutoRefresh()      { return attackModeAutoRefresh;     }
    public int     getAttackModeRefreshInterval() { return attackModeRefreshInterval; }
    public boolean isWebhookEnabled()             { return webhookEnabled;            }
    public int     getWebhookPort()               { return webhookPort;               }
    public boolean isWebhookSsl()                 { return webhookSsl;                }
    public String  getWebhookSecret()             { return webhookSecret;             }
    public String  getLanguage()                  { return language;                  }
    public boolean isDebug()                      { return debug;                     }
}
