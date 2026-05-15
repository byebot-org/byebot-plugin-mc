package dev.ronaldzav.byebot.antibot.check;

import dev.ronaldzav.byebot.lang.Messages;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Blocks IPs that exceed {@code maxJoinsPerMinute} connection attempts within
 * a 60-second sliding window. Offending IPs are temp-banned for
 * {@code banDurationSeconds}.
 */
public final class RateLimitCheck implements AntibotCheck {

    private static final long WINDOW_MS = 60_000L;

    private final int  maxJoinsPerMinute;
    private final long banDurationMs;

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> windows     =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>                        bannedUntil =
            new ConcurrentHashMap<>();

    public RateLimitCheck(int maxJoinsPerMinute, int banDurationSeconds) {
        this.maxJoinsPerMinute = maxJoinsPerMinute;
        this.banDurationMs     = banDurationSeconds * 1000L;
    }

    @Override
    public String getName() { return "rate-limit"; }

    @Override
    public CheckResult test(InetAddress address, String username) {
        String ip  = address.getHostAddress();
        long   now = System.currentTimeMillis();

        // Check active ban first (O(1))
        Long banExpiry = bannedUntil.get(ip);
        if (banExpiry != null) {
            if (now < banExpiry) return CheckResult.block(Messages.BLOCK_RATE_LIMIT);
            bannedUntil.remove(ip);
        }

        // Sliding-window rate check
        ConcurrentLinkedDeque<Long> deque =
                windows.computeIfAbsent(ip, k -> new ConcurrentLinkedDeque<>());

        deque.removeIf(t -> t < now - WINDOW_MS);
        deque.addLast(now);

        if (deque.size() > maxJoinsPerMinute) {
            bannedUntil.put(ip, now + banDurationMs);
            windows.remove(ip);
            return CheckResult.block(Messages.BLOCK_RATE_LIMIT);
        }
        return CheckResult.PASS;
    }

    /** Evicts stale entries — call periodically to prevent unbounded growth. */
    public void cleanup() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        long now    = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> {
            e.getValue().removeIf(t -> t < cutoff);
            return e.getValue().isEmpty();
        });
        bannedUntil.entrySet().removeIf(e -> e.getValue() <= now);
    }
}
