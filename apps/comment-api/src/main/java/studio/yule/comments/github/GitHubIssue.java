package studio.yule.comments.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.List;

/** The slice of GitHub's issue payload this service reads. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubIssue(
        long number,
        String title,
        String body,
        OffsetDateTime created_at,
        User user,
        List<Label> labels,
        PullRequest pull_request
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Label(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String login) {}

    /** Present only on pull requests — GitHub returns those from /issues too. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequest(String url) {}

    public boolean isPullRequest() {
        return pull_request != null;
    }

    public boolean hasLabel(String name) {
        return labels != null && labels.stream().anyMatch(l -> name.equalsIgnoreCase(l.name()));
    }
}
