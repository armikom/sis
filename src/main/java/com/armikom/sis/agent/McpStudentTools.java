package com.armikom.sis.agent;

import com.armikom.sis.agent.McpTool.Args;
import com.armikom.sis.agent.McpTool.Audience;
import com.armikom.sis.common.PublicBaseUrl;
import com.armikom.sis.core.course.CourseRepository;
import com.armikom.sis.pool.PoolChapterController;
import com.armikom.sis.pool.PoolException;
import com.armikom.sis.pool.PoolQuestionController;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.armikom.sis.agent.McpTool.chapter;
import static com.armikom.sis.agent.McpTool.integer;
import static com.armikom.sis.agent.McpTool.oneOf;
import static com.armikom.sis.agent.McpTool.schema;
import static com.armikom.sis.agent.McpTool.str;

/**
 * MCP tools for studying with a personal AI agent (ported from question-pool, now per course). The rules of the web
 * site stay the same because the tools call the same controller methods as the REST API: the answer of a question
 * is hidden until the student answers it, and only the first answer counts. Practice answers and the study state
 * are kept apart, so an agent can quiz a student many times without changing the participation report.
 */
@Component
public class McpStudentTools {

    private static final List<String> LETTERS = List.of("A", "B", "C", "D");
    private static final List<String> STUDY_STATUSES = List.of("NOT_STARTED", "STUDYING", "REVIEWING", "DONE");

    private final JdbcClient db;
    private final JsonMapper json;
    private final PoolChapterController chapters;
    private final PoolQuestionController questions;
    private final McpCourses courses;
    private final CourseRepository courseRepo;
    private final PublicBaseUrl baseUrl;

    public McpStudentTools(JdbcClient db, JsonMapper json, PoolChapterController chapters,
                           PoolQuestionController questions, McpCourses courses, CourseRepository courseRepo,
                           PublicBaseUrl baseUrl) {
        this.db = db;
        this.json = json;
        this.chapters = chapters;
        this.questions = questions;
        this.courses = courses;
        this.courseRepo = courseRepo;
        this.baseUrl = baseUrl;
    }

