package com.armikom.sis.agent;

import com.armikom.sis.agent.McpTool.Args;
import com.armikom.sis.agent.McpTool.Audience;
import com.armikom.sis.core.course.RosterService;
import com.armikom.sis.pool.PoolExamController;
import com.armikom.sis.pool.PoolStaffController;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.armikom.sis.agent.McpTool.bool;
import static com.armikom.sis.agent.McpTool.chapter;
import static com.armikom.sis.agent.McpTool.integer;
import static com.armikom.sis.agent.McpTool.oneOf;
import static com.armikom.sis.agent.McpTool.schema;
import static com.armikom.sis.agent.McpTool.str;

/**
 * MCP tools for course staff: participation, question review, problem reports, chapters and exams (ported from
 * question-pool, now per course). They call the same controller methods as the staff screens.
 */
@Component
public class McpTeacherTools {

    private static final String COURSE_OF_FLAG = """
            select c.course_id from question_flag f join question q on q.id = f.question_id
            join chapter c on c.id = q.chapter_id where f.id = :id""";
    private static final String COURSE_OF_COMMENT = """
            select c.course_id from question_comment m join question q on q.id = m.question_id
            join chapter c on c.id = q.chapter_id where m.id = :id""";
    private static final String COURSE_OF_EXAM = "select course_id from exam where id = :id";

    private final JdbcClient db;
    private final JsonMapper json;
    private final PoolStaffController staff;
    private final PoolExamController exams;
    private final McpStudentTools student;
    private final RosterService roster;

    public McpTeacherTools(JdbcClient db, JsonMapper json, PoolStaffController staff, PoolExamController exams,
                           McpStudentTools student, RosterService roster) {
        this.db = db;
        this.json = json;
        this.staff = staff;
        this.exams = exams;
        this.student = student;
        this.roster = roster;
    }

