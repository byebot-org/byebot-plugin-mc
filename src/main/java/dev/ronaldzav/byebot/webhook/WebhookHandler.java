package dev.ronaldzav.byebot.webhook;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.ronaldzav.byebot.ByeBot;
import dev.ronaldzav.byebot.antibot.verification.VerifiedPlayer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Handles incoming webhook POST requests at /webhook.
 *
 * Authentication: HMAC-SHA256 of the raw request body, keyed with the api-token.
 * The digest must be sent in the header:  X-ByeBot-Signature: sha256=<hex>
 */
final class WebhookHandler implements HttpHandler {

    private static final byte[] RESPONSE_OK  = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RESPONSE_ERR = "{\"ok\":false}".getBytes(StandardCharsets.UTF_8);

    private final ByeBot plugin;

    WebhookHandler(ByeBot plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, RESPONSE_ERR);
            return;
        }

        byte[] body = exchange.getRequestBody().readAllBytes();

        String signature = exchange.getRequestHeaders().getFirst("X-ByeBot-Signature");
        if (!verifySignature(body, signature)) {
            plugin.getLogger().warn("[Webhook] Rejected request — invalid signature from {}.",
                    exchange.getRemoteAddress().getAddress().getHostAddress());
            respond(exchange, 401, RESPONSE_ERR);
            return;
        }

        try {
            JsonObject json = JsonParser.parseString(
                    new String(body, StandardCharsets.UTF_8)).getAsJsonObject();

            String     event = json.has("event") ? json.get("event").getAsString() : "";
            JsonObject data  = json.has("data")  ? json.getAsJsonObject("data")    : new JsonObject();

            dispatch(event, data);
            respond(exchange, 200, RESPONSE_OK);

        } catch (Exception e) {
            plugin.getLogger().warn("[Webhook] Failed to process event body: {}", e.getMessage());
            respond(exchange, 400, RESPONSE_ERR);
        }
    }

    // -------------------------------------------------------------------------

    private void dispatch(String event, JsonObject data) {
        switch (event) {

            case "player_verified" -> {
                var vpm = plugin.getVerifiedPlayersManager();
                if (vpm == null) return;
                VerifiedPlayer vp = VerifiedPlayer.fromJson(data);
                vpm.applyWebhookEvent(vp);
                if (plugin.getConfig().isDebug()) {
                    plugin.getLogger().debug("[Webhook] player_verified → {}.", vp.username());
                }
            }

            case "player_unverified" -> {
                var vpm = plugin.getVerifiedPlayersManager();
                if (vpm != null && data.has("username")) {
                    vpm.revokeByUsername(data.get("username").getAsString());
                }
            }

            case "attack_mode_changed" -> {
                if (data.has("enabled")) {
                    boolean enabled = data.get("enabled").getAsBoolean();
                    plugin.getAntibotManager().applyAttackModeFromWebhook(enabled);
                }
            }

            default -> plugin.getLogger().warn("[Webhook] Unknown event type: '{}'.", event);
        }
    }

    // -------------------------------------------------------------------------

    private boolean verifySignature(byte[] body, String header) {
        if (header == null || !header.startsWith("sha256=")) return false;
        try {
            String secret = plugin.getConfig().getWebhookSecret();
            if (secret.isBlank()) return false;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));

            // Constant-time comparison to prevent timing attacks
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    header.getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            return false;
        }
    }

    private static void respond(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