    List<McpTool> tools() {
        return List.of(
                // ---------- for everyone ----------
                McpTool.read(Audience.ALL, "list_courses", "List my courses",
                        "Lists your courses that use the question pool, with your role in each. Pass the code or id "
                                + "of one as 'course' to the other tools when you have more than one.",
                        schema(), c -> listCourses(c)),
                McpTool.read(Audience.ALL, "whoami", "Who am I",
                        "Shows the signed-in person: nickname, courses, and for the course (if known) the role and "
                                + "the rules of the pool (questions per chapter).",
                        schema(), c -> whoami(c)),
                McpTool.read(Audience.ALL, "list_chapters", "List chapters",
                        "Lists the chapters of the course with the number of approved questions and your own numbers.",
                        schema(), c -> chapters.list(c.courseId())),
                McpTool.read(Audience.ALL, "read_chapter", "Read a chapter text",
                        "Returns the reference text of one chapter in Markdown. Use it to teach and to explain answers.",
                        schema("chapter!", chapter(), "language", oneOf("en (default) or tr.", "en", "tr")),
                        c -> readChapter(c)),
                McpTool.read(Audience.ALL, "list_questions", "List questions of a chapter",
                        "Lists the question stems of one chapter with their state and whether you answered them. "
                                + "Waiting questions are listed too, so a student can avoid writing the same question.",
                        schema("chapter!", chapter()),
                        c -> questions.list(chapterId(c.courseId(), c.args().num("chapter")))),
                McpTool.read(Audience.ALL, "get_question", "Get one question",
                        "Returns one question. For a student who has not answered it yet, the correct option, the "
                                + "reasons and the explanation are NOT included: ask the student to choose first.",
                        schema("question_id!", integer("Question id.")), c -> {
                            long id = c.args().num("question_id");
                            c.courseOfQuestion(id);
                            return question(id);
                        }),
                McpTool.read(Audience.ALL, "get_comments", "Read the discussion of a question",
                        "Returns the discussion thread of a question. Open only after the question is answered.",
                        schema("question_id!", integer("Question id.")), c -> {
                            long id = c.args().num("question_id");
                            c.courseOfQuestion(id);
                            return questions.comments(id);
                        }),
                McpTool.write(Audience.ALL, "post_comment", "Post a comment",
                        "Posts a comment in the discussion of a question, in the name of the signed-in person. "
                                + "Other students see it. Only post text that the person wrote or clearly agreed to.",
                        schema("question_id!", integer("Question id."), "body!",
                                str("The comment, at most 2000 characters."),
                                "parent_id", integer("Id of the comment you answer to (optional).")), c -> {
                            long id = c.args().num("question_id");
                            c.courseOfQuestion(id);
                            return questions.comment(id, new PoolQuestionController.CommentIn(c.args().str("body"),
                                    c.args().optNum("parent_id")));
                        }),

                // ---------- progress and study state ----------
                McpTool.read(Audience.STUDENT, "get_progress", "My progress",
                        "Start every study session with this. Per chapter: questions left to answer, first-try "
                                + "results, mistakes to review, your own questions, and the saved study state (status, "
                                + "confidence, notes). Also the last study sessions and a suggestion for what to do next.",
                        schema(), c -> progressReport(c.person().personId(), c.courseId())),
                McpTool.write(Audience.STUDENT, "update_study_state", "Save study state of a chapter",
                        "Saves where the student is in one chapter: status, confidence (1-5) and short notes about "
                                + "weak points. Notes replace the old notes, so send the full text. The course staff "
                                + "can read this.",
                        schema("chapter!", chapter(),
                                "status", oneOf("Where the student is.", "NOT_STARTED", "STUDYING", "REVIEWING", "DONE"),
                                "confidence", integer("How sure the student feels, 1 (not at all) to 5 (very)."),
                                "notes", str("Weak points and what to repeat next time, at most 4000 characters.")),
                        c -> updateStudyState(c)),
                McpTool.write(Audience.STUDENT, "log_study_session", "Write a study diary entry",
                        "Adds a short diary entry at the end of a study session: what was studied, what went well, "
                                + "what to do next time. The next session (maybe with another agent) reads it.",
                        schema("summary!", str("2-6 sentences, at most 1500 characters."), "chapter",
                                integer("Chapter number, if the session was about one chapter.")),
                        c -> logSession(c)),

                // ---------- answering ----------
                McpTool.read(Audience.STUDENT, "next_question", "Next question to answer",
                        "Picks an approved question that the student has not answered yet (not their own). The "
                                + "answer is not included. Show the stem and the four options, then let the student choose.",
                        schema("chapter", integer("Chapter number. Leave empty for any chapter.")),
                        c -> nextQuestion(c)),
                McpTool.write(Audience.STUDENT, "answer_question", "Answer a question (first answer counts)",
                        "Sends the student's OWN choice for a question. Only the first answer counts for the "
                                + "participation report and cannot change. Never choose for the student. The answer "
                                + "shows the correct option, the reasons and the explanation.",
                        schema("question_id!", integer("Question id."),
                                "selected!", oneOf("The option the student chose.", "A", "B", "C", "D")),
                        c -> answer(c)),
                McpTool.read(Audience.STUDENT, "next_review_question", "Next question to practise again",
                        "Picks a question the student already answered, for practice: first the ones answered wrong "
                                + "last time, then the ones not seen for the longest time. The answer is not included.",
                        schema("chapter", integer("Chapter number. Leave empty for any chapter.")),
                        c -> nextReview(c)),
                McpTool.write(Audience.STUDENT, "practice_answer", "Practise a question again",
                        "Checks the student's choice for a question they already answered once. It is saved for the "
                                + "review list only; the first answer does not change.",
                        schema("question_id!", integer("Question id."),
                                "selected!", oneOf("The option the student chose.", "A", "B", "C", "D")),
                        c -> practise(c)),
                McpTool.write(Audience.STUDENT, "flag_question", "Report a problem in a question",
                        "Reports a problem (wrong key, unclear text) in an answered question to the course staff.",
                        schema("question_id!", integer("Question id."),
                                "reason!", str("What is wrong, 5-500 characters.")), c -> {
                            long id = c.args().num("question_id");
                            c.courseOfQuestion(id);
                            return questions.flag(id, new PoolQuestionController.FlagIn(c.args().str("reason")));
                        }),

                // ---------- writing questions ----------
                McpTool.read(Audience.STUDENT, "my_questions", "My questions",
                        "Lists the questions the student wrote in the course, with their state, the AI check state "
                                + "and the staff note. Use get_question to see the AI check of one question.",
                        schema(), c -> questions.mine(c.courseId())),
                McpTool.write(Audience.STUDENT, "submit_question", "Submit a new question",
                        "Sends a new multiple-choice question that THE STUDENT wrote (CTFL style: one stem, four "
                                + "options, one correct answer, a reason for every option, a reference to the chapter "
                                + "text). You may give feedback on the student's draft, but do not write it for them. "
                                + "Show the final text and ask before you send it.",
                        questionSchema(true), c -> questions.create(
                                questionIn(c.args(), null, chapterId(c.courseId(), c.args().num("chapter"))))),
                McpTool.write(Audience.STUDENT, "revise_question", "Change my question",
                        "Changes a question of the student while it is still waiting or sent back. Send only the "
                                + "fields that change. The question goes back to waiting and is checked again.",
                        questionSchema(false), c -> revise(c)));
    }

