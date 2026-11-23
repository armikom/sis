package com.armikom.sis.agent;

import com.armikom.sis.agent.McpTool.Args;
import com.armikom.sis.agent.McpTool.Audience;
import com.armikom.sis.common.ApiException;
import com.armikom.sis.common.ErrorCode;
import com.armikom.sis.exam.ExamAnalysisService;
import com.armikom.sis.exam.ExamDtos.AnswerRequest;
import com.armikom.sis.exam.ExamDtos.ExamDto;
import com.armikom.sis.exam.ExamDtos.ExamRequest;
import com.armikom.sis.exam.ExamDtos.ExamResultRequest;
import com.armikom.sis.exam.ExamDtos.GroupRequest;
import com.armikom.sis.exam.ExamDtos.OptionDto;
import com.armikom.sis.exam.ExamDtos.QuestionDto;
import com.armikom.sis.exam.ExamDtos.QuestionRequest;
import com.armikom.sis.exam.ExamService;
import com.armikom.sis.exam.QuestionType;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import static com.armikom.sis.agent.McpTool.integer;
import static com.armikom.sis.agent.McpTool.intList;
import static com.armikom.sis.agent.McpTool.listOf;
import static com.armikom.sis.agent.McpTool.number;
import static com.armikom.sis.agent.McpTool.obj;
import static com.armikom.sis.agent.McpTool.oneOf;
import static com.armikom.sis.agent.McpTool.schema;
import static com.armikom.sis.agent.McpTool.str;

/**
 * MCP tools for course staff to read and edit exams (definitions, questions, results, analysis). They call
 * {@link ExamService} and {@link ExamAnalysisService}, the same code as the exam screens, so every rule (roster-only
 * results, label sets, groups, rescoring) applies in one place. Arguments are snake_case (camelCase is accepted too);
 * results are the exam module's camelCase DTOs, unchanged.
 */
@Component
@Order(3)
public class McpExamTools implements McpToolProvider {

    static final String COURSE_OF_EXAM = "select course_id from exam where id = :id";
    static final String COURSE_OF_QUESTION =
            "select e.course_id from exam_question q join exam e on e.id = q.exam_id where q.id = :id";

    private static final String REPLACE_WARNING = " Questions not sent are DELETED together with every answer "
            + "students gave to them; a question keeps its answers only when it is sent with its id.";

    private final ExamService exams;
    private final ExamAnalysisService analysis;
    private final JdbcClient db;
    private final JsonMapper json;

    public McpExamTools(ExamService exams, ExamAnalysisService analysis, JdbcClient db, JsonMapper json) {
        this.exams = exams;
        this.analysis = analysis;
        this.db = db;
        this.json = json;
    }

