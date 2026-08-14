package studio.yule.resume.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ResumeDtos {

    private ResumeDtos() {
    }

    /**
     * What a visitor tells us before the file is built. None of it is verified —
     * a determined stranger can type anything. It is a speed bump plus a record,
     * not authentication, and the README says so out loud.
     */
    public record DownloadRequest(
            @NotBlank(message = "이름을 입력해 주세요.")
            @Size(max = 60)
            String name,

            @NotBlank(message = "이메일을 입력해 주세요.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            @Size(max = 120)
            String email,

            @Size(max = 80)
            String org,

            @Size(max = 200)
            String purpose) {
    }

    /** A row of the log, for the owner's listing. */
    public record DownloadView(
            long serial,
            String name,
            String email,
            String org,
            String purpose,
            String createdAt) {
    }
}
