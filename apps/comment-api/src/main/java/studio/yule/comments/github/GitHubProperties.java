package studio.yule.comments.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where comments are filed and who files them.
 *
 * {@code token} is a fine-grained PAT with Issues:write (and Contents:write if
 * image attachments are on). It only ever exists as an environment variable —
 * never in the repository.
 */
@ConfigurationProperties(prefix = "github")
public record GitHubProperties(
        String owner,
        String repo,
        String token,
        String branch,
        boolean attachmentsEnabled
) {
    public GitHubProperties {
        branch = (branch == null || branch.isBlank()) ? "main" : branch;
    }

    public String repoPath() {
        return owner + "/" + repo;
    }

    public boolean configured() {
        return token != null && !token.isBlank();
    }
}
