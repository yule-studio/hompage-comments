package studio.yule.comments;

import org.springframework.stereotype.Service;
import studio.yule.comments.github.GitHubClient;
import studio.yule.comments.github.GitHubIssue;
import studio.yule.comments.web.CommentDtos.CommentView;
import studio.yule.comments.web.CommentDtos.NewComment;

import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comments are GitHub issues.
 *
 * The visitor's name is the issue title and the comment is the body, so a
 * comment is readable and moderatable straight from the GitHub UI — closing an
 * issue hides it, and the `pinned` label pins it to the top of the wall.
 *
 * Everything the wall needs but an issue has no field for (the like count) is
 * kept in a trailing HTML comment, which GitHub renders as nothing.
 */
@Service
public class CommentService {

    private static final String LABEL = "comment";
    private static final String PINNED_LABEL = "pinned";
    private static final Pattern META =
            Pattern.compile("<!--\\s*meta:likes=(\\d+)(?:;image=(\\S*))?\\s*-->");
    private static final int MAX_COMMENTS = 100;
    /** decoded attachment ceiling — these become commits in the repo */
    private static final int MAX_IMAGE_BYTES = 1_500_000;

    private final GitHubClient github;

    public CommentService(GitHubClient github) {
        this.github = github;
    }

    public boolean enabled() {
        return github.props().configured();
    }

    public List<CommentView> list() {
        return github.listIssues(MAX_COMMENTS).stream()
                .filter(issue -> !issue.isPullRequest())
                // only issues this service filed — the repo's own development
                // issues are not comments and must not surface on the site
                .filter(issue -> issue.hasLabel(LABEL))
                .map(this::toView)
                .sorted((a, b) -> Boolean.compare(b.pinned(), a.pinned()))
                .toList();
    }

    public CommentView create(NewComment input) {
        String imageUrl = null;
        if (github.props().attachmentsEnabled() && input.image() != null && !input.image().isBlank()) {
            imageUrl = storeImage(input.image());
        }

        String body = renderBody(input.comment().strip(), imageUrl, 0);
        GitHubIssue issue = github.createIssue(input.name().strip(), body, List.of(LABEL));
        return toView(issue);
    }

    /**
     * Reactions would be the natural home for likes, but every visitor shares
     * this service's token — GitHub would collapse them into one reaction. So
     * the count lives in the body and is rewritten on each like.
     */
    public CommentView like(long number) {
        GitHubIssue issue = github.getIssue(number);

        /*
         * The issue number arrives straight from the client, and liking rewrites
         * the body — so anything that isn't one of our own comments has to be
         * refused, or a stranger could overwrite a development issue.
         */
        if (issue == null || issue.isPullRequest() || !issue.hasLabel(LABEL)) {
            throw new NotACommentException(number);
        }

        String body = issue.body() == null ? "" : issue.body();
        String imageUrl = readImageUrl(body);
        int likes = readLikes(body) + 1;

        return toView(github.updateIssueBody(number, renderBody(readComment(body), imageUrl, likes)));
    }

    /** Raised when a client points a comment operation at some other issue. */
    public static class NotACommentException extends RuntimeException {
        public NotACommentException(long number) {
            super("issue #" + number + " is not a comment");
        }
    }

    /* ── body format ────────────────────────────────────────── */

    private String renderBody(String comment, String imageUrl, int likes) {
        StringBuilder sb = new StringBuilder(comment);
        if (imageUrl != null) {
            sb.append("\n\n").append(attachmentMarkdown(imageUrl));
        }
        sb.append("\n\n<!-- meta:likes=").append(likes);
        if (imageUrl != null) {
            sb.append(";image=").append(imageUrl);
        }
        sb.append(" -->");
        return sb.toString();
    }

    private String attachmentMarkdown(String url) {
        return "![attachment](" + url + ")";
    }

    private CommentView toView(GitHubIssue issue) {
        String body = issue.body() == null ? "" : issue.body();

        return new CommentView(
                issue.number(),
                issue.title(),
                readComment(body),
                readImageUrl(body),
                readLikes(body),
                issue.hasLabel(PINNED_LABEL),
                issue.created_at() == null ? null : issue.created_at().format(DateTimeFormatter.ISO_INSTANT),
                "https://github.com/%s/issues/%d".formatted(github.props().repoPath(), issue.number()));
    }

    private int readLikes(String body) {
        Matcher m = META.matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * The attachment URL is read from the metadata, not by scanning the body for
     * markdown — a visitor can type `![x](...)` inside their own comment, and
     * that must stay their text rather than becoming the attachment.
     */
    private String readImageUrl(String body) {
        Matcher m = META.matcher(body);
        if (m.find()) {
            String url = m.group(2);
            return (url == null || url.isBlank()) ? null : url;
        }
        return null;
    }

    /** The visitor's text: the body minus the block this service appended. */
    private String readComment(String body) {
        String text = META.matcher(body).replaceAll("");
        String imageUrl = readImageUrl(body);
        if (imageUrl != null) {
            text = text.replace(attachmentMarkdown(imageUrl), "");
        }
        return text.strip();
    }

    /* ── attachments ────────────────────────────────────────── */

    /**
     * Attachments become commits in the repository, so the payload is checked
     * before it is trusted: a declared image type, decodable base64, a real
     * image signature, and a size ceiling.
     */
    private String storeImage(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) throw new BadAttachmentException("이미지 형식이 올바르지 않습니다.");

        String header = dataUrl.substring(0, comma).toLowerCase();
        String ext;
        if (header.startsWith("data:image/png")) {
            ext = "png";
        } else if (header.startsWith("data:image/jpeg") || header.startsWith("data:image/jpg")) {
            ext = "jpg";
        } else {
            throw new BadAttachmentException("PNG 또는 JPEG 이미지만 첨부할 수 있습니다.");
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            throw new BadAttachmentException("이미지를 읽을 수 없습니다.");
        }

        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            throw new BadAttachmentException("이미지 용량이 너무 큽니다.");
        }
        if (!looksLikeImage(bytes, ext)) {
            throw new BadAttachmentException("이미지 파일이 아닙니다.");
        }

        String path = "assets/comments/%s.%s".formatted(UUID.randomUUID(), ext);
        return github.uploadImage(path, bytes);
    }

    /** Magic bytes, so the declared type has to match the actual content. */
    private boolean looksLikeImage(byte[] b, String ext) {
        if ("png".equals(ext)) {
            return b.length > 8
                    && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        }
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
    }

    /** Raised when an attachment is not a usable image. */
    public static class BadAttachmentException extends RuntimeException {
        public BadAttachmentException(String message) {
            super(message);
        }
    }
}