    List<McpTool> tools() {
        Audience t = Audience.INSTRUCTOR;
        return List.of(
                // ---------- people ----------
                McpTool.read(t, "class_overview", "Class overview",
                        "One row per student of the course: real name, number, questions written and approved, "
                                + "answers, first-try correct, comments, reports, last login, and whether they use an "
                                + "MCP agent.",
                        schema(), c -> classOverview(c.courseId())),
                McpTool.read(t, "student_detail", "One student in detail",
                        "Everything about one student of the course: questions, comments, reports, results per "
                                + "chapter, the study state their agent saved, and their study diary. Find the student "
                                + "by id, e-mail, student number, nickname or a part of the name.",
                        schema("student!", str("Id, e-mail, student number, nickname or part of the name.")),
                        c -> studentDetail(c.courseId(), c.args().str("student"))),
                McpTool.read(t, "study_overview", "Study state of the class",
                        "Per chapter: how many students are in each study state, average confidence, practice "
                                + "answers, and the most common weak points students' agents noted.",
                        schema(), c -> studyOverview(c.courseId())),
                McpTool.write(t, "import_students", "Add students to the course roster",
                        "Adds or updates students on the course roster (never removes anyone). One per line, either "
                                + "'student number, full name[, e-mail]' or 'e-mail, full name, student number' "
                                + "(comma, semicolon or tab). A student number is required. Ask the staff member "
                                + "before you run it.",
                        schema("csv!", str("The lines to import.")),
                        c -> roster.importText(c.courseId(), c.args().str("csv"), "MERGE")),

                // ---------- questions ----------
                McpTool.read(t, "question_counts", "Question counts",
                        "How many questions are waiting, sent back, approved and rejected, per chapter.",
                        schema(), c -> questionCounts(c.courseId())),
                McpTool.read(t, "list_questions_for_review", "Questions in one state",
                        "Lists questions in one state with all fields, the AI check, the author's real name, answer "
                                + "numbers and open reports. Default state: PENDING (waiting for review).",
                        schema("status", oneOf("Question state.", "PENDING", "NEEDS_REVISION", "APPROVED", "REJECTED"),
                                "chapter", integer("Chapter number (optional).")),
                        c -> reviewList(c)),
                McpTool.write(t, "decide_question", "Approve, send back or reject",
                        "Approves a question, sends it back for changes (REVISE) or rejects it. REVISE and REJECT "
                                + "need a note for the student in simple English. Show your plan and ask the staff "
                                + "member before you decide.",
                        schema("question_id!", integer("Question id."),
                                "action!", oneOf("The decision.", "APPROVE", "REVISE", "REJECT"),
                                "note", str("Note for the student. Required for REVISE and REJECT.")), c -> {
                            long id = c.args().num("question_id");
                            return staff.decide(c.courseOfQuestion(id), id, new PoolStaffController.DecisionIn(
                                    c.args().str("action").trim().toUpperCase(), c.args().optStr("note")));
                        }),
                McpTool.write(t, "edit_question", "Fix the wording of a question",
                        "Changes the text of any question of the course. Send only the fields that change. The state "
                                + "does not change.",
                        McpStudentTools.questionSchema(false), c -> {
                            long id = c.args().num("question_id");
                            long courseId = c.courseOfQuestion(id);
                            Map<String, Object> current = student.question(id);
                            return staff.edit(courseId, id, McpStudentTools.questionIn(c.args(), current, null));
                        }),
                McpTool.read(t, "question_stats", "Question statistics",
                        "Approved questions with the number of answers, the share of correct answers and how often "
                                + "each option was chosen. Hardest first. Use it to find bad keys or weak distractors.",
                        schema("chapter", integer("Chapter number (optional)."),
                                "min_answers", integer("Only questions with at least this many answers (default 1).")),
                        c -> questionStats(c)),

                // ---------- reports and moderation ----------
                McpTool.read(t, "list_flags", "Problem reports",
                        "Problem reports from students. Open reports only, unless include_resolved is true.",
                        schema("include_resolved", bool("Also list solved reports.")),
                        c -> staff.flags(c.courseId()).stream()
                                .filter(f -> Boolean.TRUE.equals(c.args().optBool("include_resolved"))
                                        || Boolean.FALSE.equals(f.get("resolved")))
                                .toList()),
                McpTool.write(t, "resolve_flag", "Mark a report as solved", "Marks a problem report as solved.",
                        schema("flag_id!", integer("Report id.")), c -> {
                            long id = c.args().num("flag_id");
                            return staff.resolveFlag(c.courseOf(COURSE_OF_FLAG, id, "Report not found."), id);
                        }),
                new McpTool("hide_comment", "Hide a comment",
                        "Hides a comment from students (the text stays for the report). Ask the staff member first.",
                        schema("comment_id!", integer("Comment id.")), t, false, true, c -> {
                            long id = c.args().num("comment_id");
                            return staff.hideComment(c.courseOf(COURSE_OF_COMMENT, id, "Comment not found."), id);
                        }),

                // ---------- chapters and exams ----------
                McpTool.write(t, "set_chapter_open", "Open or close a chapter",
                        "Opens or closes a chapter for new student questions.",
                        schema("chapter!", chapter(), "open!", bool("true = open for new questions.")), c -> {
                            long courseId = c.courseId();
                            return staff.updateChapter(courseId, student.chapterId(courseId, c.args().num("chapter")),
                                    new PoolStaffController.ChapterIn(null, null, null, null, c.args().optBool("open")));
                        }),
                McpTool.read(t, "list_exams", "List exams", "Lists the exams built from the pool so far.", schema(),
                        c -> exams.list(c.courseId())),
                McpTool.read(t, "get_exam", "One exam",
                        "The questions of an exam in chapter order, with the key and answer numbers.",
                        schema("exam_id!", integer("Exam id.")), c -> {
                            long id = c.args().num("exam_id");
                            return exam(c.courseOf(COURSE_OF_EXAM, id, "Exam not found."), id);
                        }),
                McpTool.write(t, "create_exam", "Build an exam",
                        "Draws approved questions at random, a number per chapter. Each form (A, B, ...) has the same "
                                + "questions in another order. Print the forms on the web site (Exams). Ask the staff "
                                + "member before you run it.",
                        schema("title!", str("Exam title, at most 120 characters."),
                                "questions_per_chapter!",
                                Map.of("type", "object", "description",
                                        "Chapter number -> how many questions, for example {\"1\": 3, \"2\": 3}.",
                                        "additionalProperties", Map.of("type", "integer")),
                                "forms", integer("Number of forms, 1-4 (default 2)."),
                                "skip_flagged", bool("Skip questions with an open report (default true).")),
                        c -> createExam(c)));
    }