    // ---------- implementations ----------

    private List<Map<String, Object>> listCourses(McpCall c) {
        return courses.mine(c.person()).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("code", r.code());
            m.put("name", r.name());
            m.put("term", r.term());
            m.put("language", r.language());
            m.put("role", r.role());
            m.put("staff", r.staff());
            return m;
        }).toList();
    }

    private Map<String, Object> whoami(McpCall c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nickname", c.person().nickname());
        boolean staffSomewhere = courses.hasStaffCourse(c.person());
        if (staffSomewhere) {
            // Students are known to their agent by nickname only, so the real name does not go to the AI company.
            out.put("full_name", c.person().fullName());
        }
        out.put("courses", listCourses(c));
        try {
            McpCourses.Ref ref = courses.resolve(c.person(), c.args().optStr("course"));
            courseRepo.findById(ref.id()).ifPresent(course -> {
                out.put("course", ref.code());
                out.put("role", ref.role());
                out.put("staff", ref.staff());
                out.put("questions_per_chapter", course.getQuestionsPerChapter());
            });
        } catch (IllegalArgumentException e) {
            out.put("course_note", e.getMessage());
        }
        out.put("site", baseUrl.current());
        return out;
    }

    private Map<String, Object> readChapter(McpCall c) {
        Map<String, Object> ch = chapters.get(chapterId(c.courseId(), c.args().num("chapter")));
        String lang = "tr".equalsIgnoreCase(c.args().optStr("language")) ? "tr" : "en";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chapter", ch.get("number"));
        out.put("title", ch.get("title"));
        out.put("language", lang);
        out.put("text", ch.get("doc_" + lang));
        return out;
    }

    Map<String, Object> question(long id) {
        Map<String, Object> q = questions.get(id);
        Object review = q.get("ai_review");
        if (review instanceof String s && !s.isBlank()) {
            q.put("ai_review", json.readTree(s));
        }
        if (Boolean.FALSE.equals(q.get("revealed"))) {
            q.put("note_for_agent", "Not answered yet. Show the stem and options only, let the student choose, "
                    + "then call answer_question with the student's choice.");
        }
        return q;
    }

    private Map<String, Object> nextQuestion(McpCall c) {
        long courseId = c.courseId();
        long me = c.person().personId();
        Integer chapterNumber = c.args().optInt("chapter");
        Long chapterId = chapterNumber == null ? null : chapterId(courseId, chapterNumber);
        Optional<Long> id = db.sql("""
                select q.id from question q join chapter c on c.id = q.chapter_id
                where c.course_id = :course and q.status = 'APPROVED' and q.author_id <> :u
                  and (cast(:c as bigint) is null or q.chapter_id = :c)
                  and not exists (select 1 from question_attempt a where a.question_id = q.id and a.person_id = :u)
                order by c.number, random() limit 1""")
                .param("course", courseId).param("u", me).param("c", chapterId).query(Long.class).optional();
        if (id.isEmpty()) {
            return Map.of("done", true, "message", chapterNumber == null
                    ? "No new approved questions are left. Use next_review_question to practise."
                    : "No new approved questions are left in chapter " + chapterNumber
                            + ". Use next_review_question to practise, or try another chapter.");
        }
        Map<String, Object> q = question(id.get());
        q.put("left_after_this", db.sql("""
                select count(*) - 1 from question q join chapter c on c.id = q.chapter_id
                where c.course_id = :course and q.status = 'APPROVED' and q.author_id <> :u
                  and (cast(:c as bigint) is null or q.chapter_id = :c)
                  and not exists (select 1 from question_attempt a where a.question_id = q.id and a.person_id = :u)""")
                .param("course", courseId).param("u", me).param("c", chapterId).query(Integer.class).single());
        return q;
    }

    private Map<String, Object> answer(McpCall c) {
        long id = c.args().num("question_id");
        c.courseOfQuestion(id);
        String selected = letter(c.args().str("selected"));
        db.sql("select selected from question_attempt where question_id = :q and person_id = :u").param("q", id)
                .param("u", c.person().personId()).query(String.class).optional().ifPresent(first -> {
                    throw new IllegalArgumentException("The student already answered this question (first answer: "
                            + first + "). Use practice_answer to practise it again.");
                });
        Map<String, Object> q = new LinkedHashMap<>(
                questions.attempt(id, new PoolQuestionController.AttemptIn(selected)));
        q.put("answer_was_correct", selected.equals(q.get("correct")));
        q.put("note_for_agent", "Explain with the reasons and the chapter text. If the answer was wrong, add the "
                + "weak point to the notes with update_study_state.");
        return q;
    }

    private Map<String, Object> nextReview(McpCall c) {
        long courseId = c.courseId();
        long me = c.person().personId();
        Integer chapterNumber = c.args().optInt("chapter");
        Long chapterId = chapterNumber == null ? null : chapterId(courseId, chapterNumber);
        List<Map<String, Object>> queue = db.sql("""
                select s.*, coalesce(s.last_practice_correct, s.first_correct) as last_correct,
                    coalesce(s.last_practice_at, s.first_at) as last_seen
                from (
                    select q.id, c.number as chapter, q.stem, q.option_a, q.option_b, q.option_c, q.option_d,
                        a.correct as first_correct, a.created_at as first_at,
                        (select p.correct from practice_answer p where p.person_id = :u and p.question_id = q.id
                            order by p.id desc limit 1) as last_practice_correct,
                        (select max(p.created_at) from practice_answer p
                            where p.person_id = :u and p.question_id = q.id) as last_practice_at,
                        (select count(*) from practice_answer p
                            where p.person_id = :u and p.question_id = q.id) as times_practised
                    from question_attempt a join question q on q.id = a.question_id
                    join chapter c on c.id = q.chapter_id
                    where a.person_id = :u and c.course_id = :course and q.status = 'APPROVED'
                      and (cast(:c as bigint) is null or q.chapter_id = :c)
                ) s
                order by coalesce(s.last_practice_correct, s.first_correct), coalesce(s.last_practice_at, s.first_at)
                """).param("u", me).param("course", courseId).param("c", chapterId).query().listOfRows();
        if (queue.isEmpty()) {
            return Map.of("done", true, "message",
                    "Nothing to practise yet. Answer some questions first with next_question.");
        }
        Map<String, Object> first = queue.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", first.get("id"));
        out.put("chapter", first.get("chapter"));
        out.put("stem", first.get("stem"));
        for (String l : LETTERS) {
            out.put("option_" + l.toLowerCase(), first.get("option_" + l.toLowerCase()));
        }
        out.put("why_now", Boolean.FALSE.equals(first.get("last_correct")) ? "answered wrong last time"
                : "not seen for the longest time");
        out.put("times_practised", first.get("times_practised"));
        out.put("wrong_in_queue", queue.stream().filter(r -> Boolean.FALSE.equals(r.get("last_correct"))).count());
        out.put("note_for_agent", "Do not show the answer. Let the student choose, then call practice_answer.");
        return out;
    }

    private Map<String, Object> practise(McpCall c) {
        long id = c.args().num("question_id");
        c.courseOfQuestion(id);
        long me = c.person().personId();
        String selected = letter(c.args().str("selected"));
        if (db.sql("select count(*) from question_attempt where question_id = :q and person_id = :u").param("q", id)
                .param("u", me).query(Integer.class).single() == 0) {
            throw new IllegalArgumentException(
                    "The student has not answered this question yet. Use answer_question for the first answer.");
        }
        Map<String, Object> q = questions.get(id);
        boolean correct = selected.equals(q.get("correct"));
        db.sql("insert into practice_answer (person_id, question_id, selected, correct) values (:u, :q, :s, :ok)")
                .param("u", me).param("q", id).param("s", selected).param("ok", correct).update();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question_id", id);
        out.put("selected", selected);
        out.put("answer_was_correct", correct);
        out.put("correct", q.get("correct"));
        out.put("reason_for_selected", q.get("rationale_" + selected.toLowerCase()));
        out.put("reason_for_correct", q.get("rationale_" + q.get("correct").toString().toLowerCase()));
        out.put("explanation", q.get("explanation"));
        out.put("reference", q.get("reference"));
        out.put("first_answer", q.get("my_selected"));
        return out;
    }

    private Map<String, Object> updateStudyState(McpCall c) {
        Args a = c.args();
        long chapterId = chapterId(c.courseId(), a.num("chapter"));
        long me = c.person().personId();
        String status = a.optStr("status");
        if (status != null) {
            status = status.trim().toUpperCase();
            if (!STUDY_STATUSES.contains(status)) {
                throw new IllegalArgumentException("status must be one of " + STUDY_STATUSES + ".");
            }
        }
        Integer confidence = a.optInt("confidence");
        if (confidence != null && (confidence < 1 || confidence > 5)) {
            throw new IllegalArgumentException("confidence must be 1 to 5.");
        }
        String notes = a.optStr("notes");
        if (notes != null && notes.length() > 4000) {
            throw new IllegalArgumentException("notes are too long (at most 4000 characters).");
        }
        db.sql("""
                insert into study_state (person_id, chapter_id, status, confidence, notes)
                values (:u, :c, coalesce(cast(:s as text), 'STUDYING'), cast(:conf as integer),
                        coalesce(cast(:n as text), ''))
                on conflict (person_id, chapter_id) do update set
                    status = coalesce(cast(:s as text), study_state.status),
                    confidence = coalesce(cast(:conf as integer), study_state.confidence),
                    notes = coalesce(cast(:n as text), study_state.notes), updated_at = now()""")
                .param("u", me).param("c", chapterId).param("s", status).param("conf", confidence)
                .param("n", notes == null ? null : notes.trim()).update();
        return db.sql("""
                select c.number as chapter, s.status, s.confidence, s.notes, s.updated_at
                from study_state s join chapter c on c.id = s.chapter_id
                where s.person_id = :u and s.chapter_id = :c""")
                .param("u", me).param("c", chapterId).query().singleRow();
    }

    private Map<String, Object> logSession(McpCall c) {
        String summary = c.args().str("summary").trim();
        if (summary.length() > 1500) {
            throw new IllegalArgumentException("summary is too long (at most 1500 characters).");
        }
        long courseId = c.courseId();
        Integer chapterNumber = c.args().optInt("chapter");
        long id = db.sql("insert into study_log (person_id, chapter_id, summary) values (:u, :c, :s) returning id")
                .param("u", c.person().personId())
                .param("c", chapterNumber == null ? null : chapterId(courseId, chapterNumber))
                .param("s", summary).query(Long.class).single();
        return Map.of("ok", true, "id", id);
    }

    private Map<String, Object> revise(McpCall c) {
        long id = c.args().num("question_id");
        c.courseOfQuestion(id);
        Map<String, Object> current = questions.get(id);
        if (!Boolean.TRUE.equals(current.get("mine"))) {
            throw PoolException.questionNotFound();
        }
        return questions.update(id, questionIn(c.args(), current, null));
    }

    // ---------- progress (also used by the teacher tools) ----------

    /** Per chapter numbers of one student in one course, together with the saved study state. */
    List<Map<String, Object>> progress(long personId, long courseId) {
        int perChapter = courseRepo.findById(courseId).map(x -> x.getQuestionsPerChapter()).orElse(0);
        List<Map<String, Object>> rows = db.sql("""
                select c.number as chapter, c.title, c.open as open_for_new_questions,
                    (select count(*) from question q
                        where q.chapter_id = c.id and q.status = 'APPROVED' and q.author_id <> :u) as can_answer,
                    (select count(*) from question_attempt a join question q on q.id = a.question_id
                        where q.chapter_id = c.id and a.person_id = :u and q.status = 'APPROVED') as answered,
                    (select count(*) from question_attempt a join question q on q.id = a.question_id
                        where q.chapter_id = c.id and a.person_id = :u and q.status = 'APPROVED' and a.correct)
                        as first_try_correct,
                    (select count(*) from question_attempt a join question q on q.id = a.question_id
                        where q.chapter_id = c.id and a.person_id = :u and q.status = 'APPROVED'
                          and not coalesce((select p.correct from practice_answer p
                              where p.person_id = :u and p.question_id = a.question_id order by p.id desc limit 1),
                              a.correct)) as to_review,
                    (select count(*) from practice_answer p join question q on q.id = p.question_id
                        where q.chapter_id = c.id and p.person_id = :u) as practice_answers,
                    (select count(*) from question q
                        where q.chapter_id = c.id and q.author_id = :u and q.status <> 'REJECTED') as my_questions,
                    (select count(*) from question q
                        where q.chapter_id = c.id and q.author_id = :u and q.status = 'APPROVED') as my_approved,
                    coalesce(s.status, 'NOT_STARTED') as study_status, s.confidence, coalesce(s.notes, '') as notes,
                    s.updated_at as study_updated_at
                from chapter c left join study_state s on s.chapter_id = c.id and s.person_id = :u
                where c.course_id = :course
                order by c.number""").param("u", personId).param("course", courseId).query().listOfRows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            long canAnswer = ((Number) r.get("can_answer")).longValue();
            long answered = ((Number) r.get("answered")).longValue();
            m.put("not_answered", Math.max(0, canAnswer - answered));
            m.put("questions_to_write", Math.max(0, perChapter - ((Number) r.get("my_questions")).longValue()));
            out.add(m);
        }
        return out;
    }

    /**
     * Diary entries of one student for one course: entries about a chapter of the course, plus (only when
     * {@code includeGeneral}) entries without a chapter, which belong to no course.
     */
    List<Map<String, Object>> studyLog(long personId, long courseId, int limit, boolean includeGeneral) {
        return db.sql("""
                select l.id, c.number as chapter, l.summary, l.created_at
                from study_log l left join chapter c on c.id = l.chapter_id
                where l.person_id = :u and (c.course_id = :course or (:general and l.chapter_id is null))
                order by l.id desc limit :n""").param("u", personId).param("course", courseId)
                .param("general", includeGeneral).param("n", limit).query().listOfRows();
    }

    private Map<String, Object> progressReport(long personId, long courseId) {
        List<Map<String, Object>> chapterRows = progress(personId, courseId);
        Map<String, Object> totals = new LinkedHashMap<>();
        for (String key : List.of("can_answer", "answered", "first_try_correct", "not_answered", "to_review",
                "practice_answers", "my_questions", "my_approved", "questions_to_write")) {
            totals.put(key, chapterRows.stream().mapToLong(c -> ((Number) c.get(key)).longValue()).sum());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", totals);
        out.put("chapters", chapterRows);
        out.put("recent_sessions", studyLog(personId, courseId, 5, true));
        out.put("suggestion", suggestion(chapterRows));
        return out;
    }

    private static String suggestion(List<Map<String, Object>> chapters) {
        for (Map<String, Object> c : chapters) {
            if (((Number) c.get("to_review")).longValue() > 0) {
                return "Chapter " + c.get("chapter") + " has " + c.get("to_review")
                        + " questions answered wrong. Practise them with next_review_question.";
            }
        }
        for (Map<String, Object> c : chapters) {
            if (!"DONE".equals(c.get("study_status")) && ((Number) c.get("not_answered")).longValue() > 0) {
                return "Study chapter " + c.get("chapter") + " (read_chapter), then answer its "
                        + c.get("not_answered") + " new questions with next_question.";
            }
        }
        for (Map<String, Object> c : chapters) {
            if (Boolean.TRUE.equals(c.get("open_for_new_questions"))
                    && ((Number) c.get("questions_to_write")).longValue() > 0) {
                return "Chapter " + c.get("chapter") + " still needs " + c.get("questions_to_write")
                        + " questions from you. Help the student write them (the student writes, you give feedback).";
            }
        }
        return "Everything is answered. Keep practising with next_review_question before the exam.";
    }

    // ---------- helpers ----------

    long chapterId(long courseId, long number) {
        return db.sql("select id from chapter where course_id = :course and number = :n")
                .param("course", courseId).param("n", number).query(Long.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("There is no chapter " + number
                        + " in this course. Use list_chapters."));
    }

    private static String letter(String raw) {
        String l = raw.trim().toUpperCase();
        if (!LETTERS.contains(l)) {
            throw new IllegalArgumentException("selected must be A, B, C or D.");
        }
        return l;
    }

    static Map<String, Object> questionSchema(boolean create) {
        List<Object> pairs = new ArrayList<>();
        String r = create ? "!" : "";
        if (create) {
            pairs.addAll(List.of("chapter!", chapter()));
        } else {
            pairs.addAll(List.of("question_id!", integer("Question id.")));
        }
        pairs.addAll(List.of("stem" + r, str("The question text, 15-800 characters."), "option_a" + r,
                str("Option A."), "option_b" + r, str("Option B."), "option_c" + r, str("Option C."),
                "option_d" + r, str("Option D."), "correct" + r,
                oneOf("The correct option.", "A", "B", "C", "D"), "rationale_a" + r,
                str("Why A is right or wrong, 10-600 characters."), "rationale_b" + r, str("Why B is right or wrong."),
                "rationale_c" + r, str("Why C is right or wrong."), "rationale_d" + r, str("Why D is right or wrong."),
                "reference" + r, str("Where in the chapter text the answer is, for example 'Chapter 6, 3.2 Reviews'.")));
        return schema(pairs.toArray());
    }

    /** Builds the question fields from the arguments; missing fields come from {@code current} (for changes). */
    static PoolQuestionController.QuestionIn questionIn(Args a, Map<String, Object> current, Long chapterId) {
        Function<String, String> f = name -> a.has(name) ? a.optStr(name)
                : current == null ? null : (String) current.get(name);
        return new PoolQuestionController.QuestionIn(chapterId, f.apply("stem"),
                f.apply("option_a"), f.apply("option_b"), f.apply("option_c"), f.apply("option_d"),
                f.apply("correct"), f.apply("rationale_a"), f.apply("rationale_b"), f.apply("rationale_c"),
                f.apply("rationale_d"), f.apply("reference"));
    }
}
