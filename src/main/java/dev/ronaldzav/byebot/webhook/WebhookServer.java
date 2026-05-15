package dev.ronaldzav.byebot.webhook;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import dev.ronaldzav.byebot.ByeBot;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Embedded HTTP/HTTPS server that receives push notifications from the ByeBot API.
 *
 * Supported events (POST /webhook, JSON body):
 *   attack_mode_changed  — enables or disables attack mode instantly
 *   player_verified      — adds a verified player to the local cache instantly
 *   player_unverified    — removes a player from the verified cache
 *
 * Every request must carry the header:
 *   X-ByeBot-Signature: sha256=<HMAC-SHA256 of body using api-token as secret>
 *
 * When ssl: true a self-signed PKCS12 keystore is auto-generated at
 * plugins/byebot/webhook-keystore.p12 on first start.
 */
public final class WebhookServer {

    private static final String KEYSTORE_FILE = "webhook-keystore.p12";
    // Internal password for the auto-generated keystore file — not transmitted
    private static final String KEYSTORE_PASS = "byebot-internal";

    private final ByeBot     plugin;
    private       HttpServer server;

    public WebhookServer(ByeBot plugin) {
        this.plugin = plugin;
    }

    public void start(int port, boolean ssl) {
        try {
            if (ssl) {
                server = buildHttpsServer(port);
            } else {
                server = HttpServer.create(new InetSocketAddress(port), 10);
            }

            server.createContext("/webhook", new WebhookHandler(plugin));
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "byebot-webhook");
                t.setDaemon(true);
                return t;
            }));
            server.start();

            plugin.getLogger().info("[Webhook] Listening on {}:{} ({}).",
                    ssl ? "https" : "http", port, ssl ? "SSL/TLS" : "plain HTTP");

        } catch (Exception e) {
            plugin.getLogger().error("[Webhook] Failed to start server on port {}: {}", port, e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            plugin.getLogger().info("[Webhook] Server stopped.");
        }
    }

    // -------------------------------------------------------------------------

    private HttpsServer buildHttpsServer(int port) throws Exception {
        Path keystorePath = plugin.getDataDirectory().resolve(KEYSTORE_FILE);

        if (!Files.exists(keystorePath)) {
            generateKeystore(keystorePath);
        }

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystorePath)) {
            ks.load(in, KEYSTORE_PASS.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEYSTORE_PASS.toCharArray());

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(kmf.getKeyManagers(), null, null);

        HttpsServer https = HttpsServer.create(new InetSocketAddress(port), 10);
        https.setHttpsConfigurator(new HttpsConfigurator(sslCtx) {
            @Override
            public void configure(HttpsParameters params) {
                params.setSSLParameters(getSSLContext().getDefaultSSLParameters());
            }
        });
        return https;
    }

    private void generateKeystore(Path keystorePath) throws Exception {
        plugin.getLogger().info("[Webhook] Generating self-signed SSL certificate...");

        // keytool is always co-located with the running JVM
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");

        Process proc = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-keyalg",    "RSA",
                "-keysize",   "2048",
                "-validity",  "3650",
                "-dname",     "CN=ByeBot Webhook",
                "-alias",     "byebot",
                "-storetype", "PKCS12",
                "-keystore",  keystorePath.toString(),
                "-storepass", KEYSTORE_PASS,
                "-keypass",   KEYSTORE_PASS,
                "-noprompt"
        ).redirectErrorStream(true).start();

        boolean done = proc.waitFor(15, TimeUnit.SECONDS);
        int exit = done ? proc.exitValue() : -1;

        if (exit != 0) {
            throw new IOException("keytool exited with code " + exit
                    + ". Output: " + new String(proc.getInputStream().readAllBytes()));
        }

        plugin.getLogger().info("[Webhook] Certificate saved to {}.", KEYSTORE_FILE);
    }
}
