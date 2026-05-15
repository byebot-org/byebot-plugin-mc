package dev.ronaldzav.byebot.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ronaldzav.byebot.config.ByeBotConfig;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** Authenticated HTTP client for the ByeBot REST API. */
public final class ByeBotApi {

    private static final String BASE_URL = "https://byebot.org/api/v1";

    private final String     apiToken;
    private final Logger     logger;
    private final HttpClient client;

    public ByeBotApi(String apiToken, Logger logger) {
        this.apiToken = apiToken;
        this.logger   = logger;
        this.client   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // -------------------------------------------------------------------------
    // Endpoints
    // -------------------------------------------------------------------------

    public Optional<JsonObject> fetchSettings() {
        return get("/server/settings");
    }

    public Optional<JsonObject> fetchIpRanges() {
        return get("/providers/ip-ranges");
    }

    public Optional<Boolean> fetchAttackMode() {
        return get("/server/attack-mode")
                .map(j -> j.get("under_attack_mode").getAsBoolean());
    }

    /** Returns {@code true} if the request succeeded. */
    public boolean setAttackMode(boolean enabled) {
        return post("/server/attack-mode", "{\"enabled\":" + enabled + "}").isPresent();
    }

    /**
     * Fetches verified players. Pass {@code null} for a full sync, or an ISO-8601
     * timestamp to request only records changed since that point (delta sync).
     */
    public Optional<JsonObject> fetchRoomConfig() {
        return get("/rooms");
    }

    public Optional<JsonObject> fetchVerifiedPlayers(String since) {
        String path = "/server/verified-players";
        if (since != null && !since.isBlank()) {
            path += "?since=" + java.net.URLEncoder.encode(since,
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        return get(path);
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private Optional<JsonObject> get(String path) {
        try {
            HttpResponse<String> resp = client.send(
                    request(path).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return handle(resp, "GET " + path);
        } catch (Exception e) {
            logger.error("[API] GET {} failed: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<JsonObject> post(String path, String body) {
        try {
            HttpResponse<String> resp = client.send(
                    request(path)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return handle(resp, "POST " + path);
        } catch (Exception e) {
            logger.error("[API] POST {} failed: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiToken)
                .header("User-Agent",    "ByeBot/" + ByeBotConfig.CURRENT_VERSION
                        + " (byebot.org)");
    }

    private Optional<JsonObject> handle(HttpResponse<String> resp, String label) {
        if (resp.statusCode() == 401) {
            logger.error("[API] {} → 401 Unauthorized. Check your api-token in config.yml.", label);
            return Optional.empty();
        }
        if (resp.statusCode() != 200) {
            logger.warn("[API] {} → HTTP {}", label, resp.statusCode());
            return Optional.empty();
        }
        try {
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (!json.has("ok") || !json.get("ok").getAsBoolean()) {
                logger.warn("[API] {} → server returned ok=false", label);
                return Optional.empty();
            }
            return Optional.of(json);
        } catch (Exception e) {
            logger.error("[API] {} → parse error: {}", label, e.getMessage());
            return Optional.empty();
        }
    }
}