    // ---------- implementations ----------

    private List<Map<String, Object>> classOverview(long courseId) {
        Map<Long, Map<String, Object>> mcp = new LinkedHashMap<>();
        db.sql("select person_id, last_used_at from mcp_token").query().listOfRows()
                .forEach(r -> mcp.put(((Number) r.get("person_id")).longValue(), r));
        return staff.students(courseId).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>(s);
            Map<String, Object> token = mcp.get(((Number) s.get("id")).longValue());
            m.put("has_mcp_token", token != null);
            m.put("mcp_last_used_at", token == null ? null : token.get("last_used_at"));
            return m;
        }).toList();
    }

    private Map<String, Object> studentDetail(long courseId, String query) {
        String q = query.trim();
        List<Map<String, Object>> found = db.sql("""
                select u.id, u.full_name, u.email, u.student_no, u.nickname
                from enrollment e join person u on u.id = e.person_id
                where e.course_id = :course and e.role = 'STUDENT' and e.deleted_at is null and u.deleted_at is null
                  and (cast(u.id as text) = :q or lower(u.email) = lower(:q) or u.student_no = :q
                       or lower(u.nickname) = lower(:q) or u.full_name ilike '%' || :q || '%')
                order by u.full_name limit 20""").param("course", courseId).param("q", q).query().listOfRows();
        if (found.isEmpty()) {
            throw new IllegalArgumentException("No student of this course matches '" + q + "'.");
        }
        if (found.size() > 1) {
            return Map.of("message", "More than one student matches. Ask again with the id.", "matches", found);
        }
        long id = ((Number) found.get(0).get("id")).longValue();
        Map<String, Object> out = new LinkedHashMap<>(staff.student(courseId, id));
        out.put("progress", student.progress(id, courseId));
        out.put("study_log", student.studyLog(id, courseId, 20, false));
        out.put("mcp", db.sql("select created_at, last_used_at from mcp_token where person_id = :u").param("u", id)
                .query().listOfRows().stream().findFirst().orElse(null));
        parseReviews((List<?>) out.get("questions"));
        return out;
    }

    private Map<String, Object> studyOverview(long courseId) {
        String students = """
                select u.id from enrollment e join person u on u.id = e.person_id
                where e.course_id = :course and e.role = 'STUDENT' and e.deleted_at is null and u.deleted_at is null""";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("students", db.sql("select count(*) from (" + students + ") s").param("course", courseId)
                .query(Integer.class).single());
        out.put("students_with_agent", db.sql("""
                select count(*) from mcp_token t
                where t.last_used_at is not null and t.person_id in (%s)""".formatted(students))
                .param("course", courseId).query(Integer.class).single());
        out.put("chapters", db.sql("""
                select c.number as chapter, c.title,
                    count(s.person_id) filter (where s.status = 'STUDYING') as studying,
                    count(s.person_id) filter (where s.status = 'REVIEWING') as reviewing,
                    count(s.person_id) filter (where s.status = 'DONE') as done,
                    round(avg(s.confidence), 2) as avg_confidence,
                    (select count(*) from practice_answer p join question q on q.id = p.question_id
                        where q.chapter_id = c.id) as practice_answers,
                    (select round(100.0 * count(*) filter (where p.correct) / nullif(count(*), 0), 1)
                        from practice_answer p join question q on q.id = p.question_id
                        where q.chapter_id = c.id) as practice_correct_pct
                from chapter c
                left join study_state s on s.chapter_id = c.id and s.person_id in (%s)
                where c.course_id = :course
                group by c.id order by c.number""".formatted(students)).param("course", courseId).query()
                .listOfRows());
        out.put("notes", db.sql("""
                select c.number as chapter, u.nickname, s.confidence, s.notes, s.updated_at
                from study_state s join chapter c on c.id = s.chapter_id join person u on u.id = s.person_id
                where c.course_id = :course and s.notes <> '' and s.person_id in (%s)
                order by c.number, s.updated_at desc limit 200""".formatted(students)).param("course", courseId)
                .query().listOfRows());
        out.put("note_for_agent", "Summarise the notes into the most common weak points per chapter.");
        return out;
    }

    private List<Map<String, Object>> questionCounts(long courseId) {
        return db.sql("""
                select c.number as chapter, c.title, c.open,
                    count(q.id) filter (where q.status = 'PENDING') as pending,
                    count(q.id) filter (where q.status = 'NEEDS_REVISION') as needs_revision,
                    count(q.id) filter (where q.status = 'APPROVED') as approved,
                    count(q.id) filter (where q.status = 'REJECTED') as rejected
                from chapter c left join question q on q.chapter_id = c.id
                where c.course_id = :course
                group by c.id order by c.number""").param("course", courseId).query().listOfRows();
    }

    private List<Map<String, Object>> reviewList(McpCall c) {
        long courseId = c.courseId();
        Args a = c.args();
        String status = a.optStr("status") == null ? "PENDING" : a.optStr("status").trim().toUpperCase();
        Integer chapter = a.optInt("chapter");
        List<Map<String, Object>> rows = staff.questions(courseId, status,
                chapter == null ? null : student.chapterId(courseId, chapter), null).stream()
                .map(r -> (Map<String, Object>) new LinkedHashMap<>(r)).toList();
        parseReviews(rows);
        return rows;
    }

    private List<Map<String, Object>> questionStats(McpCall c) {
        long courseId = c.courseId();
        Integer chapter = c.args().optInt("chapter");
        Integer min = c.args().optInt("min_answers");
        return db.sql("""
                select q.id, c.number as chapter, q.stem, q.correct, u.full_name as author_name,
                    count(t.id) as answers,
                    round(100.0 * count(t.id) filter (where t.correct) / nullif(count(t.id), 0), 1) as correct_pct,
                    count(t.id) filter (where t.selected = 'A') as chose_a,
                    count(t.id) filter (where t.selected = 'B') as chose_b,
                    count(t.id) filter (where t.selected = 'C') as chose_c,
                    count(t.id) filter (where t.selected = 'D') as chose_d,
                    (select count(*) from question_flag f where f.question_id = q.id and not f.resolved) as open_flags
                from question q
                join chapter c on c.id = q.chapter_id
                join person u on u.id = q.author_id
                left join question_attempt t on t.question_id = q.id
                where c.course_id = :course and q.status = 'APPROVED'
                  and (cast(:c as bigint) is null or q.chapter_id = :c)
                group by q.id, c.number, u.full_name
                having count(t.id) >= :min
                order by correct_pct nulls last, answers desc""")
                .param("course", courseId)
                .param("c", chapter == null ? null : student.chapterId(courseId, chapter))
                .param("min", min == null ? 1 : min)
                .query().listOfRows();
    }

    private Map<String, Object> exam(long courseId, long id) {
        Map<String, Object> out = new LinkedHashMap<>(exams.get(courseId, id));
        out.put("print_pages", "Open Exams on the web site to print the forms and keys.");
        return out;
    }

    private Map<String, Object> createExam(McpCall c) {
        long courseId = c.courseId();
        Args a = c.args();
        JsonNode counts = a.raw("questions_per_chapter");
        if (!counts.isObject()) {
            throw new IllegalArgumentException("questions_per_chapter must be an object such as {\"1\": 3}.");
        }
        Map<Long, Integer> byChapterId = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : counts.properties()) {
            if (!e.getKey().trim().matches("\\d+") || !e.getValue().isIntegralNumber()) {
                throw new IllegalArgumentException("Use chapter numbers as keys and whole numbers as values.");
            }
            byChapterId.put(student.chapterId(courseId, Long.parseLong(e.getKey().trim())), e.getValue().asInt());
        }
        Map<String, Object> created = exams.create(courseId, new PoolExamController.ExamIn(a.str("title"),
                a.optInt("forms"), byChapterId, a.optBool("skip_flagged")));
        return exam(courseId, ((Number) created.get("id")).longValue());
    }

    @SuppressWarnings("unchecked")
    private void parseReviews(List<?> rows) {
        for (Object row : rows) {
            Map<String, Object> m = (Map<String, Object>) row;
            if (m.get("ai_review") instanceof String s && !s.isBlank()) {
                m.put("ai_review", json.readTree(s));
            }
        }
    }
}
