package com.armikom.sis.pool;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Staff screens of one course (ported InstructorController without people management, which lives in core).
 * Every addressed question, flag, comment, chapter or student must belong to the course in the path (404 otherwise).
 */
@RestController
@RequestMapping("/api/pool/courses/{courseId}/staff")
public class PoolStaffController {

    private final JdbcClient db;
    private final PoolAccess access;
    private final AiService ai;
    private final Topics topics;
    private final TopicSync topicSync;

    public PoolStaffController(JdbcClient db, PoolAccess access, AiService ai, Topics topics, TopicSync topicSync) {
        this.db = db;
        this.access = access;
        this.ai = ai;
        this.topics = topics;
        this.topicSync = topicSync;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DecisionIn(String action, String note) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChapterIn(Integer number, String title, String docEn, String docTr, Boolean open) {}

    // ---------- participation ----------

    /** One row per student of the course with the numbers needed to judge participation. */
    @GetMapping("/students")
    public List<Map<String, Object>> students(@PathVariable long courseId) {
        access.staff(courseId);
        return db.sql("""
                select u.id, u.full_name, u.student_no, u.email, u.nickname, u.active, u.last_login_at,
                    (select count(*) from question q join chapter c on c.id = q.chapter_id
                        where q.author_id = u.id and c.course_id = :course) as questions,
                    (select count(*) from question q join chapter c on c.id = q.chapter_id
                        where q.author_id = u.id and c.course_id = :course and q.status = 'APPROVED') as approved,
                    (select count(distinct q.chapter_id) from question q join chapter c on c.id = q.chapter_id
                        where q.author_id = u.id and c.course_id = :course and q.status <> 'REJECTED') as chapters,
                    (select count(*) from question_attempt a join question q on q.id = a.question_id
                        join chapter c on c.id = q.chapter_id
                        where a.person_id = u.id and c.course_id = :course) as attempts,
                    (select count(*) from question_attempt a join question q on q.id = a.question_id
                        join chapter c on c.id = q.chapter_id
                        where a.person_id = u.id and c.course_id = :course and a.correct) as correct,
                    (select count(*) from question_comment m join question q on q.id = m.question_id
                        join chapter c on c.id = q.chapter_id
                        where m.author_id = u.id and c.course_id = :course and not m.deleted) as comments,
                    (select count(*) from question_flag f join question q on q.id = f.question_id
                        join chapter c on c.id = q.chapter_id
                        where f.person_id = u.id and c.course_id = :course) as flags
                from enrollment e join person u on u.id = e.person_id
                where e.course_id = :course and e.role = 'STUDENT' and e.deleted_at is null and u.deleted_at is null
                order by u.full_name""").param("course", courseId).query().listOfRows();
    }

    /** Everything one student wrote in this course, with links back to the questions. {@code id} = person id. */
    @GetMapping("/students/{id}")
    public Map<String, Object> student(@PathVariable long courseId, @PathVariable long id) {
        access.staff(courseId);
        Map<String, Object> out = new LinkedHashMap<>(db.sql("""
                select u.id, u.full_name, u.student_no, u.email, u.nickname, u.last_login_at
                from enrollment e join person u on u.id = e.person_id
                where e.course_id = :course and e.person_id = :id and e.role = 'STUDENT' and e.deleted_at is null
                  and u.deleted_at is null""")
                .param("course", courseId).param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> PoolException.notFound("Student not found.")));
        out.put("questions", db.sql("""
                select q.id, q.stem, q.status, q.created_at, c.number as chapter_number,
                    q.ai_review::text as ai_review from question q
                join chapter c on c.id = q.chapter_id
                where q.author_id = :id and c.course_id = :course order by c.number, q.id""")
                .param("id", id).param("course", courseId).query().listOfRows());
        out.put("comments", db.sql("""
                select m.id, m.question_id, m.body, m.created_at, m.deleted, q.stem from question_comment m
                join question q on q.id = m.question_id join chapter c on c.id = q.chapter_id
                where m.author_id = :id and c.course_id = :course order by m.id""")
                .param("id", id).param("course", courseId).query().listOfRows());
        out.put("flags", db.sql("""
                select f.id, f.question_id, f.reason, f.resolved, f.created_at from question_flag f
                join question q on q.id = f.question_id join chapter c on c.id = q.chapter_id
                where f.person_id = :id and c.course_id = :course order by f.id""")
                .param("id", id).param("course", courseId).query().listOfRows());
        out.put("attempts_by_chapter", db.sql("""
                select c.number as chapter_number, count(*) as attempts, count(*) filter (where a.correct) as correct
                from question_attempt a join question q on q.id = a.question_id join chapter c on c.id = q.chapter_id
                where a.person_id = :id and c.course_id = :course group by c.number order by c.number""")
                .param("id", id).param("course", courseId).query().listOfRows());
        return out;
    }

    // ---------- review ----------

