package com.armikom.sis.agent;

import com.armikom.sis.core.auth.CourseAccess;
import com.armikom.sis.core.auth.SisPrincipal;
import com.armikom.sis.pool.PoolAccess;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One tools/call: the person, the arguments, and the course the call is about. The course methods also check the
 * tool's audience in that course (student tools for students, teacher tools for staff).
 */
public final class McpCall {

    private final SisPrincipal person;
    private final McpTool.Args args;
    private final McpTool tool;
    private final McpCourses courses;
    private final CourseAccess access;
    private final PoolAccess pool;
    private final JdbcClient db;

    McpCall(SisPrincipal person, McpTool.Args args, McpTool tool, McpCourses courses, CourseAccess access,
            PoolAccess pool, JdbcClient db) {
        this.person = person;
        this.args = args;
        this.tool = tool;
        this.courses = courses;
        this.access = access;
        this.pool = pool;
        this.db = db;
    }

    public SisPrincipal person() { return person; }
    public McpTool.Args args() { return args; }

    /** The course from the {@code course} argument (or the only course), checked for the tool's audience. */
    public McpCourses.Ref course() {
        McpCourses.Ref ref = courses.resolve(person, args.optStr("course"));
        check(ref.id(), ref.code());
        return ref;
    }

    public long courseId() {
        return course().id();
    }

    /** The course of a question (the {@code course} argument is not needed), checked for the tool's audience. */
    public long courseOfQuestion(long questionId) {
        long courseId = pool.courseOfQuestion(questionId);
        check(courseId, null);
        return courseId;
    }

    /** The course of a row found by {@code sql} (one bigint column), checked for the tool's audience. */
    public long courseOf(String sql, long id, String notFound) {
        long courseId = db.sql(sql).param("id", id).query(Long.class).optional()
                .orElseThrow(() -> new IllegalArgumentException(notFound));
        check(courseId, null);
        return courseId;
    }

    private void check(long courseId, String code) {
        String name = code != null ? code : "this course";
        switch (tool.audience()) {
            case STUDENT -> {
                if (!access.isMember(courseId) || access.isStaff(courseId)) {
                    throw new IllegalArgumentException("The tool " + tool.name()
                            + " is for students, and you are not a student in " + name + ".");
                }
            }
            case INSTRUCTOR -> {
                if (!access.isStaff(courseId)) {
                    throw new IllegalArgumentException("Only the staff of " + name + " can use " + tool.name() + ".");
                }
            }
            case ALL -> {
                if (!access.isMember(courseId)) {
                    throw new IllegalArgumentException("You are not in " + name + ".");
                }
            }
        }
    }
}
