package com.armikom.sis.pool;

import com.armikom.sis.core.auth.SisPrincipal;
import com.armikom.sis.core.course.Course;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Chapters of a course for its members, and the pool settings the UI needs (ported ChapterController + /api/me). */
@RestController
@RequestMapping("/api/pool")
public class PoolChapterController {

    private final JdbcClient db;
    private final PoolAccess access;
    private final AiService ai;
    private final Topics topics;

    public PoolChapterController(JdbcClient db, PoolAccess access, AiService ai, Topics topics) {
        this.db = db;
        this.access = access;
        this.ai = ai;
        this.topics = topics;
    }

    /** The pool-specific facts the old app read from /api/me, for one course. */
    @GetMapping("/courses/{courseId}/me")
    public Map<String, Object> me(@PathVariable long courseId) {
        Course c = access.member(courseId);
        SisPrincipal me = access.me();
        Map<String, Object> course = new LinkedHashMap<>();
        course.put("id", c.getId());
        course.put("code", c.getCode());
        course.put("name", c.getName());
        course.put("term", c.getTerm());
        course.put("language", c.getLanguage());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("course", course);
        out.put("staff", access.isStaff(courseId));
        out.put("nickname", me.nickname());
        out.put("questions_per_chapter", c.getQuestionsPerChapter());
        out.put("ask_ai_per_day", c.getAskAiPerDay());
        out.put("ai_enabled", ai.enabled());
        return out;
    }

    /** Chapters with the signed-in person's own progress. */
    @GetMapping("/courses/{courseId}/chapters")
    public List<Map<String, Object>> list(@PathVariable long courseId) {
        access.member(courseId);
        return db.sql("""
                select c.id, c.number, c.title, c.open,
                    (c.doc_en <> '') as has_doc_en, (c.doc_tr <> '') as has_doc_tr,
                    (select count(*) from question q where q.chapter_id = c.id and q.status = 'APPROVED') as approved,
                    (select count(*) from question q where q.chapter_id = c.id and q.author_id = :u
                        and q.status <> 'REJECTED') as my_questions,
                    (select count(*) from question_attempt a join question q on q.id = a.question_id
                        where q.chapter_id = c.id and a.person_id = :u and q.status = 'APPROVED') as my_attempts,
                    (select count(*) from question_attempt a join question q on q.id = a.question_id
                        where q.chapter_id = c.id and a.person_id = :u and a.correct and q.status = 'APPROVED')
                        as my_correct
                from chapter c where c.course_id = :course order by c.number""")
                .param("u", access.me().personId()).param("course", courseId).query().listOfRows();
    }

    /** One chapter with its reference document in both languages and its topics. */
    @GetMapping("/chapters/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        access.member(access.courseOfChapter(id));
        Map<String, Object> out = new LinkedHashMap<>(db.sql(
                        "select id, number, title, open, doc_en, doc_tr, course_id from chapter where id = :id")
                .param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(PoolException::chapterNotFound));
        out.put("topics", topics.ofChapter(id));
        return out;
    }
}
