package studio.yule.resume.store;

import java.time.Instant;

/**
 * One download. {@code serial} is 0 until the row is written — {@link
 * DownloadLog#record} returns the assigned value.
 */
public record DownloadRecord(
        long serial,
        String name,
        String email,
        String org,
        String purpose,
        String ip,
        String userAgent,
        Instant createdAt) {

    public static DownloadRecord of(String name, String email, String org, String purpose,
                                    String ip, String userAgent) {
        return new DownloadRecord(0, name, email, org, purpose, ip, userAgent, Instant.now());
    }
}
