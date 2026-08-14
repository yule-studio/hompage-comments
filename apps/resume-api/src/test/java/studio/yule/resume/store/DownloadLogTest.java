package studio.yule.resume.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadLogTest {

    private DownloadLog log;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:sqlite:" + dir.resolve("test.db"));
        ds.setDriverClassName("org.sqlite.JDBC");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                create table resume_download (
                    serial     integer primary key autoincrement,
                    name       text not null,
                    email      text not null,
                    org        text,
                    purpose    text,
                    ip         text,
                    user_agent text,
                    created_at text not null
                )
                """);
        log = new DownloadLog(jdbc);
    }

    /**
     * The serial is stamped into the file that goes out, so it has to be the
     * row's real identity — not a count, and never two files sharing one.
     */
    @Test
    void serialsAreHandedOutInOrderAndNeverRepeat() {
        long first = log.record(DownloadRecord.of("홍길동", "hong@example.com", null, null, "1.2.3.4", "curl"));
        long second = log.record(DownloadRecord.of("김철수", "kim@example.com", "ACME", "채용", "1.2.3.5", "curl"));

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
    }

    @Test
    void recentReturnsNewestFirstWithEveryFieldIntact() {
        log.record(DownloadRecord.of("홍길동", "hong@example.com", null, null, "1.2.3.4", "curl"));
        log.record(DownloadRecord.of("김철수", "kim@example.com", "ACME", "채용 검토", "1.2.3.5", "Mozilla"));

        List<DownloadRecord> recent = log.recent(10);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).serial()).isEqualTo(2);
        assertThat(recent.get(0).name()).isEqualTo("김철수");
        assertThat(recent.get(0).org()).isEqualTo("ACME");
        assertThat(recent.get(0).purpose()).isEqualTo("채용 검토");
        assertThat(recent.get(0).createdAt()).isNotNull();
        assertThat(recent.get(1).serial()).isEqualTo(1);
    }

    @Test
    void limitIsRespected() {
        for (int i = 0; i < 5; i++) {
            log.record(DownloadRecord.of("n" + i, "n" + i + "@example.com", null, null, null, null));
        }
        assertThat(log.recent(2)).hasSize(2);
    }
}
