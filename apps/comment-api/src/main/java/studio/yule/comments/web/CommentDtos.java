package studio.yule.comments.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Wire format shared with the site's comment panel. */
public final class CommentDtos {

    private CommentDtos() {}

    /** One comment, as the site renders it. `id` is the GitHub issue number. */
    public record CommentView(
            long id,
            String name,
            String comment,
            String imageUrl,
            int likes,
            boolean pinned,
            String createdAt,
            String issueUrl
    ) {}

    public record NewComment(
            @NotBlank @Size(max = 40) String name,
            @NotBlank @Size(max = 2000) String comment,
            /** optional data: URL from the browser's file picker */
            @Size(max = 3_000_000) String image
    ) {}
}
