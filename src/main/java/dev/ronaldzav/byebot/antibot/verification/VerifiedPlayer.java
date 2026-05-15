package dev.ronaldzav.byebot.antibot.verification;

import com.google.gson.JsonObject;

import java.time.Instant;

/** Immutable snapshot of a single verified-player record from the ByeBot API. */
public record VerifiedPlayer(
        String  username,
        String  ip,
        Instant verifiedAt,
        Instant expiresAt
) {
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /** Strips the /32 (IPv4) or /128 (IPv6) CIDR suffix the API appends. */
    public String normalizedIp() {
        int slash = ip.lastIndexOf('/');
        return slash >= 0 ? ip.substring(0, slash) : ip;
    }

    public static VerifiedPlayer fromJson(JsonObject j) {
        String  username   = j.get("username").getAsString();
        String  ip         = j.get("ip").getAsString();
        Instant verifiedAt = Instant.parse(j.get("verified_at").getAsString());
        Instant expiresAt  = j.has("expires_at") && !j.get("expires_at").isJsonNull()
                ? Instant.parse(j.get("expires_at").getAsString()) : null;
        return new VerifiedPlayer(username, ip, verifiedAt, expiresAt);
    }
}
