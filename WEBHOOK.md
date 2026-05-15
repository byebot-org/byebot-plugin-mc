# ByeBot Webhook

The built-in webhook server lets the ByeBot API push events to your server in real
time instead of waiting for the next poll cycle (default: every 5 minutes).

## Setup

1. In `plugins/byebot/config.yml`, enable and configure the receiver:

```yaml
webhook:
  enabled: true
  port: 8080          # any free port on your machine
  ssl: false          # set true for TLS (recommended in production)
  secret: "changeme"  # shared secret — must match your ByeBot dashboard
```

2. Make sure the port is reachable from the internet (open in your firewall / forward
   on your router if needed).

3. In the ByeBot dashboard, add the webhook URL and the same secret:
   - **Plain HTTP:** `http://<your-ip>:<port>/webhook`
   - **HTTPS:**      `https://<your-ip>:<port>/webhook`

4. Restart the server. You should see in the console:
   ```
   [Webhook] Listening on http://0.0.0.0:8080 (plain HTTP).
   ```

### TLS / SSL

When `ssl: true`, a self-signed PKCS12 certificate is automatically generated at
`plugins/byebot/webhook-keystore.p12` on first start — no manual `keytool` command
needed.

> **Note:** Self-signed certificates will cause a browser warning, but the ByeBot API
> accepts them. If you want a trusted certificate, replace `webhook-keystore.p12` with
> your own PKCS12 file using the alias `byebot` and the password `byebot-internal`.

---

## Authentication

Every incoming request must include an HMAC-SHA256 signature of the raw request body,
computed using `webhook.secret` as the key:

```
X-ByeBot-Signature: sha256=<hex digest>
```

Requests with a missing, malformed, or incorrect signature are rejected with `401`.

> The `webhook.secret` is completely independent of your `api-token`.  
> Use a long random string (32+ characters recommended).

---

## Endpoint

### `POST /webhook`

**Content-Type:** `application/json`

All events share the same endpoint. The event type is identified by the `event` field
in the JSON body.

#### Success response

```json
{ "ok": true }
```

#### Error responses

| Status | Meaning                              |
|--------|--------------------------------------|
| `400`  | Malformed JSON or unknown structure  |
| `401`  | Missing or invalid signature         |
| `405`  | Method not allowed (non-POST)        |

---

## Events

### `attack_mode_changed`

Fired when the under-attack state changes in the ByeBot dashboard or via the API.
Applies the change instantly without waiting for the poll cycle.

```json
{
  "event": "attack_mode_changed",
  "data": {
    "enabled": true
  }
}
```

| Field            | Type    | Description                        |
|------------------|---------|------------------------------------|
| `data.enabled`   | boolean | `true` = attack mode ON, `false` = OFF |

---

### `player_verified`

Fired when a player completes the human verification on `https://byebot.org/verify-me`.
The player is added to the local verified cache immediately and will be allowed through
on their next connection attempt.

```json
{
  "event": "player_verified",
  "data": {
    "username":    "Steve",
    "ip":          "1.2.3.4/32",
    "verified_at": "2026-05-14T22:00:00Z",
    "expires_at":  "2026-05-21T22:00:00Z"
  }
}
```

| Field              | Type             | Description                                  |
|--------------------|------------------|----------------------------------------------|
| `data.username`    | string           | Minecraft username                           |
| `data.ip`          | string           | Player IP with CIDR suffix (`/32` or `/128`) |
| `data.verified_at` | ISO 8601 string  | When the verification was completed          |
| `data.expires_at`  | ISO 8601 string  | Expiry time (`null` = never expires)         |

---

### `player_unverified`

Fired when a player's verification is revoked (e.g. expired and cleaned up server-side,
or manually revoked from the dashboard).

```json
{
  "event": "player_unverified",
  "data": {
    "username": "Steve"
  }
}
```

| Field           | Type   | Description              |
|-----------------|--------|--------------------------|
| `data.username` | string | Minecraft username to evict from the local cache |

---

## Relation to polling

The webhook supplements the periodic API poll — it does not replace it. The poll
continues to run as a safety net in case webhook events are missed (e.g. during a
server restart or a temporary network outage).

| Feature                | Without webhook | With webhook          |
|------------------------|-----------------|-----------------------|
| Attack mode sync       | Every N minutes | Instant               |
| Player verified sync   | Every 3 minutes | Instant               |
| Resilience to downtime | Always on       | Poll acts as fallback |
