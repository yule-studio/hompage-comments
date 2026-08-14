package studio.yule.resume;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ResumeApiApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ResumeApiApplication.class);
        // Runs once the environment is bound but before any bean is created —
        // early enough that schema.sql finds somewhere to write.
        app.addInitializers(ctx -> ensureDatabaseDirectory(ctx.getEnvironment()));
        app.run(args);
    }

    /**
     * SQLite opens a file but will not create the folder it sits in, and the
     * default path is relative — under `gradle bootRun` that resolves to the
     * module directory, not the repo root. Left alone, the first start of a
     * fresh checkout dies on "path to './data/resume.db' does not exist".
     */
    private static void ensureDatabaseDirectory(Environment env) {
        String url = env.getProperty("spring.datasource.url", "");
        if (!url.startsWith("jdbc:sqlite:")) {
            return;
        }
        String file = url.substring("jdbc:sqlite:".length());
        // in-memory URLs have no directory to make
        if (file.isBlank() || file.startsWith(":")) {
            return;
        }
        Path parent = Path.of(file).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new UncheckedIOException("발급 대장 디렉터리를 만들 수 없습니다: " + parent, e);
        }
    }
}
