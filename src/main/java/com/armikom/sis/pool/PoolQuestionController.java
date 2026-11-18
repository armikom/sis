package com.armikom.sis.pool;

import com.armikom.sis.core.course.Course;
import org.springframework.http.HttpStatus;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What students do with questions: write, answer, discuss (ported QuestionController). The main rule is "attempt
 * first": the correct answer, the reasons, the explanation and the thread stay hidden until the student has answered
 * the question. The course is derived from the chapter / question; staff of that course are "privileged".
 */
@RestController
@RequestMapping("/api/pool")
public class PoolQuestionController {

    static final String SELECT_FULL = """
            select q.id, q.chapter_id, c.course_id, q.author_id, q.stem, q.option_a, q.option_b, q.option_c,
                q.option_d, q.correct, q.rationale_a, q.rationale_b, q.rationale_c, q.rationale_d, q.reference,
                q.status, q.ai_status, q.ai_review::text as ai_review, q.instructor_note, q.explanation, q.created_at,
                u.nickname as author
            from question q join person u on u.id = q.author_id join chapter c on c.id = q.chapter_id
            """;
    private static final List<String> HIDDEN_BEFORE_ATTEMPT = List.of("correct", "rationale_a", "rationale_b",
            "rationale_c", "rationale_d", "reference", "explanation");
    private static final List<String> AUTHOR_ONLY = List.of("ai_status", "ai_review", "instructor_note");
    static final List<String> LETTERS = List.of("A", "B", "C", "D");

    private final JdbcClient db;
    private final PoolAccess access;
    private final AiService ai;
    private final Topics topics;

