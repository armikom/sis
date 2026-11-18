package com.armikom.sis.pool;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Topic queries shared by the pool controllers, the exam service and the MCP tools: topics of a chapter, links of
 * pool and exam questions (explicit and effective), and the same-course check for topic ids.
 */
@Component
public class Topics {

    /** One topic with its chapter, as every module needs it. */
    public record Ref(long id, long chapterId, int chapterNumber, String chapterTitle, int number, String titleEn,
                      String titleTr) {

        /** The pool's question-topic shape: {id, chapter_id, chapter_number, number, title_en, title_tr}. */
        public Map<String, Object> questionShape() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("chapter_id", chapterId);
            m.put("chapter_number", chapterNumber);
            m.put("number", number);
            m.put("title_en", titleEn);
            m.put("title_tr", titleTr);
            return m;
        }
    }

    private static final String REF_COLUMNS = """
            t.id, t.chapter_id, c.number as chapter_number, c.title as chapter_title, t.number, t.title_en, t.title_tr""";

    private final JdbcClient db;

    public Topics(JdbcClient db) {
        this.db = db;
    }

    private static Ref ref(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Ref(rs.getLong("id"), rs.getLong("chapter_id"), rs.getInt("chapter_number"),
                rs.getString("chapter_title"), rs.getInt("number"), rs.getString("title_en"), rs.getString("title_tr"));
    }

    /** Chapter topic shape {id, number, title_en, title_tr}, ordered by number. */
    public List<Map<String, Object>> ofChapter(long chapterId) {
        return db.sql("select id, number, title_en, title_tr from topic where chapter_id = :c order by number")
                .param("c", chapterId).query().listOfRows();
    }

    /** Course id of a topic, if it exists. */
    public java.util.Optional<Long> courseOf(long topicId) {
        return db.sql("select c.course_id from topic t join chapter c on c.id = t.chapter_id where t.id = :id")
                .param("id", topicId).query(Long.class).optional();
    }

    /** Topics of the course by id; ordered by chapter number, number. */
    public List<Ref> ofCourse(long courseId) {
        return db.sql("select " + REF_COLUMNS + " from topic t join chapter c on c.id = t.chapter_id "
                        + "where c.course_id = :course order by c.number, t.number")
                .param("course", courseId).query((rs, i) -> ref(rs)).list();
    }

    /**
     * Checks that every id is a topic of the course; returns the distinct ids in the given order.
     * Throws {@link PoolException} 400 otherwise.
     */
    public List<Long> requireInCourse(long courseId, Collection<Long> topicIds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : topicIds) {
            if (id == null) throw PoolException.bad("A topic does not belong to this course.");
            ids.add(id);
        }
        if (ids.isEmpty()) return List.of();
        int found = db.sql("""
                select count(*) from topic t join chapter c on c.id = t.chapter_id
                where t.id in (:ids) and c.course_id = :course""").param("ids", ids).param("course", courseId)
                .query(Integer.class).single();
        if (found != ids.size()) throw PoolException.bad("A topic does not belong to this course.");
        return new ArrayList<>(ids);
    }

    /** True when every id is a topic of the course (no exception). */
    public boolean allInCourse(long courseId, Collection<Long> topicIds) {
        try {
            requireInCourse(courseId, topicIds);
            return true;
        } catch (PoolException e) {
            return false;
        }
    }

    // ---------- pool questions ----------

    /** Replaces the topic set of a pool question (ids must already be checked). */
    @Transactional
    public void setQuestionTopics(long questionId, Collection<Long> topicIds) {
        db.sql("delete from question_topic where question_id = :q").param("q", questionId).update();
        for (long t : topicIds) {
            db.sql("insert into question_topic (question_id, topic_id) values (:q, :t) on conflict do nothing")
                    .param("q", questionId).param("t", t).update();
        }
    }

    /** Topics of pool questions, by question id (questions without topics are absent). */
    public Map<Long, List<Ref>> ofQuestions(Collection<Long> questionIds) {
        Map<Long, List<Ref>> out = new LinkedHashMap<>();
        if (questionIds.isEmpty()) return out;
        db.sql("select qt.question_id as owner, " + REF_COLUMNS + """
                         from question_topic qt join topic t on t.id = qt.topic_id join chapter c on c.id = t.chapter_id
                        where qt.question_id in (:ids) order by c.number, t.number""")
                .param("ids", new LinkedHashSet<>(questionIds))
                .query((rs, i) -> Map.entry(rs.getLong("owner"), ref(rs))).list()
                .forEach(e -> out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return out;
    }

    /** Adds {@code topics} (question shape) to each row keyed by {@code idKey}; returns the same rows. */
    public List<Map<String, Object>> addTo(List<Map<String, Object>> rows, String idKey) {
        List<Long> ids = rows.stream().map(r -> ((Number) r.get(idKey)).longValue()).toList();
        Map<Long, List<Ref>> topics = ofQuestions(ids);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = r instanceof LinkedHashMap<String, Object> lm ? lm : new LinkedHashMap<>(r);
            m.put("topics", topics.getOrDefault(((Number) r.get(idKey)).longValue(), List.of()).stream()
                    .map(Ref::questionShape).toList());
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> questionShapes(long questionId) {
        return ofQuestions(List.of(questionId)).getOrDefault(questionId, List.of()).stream()
                .map(Ref::questionShape).toList();
    }

    // ---------- exam questions ----------

    /** Replaces the explicit topic set of an exam question (ids must already be checked). */
    @Transactional
    public void setExamQuestionTopics(long examQuestionId, Collection<Long> topicIds) {
        db.sql("delete from exam_question_topic where exam_question_id = :q").param("q", examQuestionId).update();
        for (long t : topicIds) {
            db.sql("""
                    insert into exam_question_topic (exam_question_id, topic_id) values (:q, :t)
                    on conflict do nothing""").param("q", examQuestionId).param("t", t).update();
        }
    }

    /** Explicit topic ids of exam questions, by exam question id (ascending topic id). */
    public Map<Long, List<Long>> explicitOfExamQuestions(Collection<Long> examQuestionIds) {
        Map<Long, List<Long>> out = new LinkedHashMap<>();
        if (examQuestionIds.isEmpty()) return out;
        db.sql("""
                select exam_question_id, topic_id from exam_question_topic
                where exam_question_id in (:ids) order by topic_id""")
                .param("ids", new LinkedHashSet<>(examQuestionIds))
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getLong(2))).list()
                .forEach(e -> out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return out;
    }

    /** Effective topics (explicit ∪ the linked pool question's) of exam questions, by exam question id. */
    public Map<Long, List<Ref>> effectiveOfExamQuestions(Collection<Long> examQuestionIds) {
        Map<Long, List<Ref>> out = new LinkedHashMap<>();
        if (examQuestionIds.isEmpty()) return out;
        db.sql("select x.owner, " + REF_COLUMNS + """
                         from (select exam_question_id as owner, topic_id from exam_question_topic
                               where exam_question_id in (:ids)
                               union
                               select eq.id, qt.topic_id from exam_question eq
                               join question_topic qt on qt.question_id = eq.pool_question_id
                               where eq.id in (:ids)) x
                        join topic t on t.id = x.topic_id join chapter c on c.id = t.chapter_id
                        order by c.number, t.number""")
                .param("ids", new LinkedHashSet<>(examQuestionIds))
                .query((rs, i) -> Map.entry(rs.getLong("owner"), ref(rs))).list()
                .forEach(e -> out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return out;
    }
}
