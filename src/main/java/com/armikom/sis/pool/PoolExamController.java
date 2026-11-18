package com.armikom.sis.pool;

import com.armikom.sis.exam.ExamResultRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Exam builder (staff), ported from question-pool onto the unified exam tables. An exam is a random draw of approved
 * questions, a number per chapter: one {@code exam} row (forms, seed) and one {@code exam_question} per drawn pool
 * question (type CHOICE, 4 choices, the key, {@code pool_question_id}). Each form (A, B, ...) has the same questions;
 * the order inside a chapter and the order of the options are shuffled per form. The shuffle depends only on the
 * exam's seed, the form and the question, so a form and its answer key always agree.
 */
@RestController
@RequestMapping("/api/pool/courses/{courseId}/staff/exams")
public class PoolExamController {

    private static final List<String> LETTERS = List.of("A", "B", "C", "D");
    private static final String DRAW = """
            select q.id from question q
            where q.chapter_id = :c and q.status = 'APPROVED'
              and q.id not in (select eq.pool_question_id from exam_question eq
                               where eq.exam_id = :e and eq.pool_question_id is not null)
              and (not :skip or not exists (select 1 from question_flag f where f.question_id = q.id and not f.resolved))
            order by random() limit :n""";

    private final JdbcClient db;
    private final PoolAccess access;
    private final ExamResultRepository results;
    private final Clock clock;

    public PoolExamController(JdbcClient db, PoolAccess access, ExamResultRepository results, Clock clock) {
        this.db = db;
        this.access = access;
        this.results = results;
        this.clock = clock;
    }

    /** {@code counts}: chapter id → how many questions. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExamIn(String title, Integer forms, Map<Long, Integer> counts, Boolean skipFlagged) {}

    /** Exams of the course that have at least one question drawn from the pool. */
    @GetMapping
    public List<Map<String, Object>> list(@PathVariable long courseId) {
        access.staff(courseId);
        return db.sql("""
                select e.id, e.title, e.forms, e.created_at,
                    (select count(*) from exam_question i where i.exam_id = e.id and i.pool_question_id is not null)
                        as questions
                from exam e where e.course_id = :course
                  and exists (select 1 from exam_question i where i.exam_id = e.id and i.pool_question_id is not null)
                order by e.id desc""").param("course", courseId).query().listOfRows();
    }

    /** Draws the questions. Fails, and saves nothing, when a chapter does not have enough approved questions. */
    @PostMapping
    @Transactional
    public Map<String, Object> create(@PathVariable long courseId, @RequestBody ExamIn in) {
        access.staff(courseId);
        String title = in == null || in.title() == null ? "" : in.title().trim();
        if (title.isEmpty() || title.length() > 120) {
            throw PoolException.bad("Write a title (at most 120 characters).");
        }
        int forms = in.forms() == null ? 2 : in.forms();
        if (forms < 1 || forms > 4) {
            throw PoolException.bad("Choose 1 to 4 forms.");
        }
        long examId = db.sql("""
                insert into exam (course_id, title, forms, seed, created_at)
                values (:course, :t, :f, :s, :now) returning id""")
                .param("course", courseId).param("t", title).param("f", forms)
                .param("s", new SecureRandom().nextLong()).param("now", Timestamp.from(Instant.now(clock)))
                .query(Long.class).single();
        int position = 0;
        for (Map<String, Object> chapter : db.sql(
                "select id, number, title from chapter where course_id = :course order by number")
                .param("course", courseId).query().listOfRows()) {
            long chapterId = ((Number) chapter.get("id")).longValue();
            int wanted = in.counts() == null ? 0 : in.counts().getOrDefault(chapterId, 0);
            if (wanted <= 0) {
                continue;
            }
            List<Long> drawn = draw(examId, chapterId, wanted, !Boolean.FALSE.equals(in.skipFlagged()));
            if (drawn.size() < wanted) {
                throw PoolException.bad("Week " + chapter.get("number") + " has only " + drawn.size()
                        + " usable approved questions, but you asked for " + wanted + ".");
            }
            for (long q : drawn) {
                addItem(examId, ++position, q);
            }
        }
        if (position == 0) {
            throw PoolException.bad("Ask for at least one question.");
        }
        return Map.of("id", examId);
    }