    @Override
    public List<McpTool> tools() {
        Audience t = Audience.INSTRUCTOR;
        Map<String, Object> question = questionSchema(false);
        Map<String, Object> questionWithId = questionSchema(true);
        Map<String, Object> newGroup = obj("A group of sub-questions (printed as one number with a, b, ... parts).",
                "ref!", str("Key the questions of this request use in group_ref (unique in the request)."),
                "text", str("Shared stem of the group (plain text)."),
                "html", str("Shared stem as rich HTML content (optional)."));
        Map<String, Object> existingGroup = obj("A group of sub-questions. Send the id to keep an existing group.",
                "id", integer("Id of an existing group of this exam (omit for a new group)."),
                "ref!", str("Key the questions of this request use in group_ref (unique in the request)."),
                "text", str("Shared stem of the group (plain text)."),
                "html", str("Shared stem as rich HTML content (optional)."));
        Map<String, Object> answer = obj("One answer of the student.",
                "question_id!", integer("Exam question id (see get_exam)."),
                "answer", str("CHOICE: the chosen label (one of the question's choice_labels), empty = blank. "
                        + "OPEN: the answer text. FREE: not used."),
                "points", number("Points given. Needed for OPEN and FREE questions; ignored for CHOICE (scored "
                        + "from the key)."));

        return List.of(
                McpTool.read(t, "list_exams", "List exams",
                        "The exams of the course: id, title, date, forms, question count, total points, results so "
                                + "far, average, published.",
                        schema(), c -> exams.list(c.courseId())),
                McpTool.read(t, "get_exam", "One exam in full",
                        "The whole exam: title, date, forms, groups and every question with its id, position, "
                                + "printed label, type, text, html, topic, points, choice labels, key, options with "
                                + "rationales, explanation and topics. Results are not included (see exam_results).",
                        schema("exam_id!", integer("Exam id.")), c -> {
                            long id = c.args().num("exam_id");
                            c.courseOf(COURSE_OF_EXAM, id, "Exam not found.");
                            return api(() -> exams.get(id));
                        }),
                McpTool.write(t, "create_exam", "Create an exam from scratch",
                        "Creates an exam with the questions you give (any type, any content; for an exam drawn "
                                + "from the pool use create_exam_from_pool). Questions are stored in the order "
                                + "sent; questions of a group must be consecutive. Ask the staff member first.",
                        schema("title!", str("Exam title (at most 200 characters)."),
                                "exam_date", str("Exam date YYYY-MM-DD (optional)."),
                                "forms", integer("Number of printed forms, 1-4 (default 1)."),
                                "seed", integer("Shuffle seed for the forms (optional)."),
                                "questions!", listOf(question, "The questions in order."),
                                "groups", listOf(newGroup, "Groups of sub-questions (optional).")),
                        c -> {
                            long courseId = c.courseId();
                            ExamRequest req = examRequest(c.args(), null);
                            return api(() -> exams.create(courseId, req));
                        }),
                McpTool.write(t, "update_exam", "Replace the whole exam",
                        "REPLACES the whole exam like PUT /api/exams/{id}: title, date, forms, groups and the full "
                                + "list of questions." + REPLACE_WARNING + " Groups must be sent with their id and a "
                                + "ref, and their questions with that group_ref, or the groups are dropped. Prefer "
                                + "add_exam_question, update_exam_question, remove_exam_question and "
                                + "set_exam_question_topics for single changes. Ask the staff member first.",
                        schema("exam_id!", integer("Exam id."),
                                "title!", str("Exam title (at most 200 characters)."),
                                "exam_date", str("Exam date YYYY-MM-DD (optional; omit to keep)."),
                                "forms", integer("Number of printed forms, 1-4 (omit to keep)."),
                                "seed", integer("Shuffle seed for the forms (omit to keep)."),
                                "questions!", listOf(questionWithId, "ALL questions of the exam in order; existing "
                                        + "ones with their id."),
                                "groups", listOf(existingGroup, "All groups of the exam (omit = no groups).")),
                        c -> {
                            long id = c.args().num("exam_id");
                            c.courseOf(COURSE_OF_EXAM, id, "Exam not found.");
                            ExamDto current = api(() -> exams.get(id));
                            ExamRequest req = examRequest(c.args(), current);
                            return api(() -> exams.update(id, req));
                        }),
                McpTool.write(t, "add_exam_question", "Add one question to an exam",
                        "Adds one question to an existing exam and keeps everything else as it is. By default the "
                                + "question is appended; position inserts it there (1 = first). To put it in an "
                                + "existing group send group_ref 'g<groupId>' (the groups of get_exam) next to that "
                                + "group's questions. Ask the staff member first.",
                        withExamId(question, "position", integer("Position to insert at, 1-based (default: last).")),
                        c -> {
                            long id = c.args().num("exam_id");
                            c.courseOf(COURSE_OF_EXAM, id, "Exam not found.");
                            ExamDto exam = api(() -> exams.get(id));
                            ExamRequest base = toRequest(exam);
                            List<QuestionRequest> questions = new ArrayList<>(base.questions());
                            QuestionRequest added = question(c.args().node(), null);
                            Integer position = c.args().optInt("position");
                            int at = position == null ? questions.size()
                                    : Math.max(0, Math.min(questions.size(), position - 1));
                            questions.add(at, added);
                            return api(() -> exams.update(id, withQuestions(base, questions)));
                        }),
                McpTool.write(t, "update_exam_question", "Change one exam question",
                        "Changes one question of an exam; only the fields you send change, the others keep their "
                                + "value (type, text, html, topic, max_points, choice_labels, correct_choice, "
                                + "topic_ids, explanation, options, group_ref, sub_label). Use it to add notes for "
                                + "students: explanation (shown with the results after publishing), html (rich "
                                + "content) and options[].rationale. A changed key, points or label set rescores "
                                + "the stored answers; a changed type drops them. Ask the staff member first.",
                        questionPatchSchema(), c -> {
                            long questionId = c.args().num("question_id");
                            c.courseOf(COURSE_OF_QUESTION, questionId, "Exam question not found.");
                            return patchQuestion(questionId, c.args().node());
                        }),
                new McpTool("remove_exam_question", "Remove a question from an exam",
                        "Deletes one question of an exam together with every answer students gave to it; the "
                                + "other questions move up. This cannot be undone: ask the staff member first and "
                                + "name the question.",
                        schema("question_id!", integer("Exam question id (see get_exam).")), t, false, true, c -> {
                            long questionId = c.args().num("question_id");
                            c.courseOf(COURSE_OF_QUESTION, questionId, "Exam question not found.");
                            ExamDto exam = examOfQuestion(questionId);
                            ExamRequest base = toRequest(exam);
                            List<QuestionRequest> kept = base.questions().stream()
                                    .filter(q -> !Long.valueOf(questionId).equals(q.id())).toList();
                            return api(() -> exams.update(exam.id(), withQuestions(base, kept)));
                        }),
                McpTool.write(t, "set_exam_question_topics", "Set the topics of an exam question",
                        "Replaces the explicit topics of one exam question; [] removes them. Topic ids come from "
                                + "list_topics. A question linked to a pool question also inherits that question's "
                                + "topics.",
                        schema("question_id!", integer("Exam question id (see get_exam)."),
                                "topic_ids!", intList("Topic ids of this course; [] removes all.")), c -> {
                            long questionId = c.args().num("question_id");
                            c.courseOf(COURSE_OF_QUESTION, questionId, "Exam question not found.");
                            List<Long> ids = c.args().optLongList("topic_ids");
                            if (ids == null) throw new IllegalArgumentException("The argument 'topic_ids' is required.");
                            ObjectNode patch = json.createObjectNode();
                            patch.set("topic_ids", c.args().raw("topic_ids"));
                            return patchQuestion(questionId, patch);
                        }),
                McpTool.read(t, "exam_results", "Results of an exam",
                        "Every saved result of the exam: student number and name, form, note, total, graded count "
                                + "and the answers per question with points.",
                        schema("exam_id!", integer("Exam id.")), c -> {
                            long id = c.args().num("exam_id");
                            c.courseOf(COURSE_OF_EXAM, id, "Exam not found.");
                            return api(() -> exams.results(id));
                        }),
                McpTool.write(t, "save_exam_result", "Save the result of one student",
                        "Saves (or replaces) the answer sheet of one student for an exam. Only students on the "
                                + "course roster can have a result. CHOICE answers are scored from the key; OPEN and "
                                + "FREE answers need points. Answers not sent are removed from the sheet. Ask the "
                                + "staff member first.",
                        schema("exam_id!", integer("Exam id."),
                                "student_no!", str("Student number (on the roster)."),
                                "answers", listOf(answer, "The answers; questions left out have no answer."),
                                "note", str("Note on the sheet (optional, at most 500 characters)."),
                                "form", str("Form letter A-D (optional).")),
                        c -> {
                            long id = c.args().num("exam_id");
                            c.courseOf(COURSE_OF_EXAM, id, "Exam not found.");
                            Args a = c.args();
                            ExamResultRequest req = new ExamResultRequest(null, a.optStr("note"),
                                    answers(a.raw("answers")), a.optStr("form"));
                            String studentNo = a.str("student_no");
                            return api(() -> exams.upsertResult(id, studentNo, req));
                        }),
                McpTool.read(t, "exam_analysis", "Analysis of an exam",
                        "Class statistics of an exam: average, median, min, max; per question graded count, "
                                + "average points, success rate and choice counts; per topic success rate and weak "
                                + "students; per student total, percent and weak topics.",
                        schema("exam_id!", integer("Exam id.")), c -> {
                            long id = c.args().num("exam_id");
                            c.courseOf(COURSE_OF_EXAM, id, "Exam not found.");
                            return api(() -> analysis.exam(id));
                        }));
    }

