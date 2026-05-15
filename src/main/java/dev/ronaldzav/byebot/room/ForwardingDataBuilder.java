package dev.ronaldzav.byebot.room;

import com.velocitypowered.api.util.GameProfile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Replicates Velocity's modern-forwarding binary payload but signed with a
 * per-room-server secret instead of the proxy's global forwarding secret.
 *
 * Wire format (same as Velocity internals):
 *   [0..31]  HMAC-SHA256(payload, secret)
 *   [32..]   payload:
 *              VarInt  forwarding version (echoed from backend request)
 *              String  remote IP
 *              UUID    16 bytes big-endian
 *              String  username
 *              VarInt  property count
 *              per property: String name, String value, bool hasSig, String? sig
 */
public final class ForwardingDataBuilder {

    private static final String VELOCITY_PLAYER_INFO_CHANNEL = "velocity:player_info";

    private ForwardingDataBuilder() {}

    public static String channelId() { return VELOCITY_PLAYER_INFO_CHANNEL; }

    public static byte[] build(byte[] secret,
                               String address,
                               UUID uuid,
                               String username,
                               List<GameProfile.Property> properties,
                               int version) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream(256);

        writeVarInt(payload, version);
        writeString(payload, address);
        writeUuid(payload, uuid);
        writeString(payload, username);

        writeVarInt(payload, properties.size());
        for (GameProfile.Property prop : properties) {
            writeString(payload, prop.getName());
            writeString(payload, prop.getValue());
            String sig = prop.getSignature();
            boolean hasSig = sig != null && !sig.isEmpty();
            payload.write(hasSig ? 1 : 0);
            if (hasSig) writeString(payload, sig);
        }

        byte[] payloadBytes = payload.toByteArray();

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        byte[] signature = mac.doFinal(payloadBytes);

        byte[] result = new byte[signature.length + payloadBytes.length];
        System.arraycopy(signature,    0, result, 0,                signature.length);
        System.arraycopy(payloadBytes, 0, result, signature.length, payloadBytes.length);
        return result;
    }

    /** Reads the forwarding version VarInt from the backend's plugin-message request data. */
    public static int readRequestedVersion(byte[] data) {
        if (data == null || data.length == 0) return 1;
        int value = 0, shift = 0;
        for (byte b : data) {
            value |= (b & 0x7F) << shift;
            shift += 7;
            if ((b & 0x80) == 0) break;
        }
        return value;
    }

    // -------------------------------------------------------------------------

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) { out.write(value); return; }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static void writeString(ByteArrayOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static void writeUuid(ByteArrayOutputStream out, UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) out.write((int) ((msb >> (i * 8)) & 0xFF));
        for (int i = 7; i >= 0; i--) out.write((int) ((lsb >> (i * 8)) & 0xFF));
    }
}
