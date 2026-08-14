package studio.yule.resume.web;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Each download renders every page of a PDF, so this throttles CPU as much as
 * it throttles the log. Deliberately a copy of comment-api's rather than a
 * shared module: the two services deploy independently, and thirty lines is a
 * cheaper price than a library both must upgrade together.
 */
@Component
public class RateLimiter {

    private static final int MAX_PER_WINDOW = 3;
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