    // ---------- schemas ----------

    /** The question fields (snake_case) and their schemas, in the order they are shown. */
    private static final Map<String, Object> QUESTION_FIELDS = new LinkedHashMap<>();

    static {
        QUESTION_FIELDS.put("type", oneOf("CHOICE = multiple choice scored from the key; OPEN = answer text plus "
                + "points given by hand; FREE = points only.",
                Arrays.stream(QuestionType.values()).map(Enum::name).toArray(String[]::new)));
        QUESTION_FIELDS.put("max_points", number("Points of the question (> 0, at most 1000)."));
        QUESTION_FIELDS.put("text", str("Question text (plain; at most 20000 characters)."));
        QUESTION_FIELDS.put("html", str("Rich content (HTML) shown instead of text when present; '' clears it."));
        QUESTION_FIELDS.put("topic", str("Free-text topic label printed with the question (at most 120 characters)."));
        QUESTION_FIELDS.put("choice_labels", str("CHOICE: one character per option, for example 'ABCD', 'ABCDE', "
                + "'DY', 'TF' (2-10 distinct letters or digits)."));
        QUESTION_FIELDS.put("choice_count", integer("CHOICE: number of options labelled A, B, ... (used when "
                + "choice_labels is not sent)."));
        QUESTION_FIELDS.put("correct_choice", str("CHOICE: the correct label (one of choice_labels)."));
        QUESTION_FIELDS.put("pool_question_id", integer("Id of the pool question this one was taken from (optional)."));
        QUESTION_FIELDS.put("topic_ids", intList("Topic ids of this course (see list_topics); [] removes all."));
        QUESTION_FIELDS.put("explanation", str("Explanation shown to students with their results after publishing; "
                + "'' clears it."));
        QUESTION_FIELDS.put("options", listOf(obj("One option of a CHOICE question.",
                "letter!", str("The option's label (one of choice_labels)."),
                "text", str("Option text."),
                "rationale", str("Why this option is right or wrong (shown to students with the results).")),
                "The options of a CHOICE question with their texts and rationales; replaces the stored list."));
        QUESTION_FIELDS.put("group_ref", str("Key of the group (groups[].ref) this question belongs to; '' removes "
                + "it from its group."));
        QUESTION_FIELDS.put("sub_label", str("Label inside the group ('a', 'b', 'iii'); blank = a, b, ... by order."));
    }