    @GetMapping("/questions")
    public List<Map<String, Object>> questions(@PathVariable long courseId,
                                               @RequestParam(defaultValue = "PENDING") String status,
                                               @RequestParam(name = "chapter_id", required = false) Long chapterId,
                                               @RequestParam(name = "chapterId", required = false) Long chapterIdAlt,
                                               @RequestParam(name = "topic_id", required = false) Long topicId) {
        access.staff(courseId);
        Long chapter = chapterId != null ? chapterId : chapterIdAlt;
        return topics.addTo(db.sql("""
                select q.id, q.chapter_id, c.number as chapter_number, q.stem, q.option_a, q.option_b, q.option_c,
                    q.option_d, q.correct, q.rationale_a, q.rationale_b, q.rationale_c, q.rationale_d, q.reference,
                    q.status, q.ai_status, q.ai_review::text as ai_review, q.instructor_note, q.explanation,
                    q.created_at, u.nickname as author, u.full_name as author_name, u.id as author_id,
                    (select count(*) from question_attempt a where a.question_id = q.id) as attempts,
                    (select count(*) from question_attempt a where a.question_id = q.id and a.correct)
                        as correct_attempts,
                    (select count(*) from question_flag f where f.question_id = q.id and not f.resolved) as open_flags
                from question q
                join person u on u.id = q.author_id
                join chapter c on c.id = q.chapter_id
                where c.course_id = :course and q.status = :status
                  and (cast(:chapter as bigint) is null or q.chapter_id = :chapter)
                  and (cast(:t as bigint) is null
                       or exists (select 1 from question_topic qt where qt.question_id = q.id and qt.topic_id = :t))
                order by c.number, q.id""").param("course", courseId).param("status", status)
                .param("chapter", chapter).param("t", topicId).query().listOfRows(), "id");
    }

    /** How many questions wait in each state, for the headings of the review screen. */
    @GetMapping("/questions/counts")
    public List<Map<String, Object>> counts(@PathVariable long courseId) {
        access.staff(courseId);
        return db.sql("""
                select q.status, count(*) as count from question q join chapter c on c.id = q.chapter_id
                where c.course_id = :course group by q.status""").param("course", courseId).query().listOfRows();
    }

    @PostMapping("/questions/{id}/decision")
    public Map<String, Object> decide(@PathVariable long courseId, @PathVariable long id,
                                      @RequestBody DecisionIn in) {
        access.staff(courseId);
        access.requireQuestionIn(courseId, id);
        String note = in == null || in.note() == null || in.note().isBlank() ? null : in.note().trim();
        String status = switch (in == null || in.action() == null ? "" : in.action()) {
            case "APPROVE" -> "APPROVED";
            case "REJECT" -> "REJECTED";
            case "REVISE" -> "NEEDS_REVISION";
            default -> throw PoolException.bad("Unknown action.");
        };
        if (!"APPROVED".equals(status) && note == null) {
            throw PoolException.bad("Write a note so the student knows what to change.");
        }
        db.sql("""
                update question set status = :s, instructor_note = :n,
                    approved_at = case when :s = 'APPROVED' then now() else null end
                where id = :id""").param("s", status).param("n", note).param("id", id).update();
        if ("APPROVED".equals(status)) {
            ai.explainAsync(id);
        }
        return Map.of("ok", true);
    }

    /** Staff can fix wording at any time. The state of the question does not change. */
    @PutMapping("/questions/{id}")
    public Map<String, Object> edit(@PathVariable long courseId, @PathVariable long id,
                                    @RequestBody PoolQuestionController.QuestionIn raw) {
        access.staff(courseId);
        access.requireQuestionIn(courseId, id);
        if (raw == null) throw PoolException.bad("The question text is too short (at least 15 characters).");
        PoolQuestionController.QuestionIn in = raw.checked();
        List<Long> topicIds = in.topicIds() == null ? null : topics.requireInCourse(courseId, in.topicIds());
        db.sql(PoolQuestionController.UPDATE_FIELDS + " where id = :id").param("id", id)
                .params(PoolQuestionController.fields(in)).update();
        if (topicIds != null) topics.setQuestionTopics(id, topicIds);
        return Map.of("ok", true);
    }

    @PostMapping("/questions/{id}/ai-check")
    public Map<String, Object> rerunCheck(@PathVariable long courseId, @PathVariable long id) {
        access.staff(courseId);
        access.requireQuestionIn(courseId, id);
        ai.checkAsync(id);
        return Map.of("ok", true);
    }

    @PostMapping("/questions/{id}/explain")
    public Map<String, Object> rerunExplanation(@PathVariable long courseId, @PathVariable long id) {
        access.staff(courseId);
        access.requireQuestionIn(courseId, id);
        ai.explainAsync(id);
        return Map.of("ok", true);
    }

    // ---------- flags and moderation ----------

