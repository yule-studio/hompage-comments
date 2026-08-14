package studio.yule.comments.github;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over the GitHub REST API — the only place that knows about
 * tokens and endpoints.
 */
@Component
@EnableConfigurationProperties(GitHubProperties.class)
public class GitHubClient {

    private static final String API = "https://api.github.com";

    private final GitHubProperties props;
    private final RestClient rest;

    public GitHubClient(GitHubProperties props, RestClient.Builder builder) {
        this.props = props;
        this.rest = builder
                .baseUrl(API)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        props.configured() ? "Bearer " + props.token() : "")
                .build();
    }

    public GitHubProperties props() {
        return props;
    }

    /** Newest first. Pull requests are filtered out by the caller. */
    public List<GitHubIssue> listIssues(int perPage) {
        return rest.get()
                .uri("/repos/{owner}/{repo}/issues?state=open&sort=created&direction=desc&per_page={n}",
                        props.owner(), props.repo(), perPage)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<GitHubIssue>>() {});
    }

    public GitHubIssue getIssue(long number) {
        return rest.get()
                .uri("/repos/{owner}/{repo}/issues/{n}", props.owner(), props.repo(), number)
                .retrieve()
                .body(GitHubIssue.class);
    }

    public GitHubIssue createIssue(String title, String body, List<String> labels) {
        return rest.post()
                .uri("/repos/{owner}/{repo}/issues", props.owner(), props.repo())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("title", title, "body", body, "labels", labels))
                .retrieve()
                .body(GitHubIssue.class);
    }

    public GitHubIssue updateIssueBody(long number, String body) {
        return rest.patch()
                .uri("/repos/{owner}/{repo}/issues/{n}", props.owner(), props.repo(), number)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", body))
                .retrieve()
                .body(GitHubIssue.class);
    }

    /**
     * Commits an image into the repo and returns its raw URL.
     *
     * GitHub's API has no attachment upload — the drag-and-drop box in the web
     * UI uses a private session endpoint. Committing the file is the supported
     * way to get a stable URL that renders inside an issue body.
     */
    public String uploadImage(String path, byte[] bytes) {
        rest.put()
                .uri("/repos/{owner}/{repo}/contents/{path}", props.owner(), props.repo(), path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "message", "chore(comments): attach " + path,
                        "content", Base64.getEncoder().encodeToString(bytes),
                        "branch", props.branch()))
                .retrieve()
                .toBodilessEntity();

        return "https://raw.githubusercontent.com/%s/%s/%s".formatted(props.repoPath(), props.branch(), path);
    }
}