    /** (name, schema) pairs of the question fields; {@code type} and {@code max_points} required when asked. */
    private static List<Object> questionPairs(boolean required) {
        List<Object> pairs = new ArrayList<>();
        QUESTION_FIELDS.forEach((k, v) -> {
            pairs.add(required && (k.equals("type") || k.equals("max_points")) ? k + "!" : k);
            pairs.add(v);
        });
        return pairs;
    }

    /** The schema of one question object; with {@code withId} it also has {@code id} (update_exam). */
    private static Map<String, Object> questionSchema(boolean withId) {
        List<Object> pairs = new ArrayList<>();
        if (withId) {
            pairs.add("id");
            pairs.add(integer("Id of an existing question of this exam; omit for a new question."));
        }
        pairs.addAll(questionPairs(true));
        return obj("One exam question. Fields are snake_case.", pairs.toArray());
    }

    /** The top-level schema of add_exam_question: exam_id, the question fields and extra pairs. */
    private static Map<String, Object> withExamId(Map<String, Object> question, Object... extra) {
        List<Object> pairs = new ArrayList<>();
        pairs.add("exam_id!");
        pairs.add(integer("Exam id."));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) question.get("properties");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) question.getOrDefault("required", List.of());
        props.forEach((k, v) -> {
            pairs.add(required.contains(k) ? k + "!" : k);
            pairs.add(v);
        });
        pairs.addAll(Arrays.asList(extra));
        return schema(pairs.toArray());
    }

    /** The schema of update_exam_question: question_id plus every question field, all optional. */
    private static Map<String, Object> questionPatchSchema() {
        List<Object> pairs = new ArrayList<>();
        pairs.add("question_id!");
        pairs.add(integer("Exam question id (see get_exam)."));
        pairs.addAll(questionPairs(false));
        return schema(pairs.toArray());
    }

    // ---------- request building ----------

    /** The exam body from the tool arguments; {@code current} (update) supplies the values not sent. */
    private ExamRequest examRequest(Args a, ExamDto current) {
        JsonNode questions = a.raw("questions");
        if (!questions.isArray()) {
            throw new IllegalArgumentException("The argument 'questions' must be a list of question objects.");
        }
        List<QuestionRequest> qs = new ArrayList<>();
        for (JsonNode q : questions) {
            if (!q.isObject()) throw new IllegalArgumentException("Every entry of 'questions' must be an object.");
            qs.add(question(q, null));
        }
        List<GroupRequest> groups = new ArrayList<>();
        JsonNode gs = a.raw("groups");
        if (gs.isArray()) {
            for (JsonNode g : gs) {
                if (!g.isObject()) throw new IllegalArgumentException("Every entry of 'groups' must be an object.");
                groups.add(new GroupRequest(longOf(g, "id"), text(g, "ref"), text(g, "text"), text(g, "html")));
            }
        } else if (!gs.isMissingNode() && !gs.isNull()) {
            throw new IllegalArgumentException("The argument 'groups' must be a list of group objects.");
        }
        LocalDate date = a.has("exam_date") || a.has("examDate") ? date(text(a.node(), "exam_date"))
                : current == null ? null : current.examDate();
        Integer forms = a.has("forms") ? a.optInt("forms") : current == null ? null : current.forms();
        Long seed = a.has("seed") ? a.optNum("seed") : current == null ? null : current.seed();
        return new ExamRequest(a.str("title"), date, qs, forms, seed, groups);
    }

    /**
     * One question from a JSON object with snake_case (or camelCase) fields. With {@code base}, fields that are not
     * sent keep the base value (patch); without it they are null.
     */
    static QuestionRequest question(JsonNode n, QuestionRequest base) {
        QuestionRequest b = base != null ? base : new QuestionRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        QuestionType type = has(n, "type") ? type(text(n, "type")) : b.type();
        if (type == null) throw new IllegalArgumentException("Every question needs a 'type' (CHOICE, OPEN or FREE).");
        BigDecimal maxPoints = has(n, "max_points") ? decimal(n, "max_points") : b.maxPoints();
        if (maxPoints == null) throw new IllegalArgumentException("Every question needs 'max_points'.");
        String choiceLabels = has(n, "choice_labels") ? text(n, "choice_labels") : b.choiceLabels();
        Integer choiceCount = has(n, "choice_count") ? intOf(n, "choice_count") : b.choiceCount();
        if (has(n, "choice_labels") && !has(n, "choice_count")) choiceCount = null;
        List<OptionDto> options = has(n, "options") ? options(field(n, "options")) : b.options();
        // A question that is no longer CHOICE may only have an empty option list.
        if (type != QuestionType.CHOICE && !has(n, "options")) options = List.of();
        return new QuestionRequest(
                has(n, "id") ? longOf(n, "id") : b.id(),
                type,
                has(n, "text") ? text(n, "text") : b.text(),
                has(n, "topic") ? text(n, "topic") : b.topic(),
                maxPoints,
                choiceCount,
                has(n, "correct_choice") ? text(n, "correct_choice") : b.correctChoice(),
                has(n, "pool_question_id") ? longOf(n, "pool_question_id") : b.poolQuestionId(),
                has(n, "topic_ids") ? longList(n, "topic_ids") : b.topicIds(),
                has(n, "explanation") ? text(n, "explanation") : b.explanation(),
                options,
                choiceLabels,
                has(n, "group_ref") ? text(n, "group_ref") : b.groupRef(),
                has(n, "sub_label") ? text(n, "sub_label") : b.subLabel(),
                has(n, "html") ? text(n, "html") : b.html());
    }

    /** An exam as a request that saves it back unchanged: groups keep their ids (ref "g<id>"), questions their ids. */
    static ExamRequest toRequest(ExamDto exam) {
        List<GroupRequest> groups = exam.groups().stream()
                .map(g -> new GroupRequest(g.id(), groupRef(g.id()), g.text(), g.html())).toList();
        List<QuestionRequest> questions = exam.questions().stream().map(McpExamTools::toRequest).toList();
        return new ExamRequest(exam.title(), exam.examDate(), questions, exam.forms(), exam.seed(), groups);
    }

    /** topicIds as a mutable copy: the service checks {@code contains(null)}, which immutable lists refuse. */
    static QuestionRequest toRequest(QuestionDto q) {
        return new QuestionRequest(q.id(), q.type(), q.text(), q.topic(), q.maxPoints(), null, q.correctChoice(),
                q.poolQuestionId(), q.topicIds() == null ? null : new ArrayList<>(q.topicIds()),
                q.explanation() == null ? "" : q.explanation(), q.options() == null ? List.of() : q.options(),
                q.choiceLabels(),
                q.groupId() == null ? null : groupRef(q.groupId()), q.subLabel(), q.html() == null ? "" : q.html());
    }

    static String groupRef(long groupId) {
        return "g" + groupId;
    }

    private static ExamRequest withQuestions(ExamRequest base, List<QuestionRequest> questions) {
        return new ExamRequest(base.title(), base.examDate(), questions, base.forms(), base.seed(), base.groups());
    }

    /** Replaces one question of its exam with the patched version; the rest of the exam is sent back unchanged. */
    private ExamDto patchQuestion(long questionId, JsonNode patch) {
        ExamDto exam = examOfQuestion(questionId);
        ExamRequest base = toRequest(exam);
        List<QuestionRequest> questions = new ArrayList<>();
        for (QuestionRequest q : base.questions()) {
            questions.add(Long.valueOf(questionId).equals(q.id()) ? question(patch, q) : q);
        }
        return api(() -> exams.update(exam.id(), withQuestions(base, questions)));
    }

    private ExamDto examOfQuestion(long questionId) {
        ExamDto exam = api(() -> exams.get(examIdOfQuestion(questionId)));
        if (exam.questions().stream().noneMatch(q -> q.id() == questionId)) {
            throw new IllegalArgumentException("Exam question not found.");
        }
        return exam;
    }

    /** The exam of a question (read-only lookup; the audience check happened in courseOf). */
    private long examIdOfQuestion(long questionId) {
        return db.sql("select exam_id from exam_question where id = :id").param("id", questionId).query(Long.class)
                .optional().orElseThrow(() -> new IllegalArgumentException("Exam question not found."));
    }

    private static List<AnswerRequest> answers(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return List.of();
        if (!n.isArray()) throw new IllegalArgumentException("The argument 'answers' must be a list of objects.");
        List<AnswerRequest> out = new ArrayList<>();
        for (JsonNode a : n) {
            if (!a.isObject()) throw new IllegalArgumentException("Every entry of 'answers' must be an object.");
            out.add(new AnswerRequest(longOf(a, "question_id"), text(a, "answer"), decimal(a, "points")));
        }
        return out;
    }

    private static List<OptionDto> options(JsonNode n) {
        if (n.isNull()) return null;
        if (!n.isArray()) throw new IllegalArgumentException("'options' must be a list of {letter, text, rationale}.");
        List<OptionDto> out = new ArrayList<>();
        for (JsonNode o : n) {
            if (!o.isObject()) throw new IllegalArgumentException("Every option must be an object.");
            out.add(new OptionDto(text(o, "letter"), text(o, "text"), text(o, "rationale")));
        }
        return out;
    }

    // ---------- JSON readers (snake_case first, camelCase alias) ----------

    private static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char ch : snake.toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return sb.toString();
    }

    private static JsonNode field(JsonNode n, String snake) {
        JsonNode v = n.path(snake);
        if (v.isMissingNode()) v = n.path(camel(snake));
        return v;
    }

    private static boolean has(JsonNode n, String snake) {
        return !field(n, snake).isMissingNode();
    }

    private static String text(JsonNode n, String snake) {
        JsonNode v = field(n, snake);
        if (v.isMissingNode() || v.isNull()) return null;
        if (!v.isValueNode()) throw new IllegalArgumentException("'" + snake + "' must be text.");
        return v.asString();
    }

    private static Long longOf(JsonNode n, String snake) {
        JsonNode v = field(n, snake);
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isString() && v.asString().trim().matches("-?\\d+")) return Long.parseLong(v.asString().trim());
        throw new IllegalArgumentException("'" + snake + "' must be a whole number.");
    }

    private static Integer intOf(JsonNode n, String snake) {
        Long v = longOf(n, snake);
        return v == null ? null : Math.toIntExact(v);
    }

    private static BigDecimal decimal(JsonNode n, String snake) {
        JsonNode v = field(n, snake);
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isNumber()) return v.decimalValue();
        if (v.isString() && v.asString().trim().matches("-?\\d+(\\.\\d+)?")) {
            return new BigDecimal(v.asString().trim());
        }
        throw new IllegalArgumentException("'" + snake + "' must be a number.");
    }

    private static List<Long> longList(JsonNode n, String snake) {
        JsonNode v = field(n, snake);
        if (v.isNull()) return null;
        if (!v.isArray()) throw new IllegalArgumentException("'" + snake + "' must be a list of whole numbers.");
        List<Long> out = new ArrayList<>();
        for (JsonNode e : v) {
            if (e.isIntegralNumber()) out.add(e.asLong());
            else if (e.isString() && e.asString().trim().matches("\\d+")) out.add(Long.parseLong(e.asString().trim()));
            else throw new IllegalArgumentException("'" + snake + "' must be a list of whole numbers.");
        }
        return out;
    }

    private static QuestionType type(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return QuestionType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown question type '" + s + "'. Use CHOICE, OPEN or FREE.");
        }
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'exam_date' must be a date like 2026-11-20.");
        }
    }

    // ---------- errors ----------

    /**
     * Runs a service call and turns its {@link ApiException} into a message the agent can act on: the validation
     * details (field → reason) are included, a roster miss is explained in English.
     */
    private static <T> T api(Supplier<T> call) {
        try {
            return call.get();
        } catch (ApiException e) {
            if (e.code() == ErrorCode.NOT_IN_ROSTER) {
                throw new IllegalArgumentException("This student number is not on the course roster. Only students "
                        + "on the roster can have a result (see course_roster / add_student).");
            }
            if (e.code() == ErrorCode.NOT_FOUND) {
                throw new IllegalArgumentException("Exam not found.");
            }
            if (e.code() == ErrorCode.VALIDATION_ERROR && !e.details().isEmpty()) {
                throw new IllegalArgumentException("The exam data is not valid: " + e.details());
            }
            throw e;
        }
    }
}