    @GetMapping("/flags")
    public List<Map<String, Object>> flags(@PathVariable long courseId) {
        access.staff(courseId);
        return db.sql("""
                select f.id, f.question_id, f.reason, f.resolved, f.created_at, q.stem, q.status,
                    u.full_name as student_name, u.nickname
                from question_flag f join question q on q.id = f.question_id
                join chapter c on c.id = q.chapter_id join person u on u.id = f.person_id
                where c.course_id = :course
                order by f.resolved, f.id desc""").param("course", courseId).query().listOfRows();
    }

    @PostMapping("/flags/{id}/resolve")
    public Map<String, Object> resolveFlag(@PathVariable long courseId, @PathVariable long id) {
        access.staff(courseId);
        int changed = db.sql("""
                update question_flag f set resolved = true from question q, chapter c
                where f.id = :id and q.id = f.question_id and c.id = q.chapter_id and c.course_id = :course""")
                .param("id", id).param("course", courseId).update();
        if (changed == 0) throw PoolException.notFound("Report not found.");
        return Map.of("ok", true);
    }

    /** Hides a comment from students. The text stays in the database for the participation report. */
    @PostMapping("/comments/{id}/hide")
    public Map<String, Object> hideComment(@PathVariable long courseId, @PathVariable long id) {
        access.staff(courseId);
        int changed = db.sql("""
                update question_comment m set deleted = true from question q, chapter c
                where m.id = :id and q.id = m.question_id and c.id = q.chapter_id and c.course_id = :course""")
                .param("id", id).param("course", courseId).update();
        if (changed == 0) throw PoolException.notFound("Comment not found.");
        return Map.of("ok", true);
    }

    // ---------- chapters ----------

    @PostMapping("/chapters")
    public Map<String, Object> createChapter(@PathVariable long courseId, @RequestBody ChapterIn in) {
        access.staff(courseId);
        if (in == null || in.number() == null || in.number() < 1 || in.number() > 999) {
            throw PoolException.bad("Write the chapter number (1 to 999).");
        }
        String title = title(in.title());
        if (title == null) throw PoolException.bad("Write a title (at most 300 characters).");
        boolean taken = db.sql("select count(*) from chapter where course_id = :course and number = :n")
                .param("course", courseId).param("n", in.number()).query(Integer.class).single() > 0;
        if (taken) throw PoolException.conflict("This course already has a chapter " + in.number() + ".");
        long id = db.sql("""
                insert into chapter (course_id, number, title, doc_en, doc_tr, open)
                values (:course, :n, :t, :en, :tr, :o) returning id""")
                .param("course", courseId).param("n", in.number()).param("t", title)
                .param("en", in.docEn() == null ? "" : in.docEn()).param("tr", in.docTr() == null ? "" : in.docTr())
                .param("o", in.open() == null || in.open()).query(Long.class).single();
        topicSync.sync(id);
        return Map.of("id", id);
    }

    @PutMapping("/chapters/{id}")
    public Map<String, Object> updateChapter(@PathVariable long courseId, @PathVariable long id,
                                             @RequestBody ChapterIn in) {
        access.staff(courseId);
        access.requireChapterIn(courseId, id);
        if (in == null) return Map.of("ok", true);
        String title = in.title() == null ? null : title(in.title());
        if (in.title() != null && title == null) throw PoolException.bad("Write a title (at most 300 characters).");
        if (in.number() != null) {
            if (in.number() < 1 || in.number() > 999) throw PoolException.bad("Write the chapter number (1 to 999).");
            boolean taken = db.sql("select count(*) from chapter where course_id = :course and number = :n and id <> :id")
                    .param("course", courseId).param("n", in.number()).param("id", id).query(Integer.class)
                    .single() > 0;
            if (taken) throw PoolException.conflict("This course already has a chapter " + in.number() + ".");
        }
        db.sql("""
                update chapter set title = coalesce(:t, title), doc_en = coalesce(:en, doc_en),
                    doc_tr = coalesce(:tr, doc_tr), open = coalesce(:o, open), number = coalesce(:n, number)
                where id = :id""").param("t", title).param("en", in.docEn()).param("tr", in.docTr())
                .param("o", in.open()).param("n", in.number()).param("id", id).update();
        if (in.docEn() != null || in.docTr() != null) topicSync.sync(id);
        return Map.of("ok", true);
    }

    @DeleteMapping("/chapters/{id}")
    public Map<String, Object> deleteChapter(@PathVariable long courseId, @PathVariable long id) {
        access.staff(courseId);
        access.requireChapterIn(courseId, id);
        boolean used = db.sql("select count(*) from question where chapter_id = :id").param("id", id)
                .query(Integer.class).single() > 0;
        if (used) throw PoolException.conflict("This chapter has questions and cannot be deleted. Close it instead.");
        db.sql("delete from chapter where id = :id").param("id", id).update();
        return Map.of("ok", true);
    }

    private static String title(String raw) {
        String t = raw == null ? "" : raw.trim();
        return t.isEmpty() || t.length() > 300 ? null : t;
    }
}
