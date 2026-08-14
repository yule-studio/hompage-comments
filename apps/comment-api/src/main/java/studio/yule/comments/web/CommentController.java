package studio.yule.comments.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import studio.yule.comments.CommentService;
import studio.yule.comments.web.CommentDtos.CommentView;
import studio.yule.comments.web.CommentDtos.NewComment;

import java.util.List;

/** The comment wall's API, consumed by hompage's Contact section. */
@RestController
@RequestMapping("/api/comments")
public class CommentController {

    private final CommentService comments;
    private final RateLimiter limiter;
    private final boolean trustProxy;

    public CommentController(CommentService comments, RateLimiter limiter,
                             @Value("${app.trust-proxy:false}") boolean trustProxy) {
        this.comments = comments;
        this.limiter = limiter;
        this.trustProxy = trustProxy;
    }

    @GetMapping
    public List<CommentView> list() {
        requireEnabled();
        return comments.list();
    }

    @PostMapping
    public ResponseEntity<CommentView> create(@Valid @RequestBody NewComment input, HttpServletRequest request) {
        requireEnabled();
        if (!limiter.allow(clientKey(request))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 시도해 주세요.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(comments.create(input));
    }

    @PostMapping("/{number}/like")
    public CommentView like(@PathVariable long number, HttpServletRequest request) {
        requireEnabled();
        if (!limiter.allow(clientKey(request))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 시도해 주세요.");
        }
        return comments.like(number);
    }

    @ExceptionHandler(CommentService.NotACommentException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notAComment(CommentService.NotACommentException e) {
        return e.getMessage();
    }

    @ExceptionHandler(CommentService.BadAttachmentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badAttachment(CommentService.BadAttachmentException e) {
        return e.getMessage();
    }

    private void requireEnabled() {
        if (!comments.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "GITHUB_TOKEN 이 설정되지 않았습니다.");
        }
    }

    /**
     * X-Forwarded-For is set by the client unless a proxy overwrites it, so
     * trusting it by default would hand out a fresh rate-limit bucket per
     * request. Only read it when the deployment says a proxy is in front
     * (app.trust-proxy=true).
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
