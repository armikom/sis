package com.armikom.sis.pool;

import com.armikom.sis.core.auth.CourseAccess;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Topics: numbered sections of the chapter texts (docs/API-POOL.md, "Topics"). Reading topics needs only course
 * membership, also when the pool is switched off, because exam results link to them; changing links and syncing
 * are pool staff actions.
 */
@RestController
@RequestMapping("/api/pool")
public class PoolTopicController {

    private final JdbcClient db;
    private final PoolAccess pool;
    private final CourseAccess access;
    private final Topics topics;
    private final TopicSync sync;

    public PoolTopicController(JdbcClient db, PoolAccess pool, CourseAccess access, Topics topics, TopicSync sync) {
        this.db = db;
        this.pool = pool;
        this.access = access;
        this.topics = topics;
        this.sync = sync;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TopicIdsIn(List<Long> topicIds) {}

    /** Topics of the course with the number of approved pool questions linked to each. */
    @GetMapping("/courses/{courseId}/topics")
    public List<Map<String, Object>> list(@PathVariable long courseId) {
        access.requireMember(courseId);
        return db.sql("""
                select t.id, t.chapter_id, c.number as chapter_number, c.title as chapter_title, t.number,
                    t.title_en, t.title_tr,
                    (select count(*) from question_topic qt join question q on q.id = qt.question_id
                        where qt.topic_id = t.id and q.status = 'APPROVED') as question_count
                from topic t join chapter c on c.id = t.chapter_id
                where c.course_id = :course order by c.number, t.number""").param("course", courseId)
                .query().listOfRows();
    }

    /** One topic with its section of the chapter text in both languages. */
    @GetMapping("/topics/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        long courseId = topics.courseOf(id).orElseThrow(() -> PoolException.notFound("Topic not found."));
        access.requireMember(courseId);
        Map<String, Object> row = db.sql("""
                select t.id, c.course_id, t.chapter_id, c.number as chapter_number, c.title as chapter_title, t.number,
                    t.title_en, t.title_tr, c.doc_en, c.doc_tr
                from topic t join chapter c on c.id = t.chapter_id where t.id = :id""").param("id", id)
                .query().singleRow();
        Map<String, Object> out = new LinkedHashMap<>(row);
        int number = ((Number) row.get("number")).intValue();
        out.remove("doc_en");
        out.remove("doc_tr");
        out.put("content_en", TopicSync.content((String) row.get("doc_en"), number));
        out.put("content_tr", TopicSync.content((String) row.get("doc_tr"), number));
        return out;
    }

    /** Re-derives the topics of one chapter from its texts. */
    @PostMapping("/courses/{courseId}/staff/chapters/{id}/topics/sync")
    public List<Map<String, Object>> sync(@PathVariable long courseId, @PathVariable long id) {
        pool.staff(courseId);
        pool.requireChapterIn(courseId, id);
        sync.sync(id);
        return topics.ofChapter(id);
    }

    /** Replaces the topics of a question of the course (any state). */
    @PutMapping("/courses/{courseId}/staff/questions/{id}/topics")
    public Map<String, Object> setQuestionTopics(@PathVariable long courseId, @PathVariable long id,
                                                 @RequestBody TopicIdsIn in) {
        pool.staff(courseId);
        pool.requireQuestionIn(courseId, id);
        if (in == null || in.topicIds() == null) throw PoolException.bad("Send topic_ids (an empty list clears them).");
        topics.setQuestionTopics(id, topics.requireInCourse(courseId, in.topicIds()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("topics", topics.questionShapes(id));
        return out;
    }
}
