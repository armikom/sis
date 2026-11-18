package com.armikom.sis.pool;

import com.armikom.sis.core.auth.CourseAccess;
import com.armikom.sis.core.auth.SisPrincipal;
import com.armikom.sis.core.course.Course;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Course-scoped access for the pool: {@link CourseAccess} plus the {@code poolEnabled} switch, and helpers that find
 * the course of a chapter or question.
 */
@Component
public class PoolAccess {

    private final CourseAccess access;
    private final JdbcClient db;

    public PoolAccess(CourseAccess access, JdbcClient db) {
        this.access = access;
        this.db = db;
    }

    public SisPrincipal me() {
        return access.currentPerson();
    }

    /** Any live member of the course (or a system admin), and the pool must be on. */
    public Course member(long courseId) {
        Course c = access.requireMember(courseId);
        requireEnabled(c);
        return c;
    }

    /** Staff of the course (or a system admin), and the pool must be on. */
    public Course staff(long courseId) {
        Course c = access.requireStaff(courseId);
        requireEnabled(c);
        return c;
    }

    public boolean isStaff(long courseId) {
        return access.isStaff(courseId);
    }

    private void requireEnabled(Course c) {
        if (!c.isPoolEnabled()) {
            if (access.isStaff(c.getId())) {
                throw PoolException.conflict("The question pool is not enabled for this course.");
            }
            throw PoolException.notFound("Not found.");
        }
    }

    /** Course id of a chapter; 404 when the chapter does not exist. */
    public long courseOfChapter(long chapterId) {
        return db.sql("select course_id from chapter where id = :id").param("id", chapterId).query(Long.class)
                .optional().orElseThrow(PoolException::chapterNotFound);
    }

    /** Course id of a question; 404 when the question does not exist. */
    public long courseOfQuestion(long questionId) {
        return db.sql("select c.course_id from question q join chapter c on c.id = q.chapter_id where q.id = :id")
                .param("id", questionId).query(Long.class).optional().orElseThrow(PoolException::questionNotFound);
    }

    /** 404 unless the chapter belongs to the course. */
    public void requireChapterIn(long courseId, long chapterId) {
        if (courseOfChapter(chapterId) != courseId) throw PoolException.chapterNotFound();
    }

    /** 404 unless the question belongs to the course. */
    public void requireQuestionIn(long courseId, long questionId) {
        if (courseOfQuestion(questionId) != courseId) throw PoolException.questionNotFound();
    }
}