    public PoolQuestionController(JdbcClient db, PoolAccess access, AiService ai, Topics topics) {
        this.db = db;
        this.access = access;
        this.ai = ai;
        this.topics = topics;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record QuestionIn(Long chapterId, String stem, String optionA, String optionB, String optionC,
                             String optionD, String correct, String rationaleA, String rationaleB, String rationaleC,
                             String rationaleD, String reference, List<Long> topicIds) {

        /** Returns a trimmed copy, or stops the request with a message the student can act on. */
        public QuestionIn checked() {
            QuestionIn q = new QuestionIn(chapterId, text("The question text", stem, 15, 800),
                    text("Option A", optionA, 1, 300), text("Option B", optionB, 1, 300),
                    text("Option C", optionC, 1, 300), text("Option D", optionD, 1, 300),
                    correct == null ? "" : correct.trim().toUpperCase(Locale.ROOT), text("The reason for A", rationaleA, 10, 600),
                    text("The reason for B", rationaleB, 10, 600), text("The reason for C", rationaleC, 10, 600),
                    text("The reason for D", rationaleD, 10, 600), text("The reference", reference, 3, 200),
                    topicIds);
            if (!LETTERS.contains(q.correct)) {
                throw PoolException.bad("Choose the correct option (A, B, C or D).");
            }
            if (List.of(q.optionA, q.optionB, q.optionC, q.optionD).stream().map(o -> o.toLowerCase(Locale.ROOT)).distinct()
                    .count() < 4) {
                throw PoolException.bad("The four options must be different.");
            }
            return q;
        }

        private static String text(String name, String value, int min, int max) {
            String v = value == null ? "" : value.trim();
            if (v.length() < min) {
                throw PoolException.bad(name + " is too short (at least " + min + " characters).");
            }
            if (v.length() > max) {
                throw PoolException.bad(name + " is too long (at most " + max + " characters).");
            }
            return v;
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AttemptIn(String selected) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CommentIn(String body, Long parentId) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FlagIn(String reason) {}

    // ---------- reading ----------

    /** Question stems of a chapter. Stems of waiting questions are listed too, so students can avoid duplicates. */
    @GetMapping("/chapters/{chapterId}/questions")
    public List<Map<String, Object>> list(@PathVariable long chapterId,
                                          @RequestParam(name = "topic_id", required = false) Long topicId) {
        access.member(access.courseOfChapter(chapterId));
        return topics.addTo(db.sql("""
                select q.id, q.stem, q.status, (q.author_id = :u) as mine, u.nickname as author,
                    (a.id is not null) as attempted, a.correct as my_correct,
                    (select count(*) from question_comment c where c.question_id = q.id and not c.deleted) as comments
                from question q
                join person u on u.id = q.author_id
                left join question_attempt a on a.question_id = q.id and a.person_id = :u
                where q.chapter_id = :c and (q.status in ('APPROVED', 'PENDING', 'NEEDS_REVISION') or q.author_id = :u)
                  and (cast(:t as bigint) is null
                       or exists (select 1 from question_topic qt where qt.question_id = q.id and qt.topic_id = :t))
                order by q.id""").param("u", access.me().personId()).param("c", chapterId).param("t", topicId)
                .query().listOfRows(), "id");
    }

    @GetMapping("/courses/{courseId}/questions/mine")
    public List<Map<String, Object>> mine(@PathVariable long courseId) {
        access.member(courseId);
        return topics.addTo(db.sql("""
                select q.id, q.stem, q.status, q.ai_status, q.instructor_note, q.created_at,
                    c.id as chapter_id, c.number as chapter_number, c.title as chapter_title,
                    (select count(*) from question_attempt a where a.question_id = q.id) as attempts,
                    (select count(*) from question_comment m where m.question_id = q.id and not m.deleted) as comments
                from question q join chapter c on c.id = q.chapter_id
                where q.author_id = :u and c.course_id = :course order by c.number, q.id""")
                .param("u", access.me().personId()).param("course", courseId).query().listOfRows(), "id");
    }

    @GetMapping("/questions/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> q = load(id);
        long courseId = ((Number) q.get("course_id")).longValue();
        access.member(courseId);
        long me = access.me().personId();
        boolean mine = isAuthor(q, me);
        boolean privileged = mine || access.isStaff(courseId);
        if (!privileged && !"APPROVED".equals(q.get("status"))) {
            throw PoolException.questionNotFound();
        }
        Optional<String> selected = db.sql(
                        "select selected from question_attempt where question_id = :q and person_id = :u")
                .param("q", id).param("u", me).query(String.class).optional();
        boolean revealed = privileged || selected.isPresent();
        q.remove("author_id");
        q.put("mine", mine);
        q.put("revealed", revealed);
        q.put("my_selected", selected.orElse(null));
        q.put("topics", topics.questionShapes(id));
        if (!privileged) {
            AUTHOR_ONLY.forEach(q::remove);
        }
        if (!revealed) {
            HIDDEN_BEFORE_ATTEMPT.forEach(q::remove);
        }
        return q;
    }

    // ---------- answering and discussing ----------

    @PostMapping("/questions/{id}/attempt")
    public Map<String, Object> attempt(@PathVariable long id, @RequestBody AttemptIn in) {
        Map<String, Object> q = load(id);
        long courseId = ((Number) q.get("course_id")).longValue();
        access.member(courseId);
        long me = access.me().personId();
        if (!"APPROVED".equals(q.get("status"))) {
            throw PoolException.questionNotFound();
        }
        if (isAuthor(q, me) || access.isStaff(courseId)) {
            throw PoolException.bad("You cannot answer this question.");
        }
        String selected = in == null || in.selected() == null ? "" : in.selected().trim().toUpperCase(Locale.ROOT);
        if (!LETTERS.contains(selected)) {
            throw PoolException.bad("Choose one option.");
        }
        // Only the first answer counts: a second request does not change it.
        db.sql("""
                insert into question_attempt (question_id, person_id, selected, correct) values (:q, :u, :s, :ok)
                on conflict (question_id, person_id) do nothing""").param("q", id).param("u", me)
                .param("s", selected).param("ok", selected.equals(q.get("correct"))).update();
        return get(id);
    }

    @GetMapping("/questions/{id}/comments")
    public List<Map<String, Object>> comments(@PathVariable long id) {
        long courseId = requireRevealed(id);
        return db.sql("""
                select c.id, c.parent_id, c.body, c.is_ai, c.created_at, (c.author_id = :u) as mine,
                    case when c.is_ai then 'AI helper'
                         when exists (select 1 from enrollment e where e.course_id = :course
                             and e.person_id = c.author_id and e.role <> 'STUDENT') then 'Instructor'
                         else u.nickname end as author,
                    (c.author_id = q.author_id) as wrote_question,
                    case when :staff then u.full_name end as real_name
                from question_comment c
                join question q on q.id = c.question_id
                left join person u on u.id = c.author_id
                where c.question_id = :q and not c.deleted order by c.id""").param("u", access.me().personId())
                .param("course", courseId).param("staff", access.isStaff(courseId)).param("q", id)
                .query().listOfRows();
    }

    @PostMapping("/questions/{id}/comments")
    public Map<String, Object> comment(@PathVariable long id, @RequestBody CommentIn in) {
        requireRevealed(id);
        return Map.of("id", addComment(id, access.me().personId(), false, in == null ? null : in.parentId(),
                commentBody(in == null ? null : in.body())));
    }

    /** Posts the student's message and then the AI helper's answer as a reply to it. */
    @PostMapping("/questions/{id}/ask-ai")
    public Map<String, Object> askAi(@PathVariable long id, @RequestBody CommentIn in) {
        long courseId = requireRevealed(id);
        Course course = access.member(courseId);
        long me = access.me().personId();
        if (!ai.enabled()) {
            throw PoolException.bad("The AI helper is switched off.");
        }
        String body = commentBody(in == null ? null : in.body());
        int today = db.sql("""
                select count(*) from question_comment a
                join question_comment p on p.id = a.parent_id
                join question q on q.id = a.question_id join chapter ch on ch.id = q.chapter_id
                where a.is_ai and p.author_id = :u and ch.course_id = :course
                  and a.created_at > now() - interval '1 day'""")
                .param("u", me).param("course", courseId).query(Integer.class).single();
        if (!access.isStaff(courseId) && today >= course.getAskAiPerDay()) {
            throw new PoolException(HttpStatus.TOO_MANY_REQUESTS,
                    "You can ask the AI helper " + course.getAskAiPerDay() + " times a day. Try again tomorrow.");
        }
        String answer;
        try {
            answer = ai.answerInThread(id, body);
        } catch (Exception e) {
            throw new PoolException(HttpStatus.BAD_GATEWAY, "The AI helper did not answer. Try again in a minute.");
        }
        long mine = addComment(id, me, false, in.parentId(), body);
        addComment(id, null, true, mine, answer);
        return Map.of("id", mine);
    }

    @PostMapping("/questions/{id}/flag")
    public Map<String, Object> flag(@PathVariable long id, @RequestBody FlagIn in) {
        requireRevealed(id);
        String reason = in == null || in.reason() == null ? "" : in.reason().trim();
        if (reason.length() < 5 || reason.length() > 500) {
            throw PoolException.bad("Write what is wrong (5 to 500 characters).");
        }
        db.sql("insert into question_flag (question_id, person_id, reason) values (:q, :u, :r)").param("q", id)
                .param("u", access.me().personId()).param("r", reason).update();
        return Map.of("ok", true);
    }

    // ---------- writing ----------

    @PostMapping("/questions")
    public Map<String, Object> create(@RequestBody QuestionIn raw) {
        if (raw == null) {
            throw PoolException.bad("Choose a chapter.");
        }
        QuestionIn in = raw.checked();
        if (in.chapterId() == null) {
            throw PoolException.bad("Choose a chapter.");
        }
        Map<String, Object> chapter = db.sql("select course_id, open from chapter where id = :c")
                .param("c", in.chapterId()).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> PoolException.bad("Chapter not found."));
        long courseId = ((Number) chapter.get("course_id")).longValue();
        Course course = access.member(courseId);
        boolean staff = access.isStaff(courseId);
        long me = access.me().personId();
        if (!Boolean.TRUE.equals(chapter.get("open")) && !staff) {
            throw PoolException.bad("This chapter is closed for new questions.");
        }
        int count = db.sql("""
                select count(*) from question where chapter_id = :c and author_id = :u and status <> 'REJECTED'""")
                .param("c", in.chapterId()).param("u", me).query(Integer.class).single();
        if (!staff && count >= course.getQuestionsPerChapter()) {
            throw PoolException.bad("You already have " + course.getQuestionsPerChapter()
                    + " questions in this chapter.");
        }
        List<Long> topicIds = in.topicIds() == null ? null : topics.requireInCourse(courseId, in.topicIds());
        long id = db.sql("""
                insert into question (chapter_id, author_id, stem, option_a, option_b, option_c, option_d, correct,
                    rationale_a, rationale_b, rationale_c, rationale_d, reference)
                values (:chapter, :author, :stem, :a, :b, :c, :d, :correct, :ra, :rb, :rc, :rd, :reference)
                returning id""").param("chapter", in.chapterId()).param("author", me)
                .params(fields(in)).query(Long.class).single();
        if (topicIds != null) topics.setQuestionTopics(id, topicIds);
        ai.checkAsync(id);
        return Map.of("id", id);
    }

    /** The author can change a question until the instructor approves or rejects it. */
    @PutMapping("/questions/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody QuestionIn raw) {
        Map<String, Object> q = load(id);
        long courseId = ((Number) q.get("course_id")).longValue();
        access.member(courseId);
        if (!isAuthor(q, access.me().personId())) {
            throw PoolException.questionNotFound();
        }
        QuestionIn in = (raw == null ? new QuestionIn(null, null, null, null, null, null, null, null, null, null,
                null, null, null) : raw).checked();
        if (!List.of("PENDING", "NEEDS_REVISION").contains(q.get("status"))) {
            throw PoolException.bad("This question is already " + q.get("status").toString().toLowerCase(Locale.ROOT)
                    + " and cannot change.");
        }
        List<Long> topicIds = in.topicIds() == null ? null : topics.requireInCourse(courseId, in.topicIds());
        db.sql(UPDATE_FIELDS + ", status = 'PENDING' where id = :id").param("id", id).params(fields(in)).update();
        if (topicIds != null) topics.setQuestionTopics(id, topicIds);
        ai.checkAsync(id);
        return Map.of("id", id);
    }

    @DeleteMapping("/questions/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        Map<String, Object> q = load(id);
        access.member(((Number) q.get("course_id")).longValue());
        if (!isAuthor(q, access.me().personId())) {
            throw PoolException.questionNotFound();
        }
        if ("APPROVED".equals(q.get("status"))) {
            throw PoolException.bad("An approved question cannot be deleted.");
        }
        db.sql("delete from question where id = :id").param("id", id).update();
        return Map.of("ok", true);
    }

    // ---------- shared helpers (also used by the staff screens and the MCP tools) ----------

    static final String UPDATE_FIELDS = """
            update question set stem = :stem, option_a = :a, option_b = :b, option_c = :c, option_d = :d,
                correct = :correct, rationale_a = :ra, rationale_b = :rb, rationale_c = :rc, rationale_d = :rd,
                reference = :reference, updated_at = now()""";

    static Map<String, Object> fields(QuestionIn in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stem", in.stem());
        m.put("a", in.optionA());
        m.put("b", in.optionB());
        m.put("c", in.optionC());
        m.put("d", in.optionD());
        m.put("correct", in.correct());
        m.put("ra", in.rationaleA());
        m.put("rb", in.rationaleB());
        m.put("rc", in.rationaleC());
        m.put("rd", in.rationaleD());
        m.put("reference", in.reference());
        return m;
    }

    private Map<String, Object> load(long id) {
        return db.sql(SELECT_FULL + " where q.id = :id").param("id", id).query().listOfRows().stream().findFirst()
                .map(LinkedHashMap::new).orElseThrow(PoolException::questionNotFound);
    }

    private static boolean isAuthor(Map<String, Object> q, long personId) {
        return ((Number) q.get("author_id")).longValue() == personId;
    }

    /**
     * The thread is open to the author, staff, and students who answered an approved question.
     * Returns the course id of the question.
     */
    private long requireRevealed(long id) {
        Map<String, Object> q = get(id);
        if (!Boolean.TRUE.equals(q.get("revealed"))) {
            throw PoolException.forbidden("Answer the question first.");
        }
        return ((Number) q.get("course_id")).longValue();
    }

    private long addComment(long questionId, Long authorId, boolean isAi, Long parentId, String body) {
        if (parentId != null) {
            boolean sameQuestion = db.sql("select count(*) from question_comment where id = :p and question_id = :q")
                    .param("p", parentId).param("q", questionId).query(Integer.class).single() == 1;
            if (!sameQuestion) {
                throw PoolException.bad("The comment you answer to does not exist.");
            }
        }
        return db.sql("""
                insert into question_comment (question_id, author_id, is_ai, parent_id, body)
                values (:q, :a, :ai, :p, :b) returning id""").param("q", questionId).param("a", authorId)
                .param("ai", isAi).param("p", parentId).param("b", body).query(Long.class).single();
    }

    private static String commentBody(String raw) {
        String body = raw == null ? "" : raw.trim();
        if (body.isEmpty() || body.length() > 2000) {
            throw PoolException.bad("Write a comment (at most 2000 characters).");
        }
        return body;
    }
}
