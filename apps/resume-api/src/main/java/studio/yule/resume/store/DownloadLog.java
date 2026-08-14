package studio.yule.resume.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

/**
 * Who has a copy, and which copy they have.
 *
 * The serial is the point of the table. It is stamped onto the file that goes
 * out, so a résumé that turns up somewhere it should not can be traced back to
 * exactly one row here — which is the only reason to ask a visitor for their
 * name before handing them a document.
 *
 * These rows are other people's personal data. They stay in a local SQLite file
 * and are deliberately not written to the public GitHub repo the way comments
 * are; see the README on where the file lives and how long to keep it.
 */
@Repository
public class DownloadLog {

    private final JdbcTemplate jdbc;

    public DownloadLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Writes the row and returns its serial. */
    public long record(DownloadRecord record) {
        /*
         * The insert and last_insert_rowid() have to see the same connection —
         * from the pool's point of view they are two unrelated statements, and
         * the rowid is per connection. One ConnectionCallback keeps them
         * together without depending on the driver's generated-key support.
         */
        Long serial = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Long>) con -> {
            try (PreparedStatement ps = con.prepareStatement("""
                    insert into resume_download
                        (name, email, org, purpose, ip, user_agent, created_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, record.name());
                ps.setString(2, record.email());
                ps.setString(3, record.org());
                ps.setString(4, record.purpose());
                ps.setString(5, record.ip());
                ps.setString(6, record.userAgent());
                ps.setString(7, record.createdAt().toString());
                ps.executeUpdate();
            }
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("select last_insert_rowid()")) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
        return serial == null ? 0L : serial;
    }

    /** Most recent first. For the owner's own eyes — see ResumeController. */
    public List<DownloadRecord> recent(int limit) {
        return jdbc.query("""
                select serial, name, email, org, purpose, ip, user_agent, created_at
                  from resume_download
                 order by serial desc
                 limit ?
                """,
                (rs, row) -> new DownloadRecord(
                        rs.getLong("serial"),
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getString("org"),
                        rs.getString("purpose"),
                        rs.getString("ip"),
                        rs.getString("user_agent"),
                        Instant.parse(rs.getString("created_at"))),
                limit);
    }
}