    /** The exam with its questions in chapter order, with the original (unshuffled) options and key. */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long courseId, @PathVariable long id) {
        access.staff(courseId);
        Map<String, Object> exam = new LinkedHashMap<>(exam(courseId, id));
        exam.remove("seed");
        exam.put("items", items(id));
        return exam;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long courseId, @PathVariable long id) {
        access.staff(courseId);
        exam(courseId, id);
        requireNoResults(id);
        db.sql("delete from exam where id = :id").param("id", id).update();
        return Map.of("ok", true);
    }

    /** Replaces one question with another random approved question of the same chapter. */
    @PostMapping("/{id}/items/{itemId}/swap")
    @Transactional
    public Map<String, Object> swap(@PathVariable long courseId, @PathVariable long id, @PathVariable long itemId) {
        access.staff(courseId);
        exam(courseId, id);
        Long chapterId = db.sql("""
                select q.chapter_id from exam_question i join question q on q.id = i.pool_question_id
                where i.id = :i and i.exam_id = :e""").param("i", itemId).param("e", id).query(Long.class)
                .optional().orElseThrow(() -> PoolException.notFound("Item not found."));
        requireNoResults(id);
        List<Long> drawn = draw(id, chapterId, 1, true);
        if (drawn.isEmpty()) {
            throw PoolException.bad("This chapter has no other approved question to use.");
        }
        db.sql("""
                update exam_question set pool_question_id = q.id, text = left(q.stem, 2000), correct_choice = q.correct
                from question q where q.id = :q and exam_question.id = :i""")
                .param("q", drawn.get(0)).param("i", itemId).update();
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/items/{itemId}/remove")
    @Transactional
    public Map<String, Object> remove(@PathVariable long courseId, @PathVariable long id, @PathVariable long itemId) {
        access.staff(courseId);
        exam(courseId, id);
        requireNoResults(id);
        db.sql("delete from exam_question where id = :i and exam_id = :e").param("i", itemId).param("e", id)
                .update();
        // Keep positions 1..n so the unified exam screens stay tidy.
        db.sql("""
                update exam_question eq set position = r.rn
                from (select id, row_number() over (order by position, id) as rn from exam_question
                      where exam_id = :e) r
                where eq.id = r.id""").param("e", id).update();
        return Map.of("ok", true);
    }

    /** One printable form: numbered questions with shuffled options, and the key for this form. */
    @GetMapping("/{id}/forms/{form}")
    public Map<String, Object> form(@PathVariable long courseId, @PathVariable long id, @PathVariable int form) {
        access.staff(courseId);
        Map<String, Object> exam = exam(courseId, id);
        if (form < 1 || form > ((Number) exam.get("forms")).intValue()) {
            throw PoolException.notFound("This exam does not have that form.");
        }
        long seed = exam.get("seed") == null ? id : ((Number) exam.get("seed")).longValue();
        List<Map<String, Object>> items = new ArrayList<>(items(id));
        // Shuffle the questions, then put them back in chapter order: the order inside a chapter is what changes.
        Collections.shuffle(items, new Random(mix(seed, form, 0)));
        items.sort((a, b) -> Integer.compare(((Number) a.get("chapter_number")).intValue(),
                ((Number) b.get("chapter_number")).intValue()));
        List<Map<String, Object>> questions = new ArrayList<>();
        for (Map<String, Object> item : items) {
            List<String> order = new ArrayList<>(LETTERS);
            Collections.shuffle(order, new Random(mix(seed, form, ((Number) item.get("question_id")).longValue())));
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("number", questions.size() + 1);
            q.put("question_id", item.get("question_id"));
            q.put("chapter_number", item.get("chapter_number"));
            q.put("stem", item.get("stem"));
            q.put("options", order.stream().map(l -> item.get("option_" + l.toLowerCase())).toList());
            q.put("correct", LETTERS.get(order.indexOf(String.valueOf(item.get("correct")).trim())));
            questions.add(q);
        }
        return Map.of("title", exam.get("title"), "form", String.valueOf((char) ('A' + form - 1)), "questions",
                questions);
    }

    /**
     * Turns (seed, form, question) into a well-spread seed. Without this, java.util.Random gives almost the same
     * first numbers for seeds that are close together, and most correct answers land on the same letter.
     */
    private static long mix(long seed, long form, long questionId) {
        long z = seed + form * 0x9E3779B97F4A7C15L + questionId * 0xD1B54A32D192ED03L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The exam row; 404 unless it belongs to the course. */
    Map<String, Object> exam(long courseId, long id) {
        return db.sql("select id, title, forms, seed, created_at from exam where id = :id and course_id = :course")
                .param("id", id).param("course", courseId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> PoolException.notFound("Exam not found."));
    }

    List<Map<String, Object>> items(long examId) {
        return db.sql("""
                select i.id as item_id, q.id as question_id, c.number as chapter_number, q.stem, q.option_a,
                    q.option_b, q.option_c, q.option_d, q.correct, q.status, u.full_name as author_name,
                    (select count(*) from question_attempt a where a.question_id = q.id) as attempts,
                    (select count(*) from question_attempt a where a.question_id = q.id and a.correct)
                        as correct_attempts,
                    (select count(*) from question_flag f where f.question_id = q.id and not f.resolved) as open_flags
                from exam_question i
                join question q on q.id = i.pool_question_id
                join chapter c on c.id = q.chapter_id
                join person u on u.id = q.author_id
                where i.exam_id = :e order by c.number, i.position, i.id""").param("e", examId).query().listOfRows();
    }

    private void requireNoResults(long examId) {
        if (results.countByExam_Id(examId) > 0) {
            throw PoolException.conflict("This exam already has results and cannot change.");
        }
    }

    private List<Long> draw(long examId, long chapterId, int count, boolean skipFlagged) {
        return db.sql(DRAW).param("c", chapterId).param("e", examId).param("skip", skipFlagged).param("n", count)
                .query(Long.class).list();
    }

    private void addItem(long examId, int position, long questionId) {
        db.sql("""
                insert into exam_question (exam_id, position, type, text, topic, max_points, choice_count,
                    correct_choice, pool_question_id)
                select :e, :p, 'CHOICE', left(q.stem, 2000), left(c.title, 120), 1, 4, q.correct, q.id
                from question q join chapter c on c.id = q.chapter_id where q.id = :q""")
                .param("e", examId).param("p", position).param("q", questionId).update();
    }
}
