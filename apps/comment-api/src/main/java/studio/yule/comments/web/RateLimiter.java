package studio.yule.comments.web;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A public endpoint that writes to a GitHub repo is worth throttling. This is a
 * per-address sliding window held in memory — enough for a personal site, and
 * deliberately not a distributed limiter: one instance, one process.
 */
@Component
public class RateLimiter {

    private static final int MAX_PER_WINDOW = 5;
    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public boolean allow(String key) {
        Deque<Instant> times = hits.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        Instant cutoff = Instant.now().minus(WINDOW);

        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
                times.pollFirst();
            }
            if (times.size() >= MAX_PER_WINDOW) {
                return false;
            }
            times.addLast(Instant.now());
            return true;
        }
    }
}
