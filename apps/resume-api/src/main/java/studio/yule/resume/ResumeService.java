package studio.yule.resume;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import studio.yule.resume.mask.PdfStamper;
import studio.yule.resume.store.DownloadLog;
import studio.yule.resume.store.DownloadRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Hands out the résumé: record who asked, then build their copy.
 *
 * The order matters. The serial is assigned first because it is stamped into
 * the file, so there is no copy in existence that is not already accounted for
 * in the log.
 */
@Service
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);
    private static final DateTimeFormatter STAMP_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Seoul"));

    private final DownloadLog downloads;
    private final PdfStamper stamper;
    private final Path source;
    private final String filename;

    public ResumeService(DownloadLog downloads, PdfStamper stamper,
                         @Value("${resume.source:}") String source,
                         @Value("${resume.filename:오유찬_이력서.pdf}") String filename) {
        this.downloads = downloads;
        this.stamper = stamper;
        this.source = (source == null || source.isBlank()) ? null : Path.of(source);
        this.filename = filename;
    }

    /** False when no source résumé is configured or readable. */
    public boolean enabled() {
        return source != null && Files.isReadable(source);
    }

    public String filename() {
        return filename;
    }

    /** The stamped PDF and the serial it carries. */
    public record Issued(long serial, byte[] pdf) {}

    public Issued issue(DownloadRecord request) throws IOException {
        long serial = downloads.record(request);

        String stamp = "발급 #%d · %s · %s"
                .formatted(serial, STAMP_DATE.format(request.createdAt()), request.name());

        byte[] pdf = stamper.stamp(source, stamp);

        log.info("resume issued serial={} to={} <{}>", serial, request.name(), request.email());
        return new Issued(serial, pdf);
    }
}
