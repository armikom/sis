package com.armikom.sis.agent;

import com.armikom.sis.core.auth.SisPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Which course an MCP call is about. A person's pool courses are the pool-enabled, not archived courses with a live
 * enrollment. The optional {@code course} argument (code or id) picks one; without it the only course is the
 * default, otherwise the tool fails with a message that asks for {@code course}. System admins may address any
 * pool-enabled course by id or code.
 */
@Component
public class McpCourses {

    /** One course of the person; {@code role} is the enrollment role (null for an admin who is not enrolled). */
    public record Ref(long id, String code, String name, String term, String language, String role, boolean staff) {
        String label() {
            return code + " (" + term + ", id " + id + ")";
        }
    }

    private final JdbcClient db;

    public McpCourses(JdbcClient db) {
        this.db = db;
    }

    public List<Ref> mine(SisPrincipal person) {
        return db.sql("""
                select c.id, c.code, c.name, c.term, c.language, e.role
                from enrollment e join course c on c.id = e.course_id
                where e.person_id = :p and e.deleted_at is null and c.pool_enabled and c.archived_at is null
                order by c.term desc, c.code""").param("p", person.personId())
                .query((rs, i) -> new Ref(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), person.systemAdmin() || !"STUDENT".equals(rs.getString(6))))
                .list();
    }

    public boolean hasStudentCourse(SisPrincipal person) {
        return mine(person).stream().anyMatch(r -> "STUDENT".equals(r.role()));
    }

    public boolean hasStaffCourse(SisPrincipal person) {
        return person.systemAdmin() || mine(person).stream().anyMatch(r -> !"STUDENT".equals(r.role()));
    }

    /** The course for a tool call; IllegalArgumentException (shown to the agent) when it cannot be decided. */
    public Ref resolve(SisPrincipal person, String raw) {
        List<Ref> mine = mine(person);
        String wanted = raw == null ? "" : raw.trim();
        if (wanted.isEmpty()) {
            if (mine.size() == 1) {
                return mine.get(0);
            }
            if (mine.isEmpty()) {
                throw new IllegalArgumentException(person.systemAdmin()
                        ? "Pass the argument 'course' (course code or id)."
                        : "You are not in any course with the question pool.");
            }
            throw new IllegalArgumentException("You are in more than one course with the question pool: "
                    + mine.stream().map(Ref::label).collect(Collectors.joining(", "))
                    + ". Pass the argument 'course' (course code or id).");
        }
        if (wanted.matches("\\d+")) {
            long id = Long.parseLong(wanted);
            for (Ref r : mine) {
                if (r.id() == id) return r;
            }
            if (person.systemAdmin()) {
                List<Ref> any = anyByQuery("c.id = :q", id);
                if (!any.isEmpty()) return any.get(0);
            }
            throw new IllegalArgumentException("You have no course with the question pool and id " + id
                    + ". Use list_courses.");
        }
        String key = normalize(wanted);
        List<Ref> matches = mine.stream().filter(r -> normalize(r.code()).equals(key)).toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("More than one of your courses has the code " + wanted + ": "
                    + matches.stream().map(Ref::label).collect(Collectors.joining(", "))
                    + ". Pass the course id instead.");
        }
        if (person.systemAdmin()) {
            List<Ref> any = anyByQuery("upper(replace(c.code, ' ', '')) = :q", key);
            if (!any.isEmpty()) return any.get(0);
        }
        throw new IllegalArgumentException("You have no course with the question pool and the code " + wanted
                + ". Use list_courses.");
    }

    private List<Ref> anyByQuery(String where, Object value) {
        return db.sql("""
                select c.id, c.code, c.name, c.term, c.language from course c
                where c.pool_enabled and %s order by c.archived_at nulls first, c.term desc, c.id desc""".formatted(where))
                .param("q", value)
                .query((rs, i) -> new Ref(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), null, true))
                .list();
    }

    private static String normalize(String code) {
        return code.replace(" ", "").toUpperCase(Locale.ROOT);
    }
}
