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
    private static final Pattern META = Pattern.compile("<!--\\s*meta:likes=(\\d+)\\s*-->");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\((\\S+?)\\)");
    private static final int MAX_COMMENTS = 100;

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
        String body = issue.body() == null ? "" : issue.body();
        int likes = readLikes(body) + 1;

        String text = stripMeta(body);
        String imageUrl = readImageUrl(text);
        String comment = stripImage(text).strip();

        return toView(github.updateIssueBody(number, renderBody(comment, imageUrl, likes)));
    }

    /* ── body format ────────────────────────────────────────── */

    private String renderBody(String comment, String imageUrl, int likes) {
        StringBuilder sb = new StringBuilder(comment);
        if (imageUrl != null) {
            sb.append("\n\n![attachment](").append(imageUrl).append(")");
        }
        sb.append("\n\n<!-- meta:likes=").append(likes).append(" -->");
        return sb.toString();
    }

    private CommentView toView(GitHubIssue issue) {
        String body = issue.body() == null ? "" : issue.body();
        String text = stripMeta(body);

        return new CommentView(
                issue.number(),
                issue.title(),
                stripImage(text).strip(),
                readImageUrl(text),
                readLikes(body),
                issue.hasLabel(PINNED_LABEL),
                issue.created_at() == null ? null : issue.created_at().format(DateTimeFormatter.ISO_INSTANT),
                "https://github.com/%s/issues/%d".formatted(github.props().repoPath(), issue.number()));
    }

    private int readLikes(String body) {
        Matcher m = META.matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private String stripMeta(String body) {
        return META.matcher(body).replaceAll("");
    }

    private String readImageUrl(String body) {
        Matcher m = IMAGE.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private String stripImage(String body) {
        return IMAGE.matcher(body).replaceAll("");
    }

    /* ── attachments ────────────────────────────────────────── */

    private String storeImage(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) return null;

        String meta = dataUrl.substring(0, comma);
        String ext = meta.contains("png") ? "png" : "jpg";
        byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));

        String path = "assets/comments/%s.%s".formatted(UUID.randomUUID(), ext);
        return github.uploadImage(path, bytes);
    }
}
