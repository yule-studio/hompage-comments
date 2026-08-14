package studio.yule.resume.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import studio.yule.resume.ResumeService;
import studio.yule.resume.store.DownloadLog;
import studio.yule.resume.store.DownloadRecord;
import studio.yule.resume.web.ResumeDtos.DownloadRequest;
import studio.yule.resume.web.ResumeDtos.DownloadView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** The résumé download, consumed by hompage's About section. */
@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private final ResumeService resume;
    private final DownloadLog downloads;
    private final RateLimiter limiter;
    private final boolean trustProxy;
    private final String adminToken;

    public ResumeController(ResumeService resume, DownloadLog downloads, RateLimiter limiter,
                            @Value("${app.trust-proxy:false}") boolean trustProxy,
                            @Value("${app.admin-token:}") String adminToken) {
        this.resume = resume;
        this.downloads = downloads;
        this.limiter = limiter;
        this.trustProxy = trustProxy;
        this.adminToken = adminToken;
    }

    /**
     * Builds and returns the caller's own copy. A POST because it writes a row
     * and mints a serial — this is not a cacheable fetch of a static file, and
     * must not become one.
     */
    @PostMapping("/download")
    public ResponseEntity<byte[]> download(@Valid @RequestBody DownloadRequest input,
                                           HttpServletRequest request) throws IOException {
        requireEnabled();
        if (!limiter.allow(clientKey(request))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 시도해 주세요.");
        }

        DownloadRecord record = DownloadRecord.of(
                input.name().strip(),
                input.email().strip(),
                blankToNull(input.org()),
                blankToNull(input.purpose()),
                clientKey(request),
                request.getHeader(HttpHeaders.USER_AGENT));

        ResumeService.Issued issued = resume.issue(record);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(resume.filename(), StandardCharsets.UTF_8)
                .build());
        // The browser can only read this cross-origin if it is exposed.
        headers.set("X-Resume-Serial", Long.toString(issued.serial()));
        headers.setCacheControl("no-store");

        return new ResponseEntity<>(issued.pdf(), headers, HttpStatus.OK);
    }

    /**
     * The log, for the owner. Guarded by a shared secret rather than a login
     * because there is exactly one reader; without ADMIN_TOKEN set the route
     * does not answer at all.
     */
    @GetMapping("/downloads")
    public List<DownloadView> list(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                   @RequestParam(defaultValue = "100") int limit) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (token == null || !constantTimeEquals(adminToken, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return downloads.recent(Math.clamp(limit, 1, 500)).stream()
                .map(r -> new DownloadView(r.serial(), r.name(), r.email(),
                        r.org(), r.purpose(), r.createdAt().toString()))
                .toList();
    }

    @ExceptionHandler(ResumeService.NothingMaskedException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String nothingMasked(ResumeService.NothingMaskedException e) {
        return e.getMessage();
    }

    private void requireEnabled() {
        if (!resume.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "RESUME_SOURCE 가 설정되지 않았습니다.");
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }

    /** Length-independent compare so the token can't be guessed by timing. */
    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * X-Forwarded-For is client-controlled unless a proxy overwrites it, so it
     * is only read when the deployment says one is in front.
     */
    private String clientKey(HttpServletRequest request) {
        if (trustProxy) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
