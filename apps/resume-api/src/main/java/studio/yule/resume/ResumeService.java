package studio.yule.resume;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import studio.yule.resume.mask.PdfMasker;
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
    private final PdfMasker masker;
    private final Path source;
    private final String filename;

    public ResumeService(DownloadLog downloads, PdfMasker masker,
                         @Value("${resume.source:}") String source,
                         @Value("${resume.filename:오유찬_이력서.pdf}") String filename) {
        this.downloads = downloads;
        this.masker = masker;
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

    /** The masked PDF and the serial it was stamped with. */
    public record Issued(long serial, byte[] pdf) {}

    public Issued issue(DownloadRecord request) throws IOException {
        long serial = downloads.record(request);

        String stamp = "발급 #%d · %s · %s"
                .formatted(serial, STAMP_DATE.format(request.createdAt()), request.name());

        PdfMasker.Result result = masker.mask(source, stamp);

        /*
         * Zero hits means the source changed shape and the patterns no longer
         * find anything — the file would go out with the phone number intact.
         * Refusing is the only safe answer: this endpoint exists to mask.
         */
        if (result.hits() == 0) {
            throw new NothingMaskedException();
        }

        log.info("resume issued serial={} to={} <{}> hits={}",
                serial, request.name(), request.email(), result.hits());
        return new Issued(serial, result.pdf());
    }

    /** Raised when the masking patterns matched nothing in the source. */
    public static class NothingMaskedException extends RuntimeException {
        public NothingMaskedException() {
            super("이력서에서 마스킹할 항목을 찾지 못했습니다.");
        }
    }
}
