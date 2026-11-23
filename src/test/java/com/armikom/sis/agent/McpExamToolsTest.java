package com.armikom.sis.agent;

import com.armikom.sis.core.course.EnrollmentRole;
import com.armikom.sis.support.ApiTestSupport;
import com.armikom.sis.support.PostgresTestcontainer;
import com.armikom.sis.support.TestClockConfig;
import com.armikom.sis.support.TestPeople;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The exam editing tools of the MCP server (docs/MCP.md): McpExamTools through JSON-RPC. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, PostgresTestcontainer.class, TestPeople.Config.class})
class McpExamToolsTest extends ApiTestSupport {

    /** A pool course with its owner, one student, and a chapter with two topics. */
    record Fx(User owner, CourseRef course, long chapter, User student, long topic1, long topic2) {}

    Fx fx() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, true, 3);
        long chapter = chapter(owner, course, 1, "Fundamentals");
        User student = newStudent(course);
        doPut(owner, "/api/pool/courses/" + course.id() + "/staff/chapters/" + chapter,
                Map.of("doc_en", "## 1. One\nOne.\n## 2. Two\nTwo.\n")).andExpect(status().isOk());
        List<Long> topics = jdbc.sql("select id from topic where chapter_id = ? order by number").param(chapter)
                .query(Long.class).list();
        return new Fx(owner, course, chapter, student, topics.get(0), topics.get(1));
    }

    // ---- JSON-RPC helpers (as in McpIntegrationTest) ---------------------------------------------

    String token(User u) throws Exception {
        return body(doPost(u, "/api/mcp-token", null).andExpect(status().isOk())).get("token").asString();
    }

    ResultActions rpc(String token, String method, Object params) throws Exception {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("id", next());
        msg.put("method", method);
        if (params != null) msg.put("params", params);
        var b = post("/mcp").contentType(MediaType.APPLICATION_JSON).content(toJson(msg));
        if (token != null) b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return mvc.perform(b);
    }

    JsonNode toolResult(String token, String tool, Map<String, Object> args) throws Exception {
        JsonNode r = body(rpc(token, "tools/call", Map.of("name", tool, "arguments", args)).andExpect(status().isOk()));
        assertTrue(r.path("error").isMissingNode(), "unexpected error: " + r);
        return r.get("result");
    }

    JsonNode call(String token, String tool, Map<String, Object> args) throws Exception {
        JsonNode r = toolResult(token, tool, args);
        String text = r.get("content").get(0).get("text").asString();
        assertFalse(r.get("isError").asBoolean(), tool + " failed: " + text);
        return json.readTree(text);
    }

    String callError(String token, String tool, Map<String, Object> args) throws Exception {
        JsonNode r = toolResult(token, tool, args);
        String text = r.get("content").get(0).get("text").asString();
        assertTrue(r.get("isError").asBoolean(), tool + " should have failed but returned: " + text);
        return text;
    }

    static Map<String, Object> in(Fx f, Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("course", f.course().code());
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    static List<Long> longs(JsonNode array) {
        List<Long> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asLong()));
        return out;
    }

    static JsonNode questionById(JsonNode exam, long id) {
        for (JsonNode q : exam.get("questions")) if (q.get("id").asLong() == id) return q;
        throw new AssertionError("question " + id + " not in " + exam);
    }

    /** create_exam with a plain CHOICE question (with options and rationales), a D/Y group of two and an OPEN one. */
    JsonNode createExam(Fx f, String staff) throws Exception {
        Map<String, Object> q1 = map("type", "CHOICE", "text", "What is a regression test?", "max_points", 10,
                "choice_labels", "ABCD", "correct_choice", "B", "topic", "Testing", "topic_ids", List.of(f.topic1()),
                "options", List.of(map("letter", "A", "text", "A new test", "rationale", "No: it repeats old tests."),
                        map("letter", "B", "text", "A repeated test", "rationale", "Yes.")));
        Map<String, Object> g1 = map("type", "CHOICE", "text", "Unit tests need a database.", "max_points", 5,
                "choice_labels", "DY", "correct_choice", "Y", "group_ref", "tf");
        Map<String, Object> g2 = map("type", "CHOICE", "text", "Mocks replace collaborators.", "max_points", 5,
                "choice_labels", "DY", "correct_choice", "D", "group_ref", "tf", "sub_label", "ii");
        Map<String, Object> open = map("type", "OPEN", "text", "Explain test doubles.", "max_points", 20,
                "html", "<p>Explain <b>test doubles</b>.</p>");
        return call(staff, "create_exam", in(f, "title", "Midterm", "exam_date", "2026-11-20", "forms", 2,
                "questions", List.of(q1, g1, g2, open),
                "groups", List.of(map("ref", "tf", "text", "True or false?", "html", "<p>T/F</p>"))));
    }

    // ---- tests ----------------------------------------------------------------------------------

    @Test
    void createGetAndEditQuestions() throws Exception {
        Fx f = fx();
        String staff = token(f.owner());

        JsonNode created = createExam(f, staff);
        long examId = created.get("id").asLong();
        assertEquals("Midterm", created.get("title").asString());
        assertEquals("2026-11-20", created.get("examDate").asString());
        assertEquals(2, created.get("forms").asInt());
        assertEquals(40, created.get("totalPoints").asDouble(), 1e-6);

        JsonNode exam = call(staff, "get_exam", Map.of("exam_id", examId));
        JsonNode qs = exam.get("questions");
        assertEquals(4, qs.size());
        assertEquals(List.of("1", "2a", "2ii", "3"), List.of(qs.get(0).get("label").asString(),
                qs.get(1).get("label").asString(), qs.get(2).get("label").asString(), qs.get(3).get("label").asString()));
        assertEquals("ABCD", qs.get(0).get("choiceLabels").asString());
        assertEquals("B", qs.get(0).get("correctChoice").asString());
        assertEquals(2, qs.get(0).get("options").size());
        assertEquals("Yes.", qs.get(0).get("options").get(1).get("rationale").asString());
        assertEquals(List.of(f.topic1()), longs(qs.get(0).get("topicIds")));
        assertEquals("DY", qs.get(1).get("choiceLabels").asString());
        assertEquals("<p>Explain <b>test doubles</b>.</p>", qs.get(3).get("html").asString());
        assertEquals(1, exam.get("groups").size());
        JsonNode group = exam.get("groups").get(0);
        long groupId = group.get("id").asLong();
        assertEquals("True or false?", group.get("text").asString());
        assertEquals("<p>T/F</p>", group.get("html").asString());
        assertEquals(List.of(qs.get(1).get("id").asLong(), qs.get(2).get("id").asLong()), longs(group.get("questionIds")));

        JsonNode list = call(staff, "list_exams", in(f));
        assertEquals(1, list.size());
        assertEquals(examId, list.get(0).get("id").asLong());
        assertEquals(4, list.get(0).get("questionCount").asInt());

        // A stored result: its answers must survive the edits below.
        long q1 = qs.get(0).get("id").asLong();
        long tf1 = qs.get(1).get("id").asLong();
        long openId = qs.get(3).get("id").asLong();
        JsonNode saved = call(staff, "save_exam_result", Map.of("exam_id", examId, "student_no", f.student().studentNo(),
                "form", "A", "note", "late", "answers", List.of(map("question_id", q1, "answer", "B"),
                        map("question_id", tf1, "answer", "Y"), map("question_id", openId, "answer", "Fakes, stubs",
                                "points", 15))));
        assertEquals(30, saved.get("totalPoints").asDouble(), 1e-6);
        assertEquals("A", saved.get("form").asString());

        // add_exam_question appends by default and keeps the group; with position it inserts.
        JsonNode added = call(staff, "add_exam_question", Map.of("exam_id", examId, "type", "FREE",
                "text", "Bonus", "max_points", 2));
        assertEquals(5, added.get("questions").size());
        assertEquals("FREE", added.get("questions").get(4).get("type").asString());
        assertEquals("Bonus", added.get("questions").get(4).get("text").asString());
        assertEquals(groupId, added.get("groups").get(0).get("id").asLong());
        assertEquals(2, added.get("groups").get(0).get("questionIds").size());
        JsonNode inserted = call(staff, "add_exam_question", Map.of("exam_id", examId, "type", "CHOICE",
                "text", "First now", "max_points", 1, "choice_count", 4, "correct_choice", "A", "position", 1));
        assertEquals(6, inserted.get("questions").size());
        assertEquals("First now", inserted.get("questions").get(0).get("text").asString());
        assertEquals("ABCD", inserted.get("questions").get(0).get("choiceLabels").asString());
        assertEquals(q1, inserted.get("questions").get(1).get("id").asLong());
        assertEquals("3a", questionById(inserted, tf1).get("label").asString());
        // Into the existing group, with its ref from get_exam.
        JsonNode grouped = call(staff, "add_exam_question", Map.of("exam_id", examId, "type", "CHOICE",
                "text", "Third statement", "max_points", 5, "choice_labels", "DY", "correct_choice", "D",
                "group_ref", "g" + groupId, "position", 5));
        assertEquals(3, grouped.get("groups").get(0).get("questionIds").size());
        assertTrue(grouped.get("questions").get(4).get("label").asString().startsWith("3"));
        assertEquals(groupId, grouped.get("questions").get(4).get("groupId").asLong());

        // update_exam_question patches only the sent fields.
        JsonNode patched = call(staff, "update_exam_question", Map.of("question_id", q1,
                "explanation", "B: a regression test repeats earlier tests.",
                "topic_ids", List.of(f.topic1(), f.topic2()),
                "options", List.of(map("letter", "A", "text", "A new test", "rationale", "No: new tests are not regression tests."),
                        map("letter", "B", "text", "A repeated test", "rationale", "Yes, exactly."),
                        map("letter", "C", "text", "A load test", "rationale", "No."))));
        JsonNode pq = questionById(patched, q1);
        assertEquals("B: a regression test repeats earlier tests.", pq.get("explanation").asString());
        assertEquals(List.of(f.topic1(), f.topic2()), longs(pq.get("topicIds")));
        assertEquals(3, pq.get("options").size());
        assertEquals("Yes, exactly.", pq.get("options").get(1).get("rationale").asString());
        // ...and keeps the rest.
        assertEquals("What is a regression test?", pq.get("text").asString());
        assertEquals("B", pq.get("correctChoice").asString());
        assertEquals("ABCD", pq.get("choiceLabels").asString());
        assertEquals(10, pq.get("maxPoints").asDouble(), 1e-6);
        assertEquals("Testing", pq.get("topic").asString());
        assertEquals(7, patched.get("questions").size(), "no question was lost");
        assertEquals(groupId, patched.get("groups").get(0).get("id").asLong());
        // A text change on a group member keeps its group and the html of the open question stays.
        JsonNode renamed = call(staff, "update_exam_question", Map.of("question_id", tf1, "text", "Unit tests need a DB."));
        assertEquals(groupId, questionById(renamed, tf1).get("groupId").asLong());
        assertEquals("<p>Explain <b>test doubles</b>.</p>", questionById(renamed, openId).get("html").asString());
        // Camel-case aliases are accepted.
        JsonNode camel = call(staff, "update_exam_question", Map.of("question_id", openId, "maxPoints", 25));
        assertEquals(25, questionById(camel, openId).get("maxPoints").asDouble(), 1e-6);

        // The stored result still has its answers (the key did not change).
        JsonNode results = call(staff, "exam_results", Map.of("exam_id", examId));
        assertEquals(1, results.size());
        assertEquals(3, results.get(0).get("answers").size());
        assertEquals(30, results.get(0).get("totalPoints").asDouble(), 1e-6);
        assertEquals("late", results.get(0).get("note").asString());

        // set_exam_question_topics replaces, [] clears.
        JsonNode topics = call(staff, "set_exam_question_topics", Map.of("question_id", q1, "topic_ids", List.of(f.topic2())));
        assertEquals(List.of(f.topic2()), longs(questionById(topics, q1).get("topicIds")));
        assertEquals("B: a regression test repeats earlier tests.", questionById(topics, q1).get("explanation").asString());
        topics = call(staff, "set_exam_question_topics", Map.of("question_id", q1, "topic_ids", List.of()));
        assertEquals(0, questionById(topics, q1).get("topicIds").size());
        Fx other = fx();
        assertTrue(callError(staff, "set_exam_question_topics", Map.of("question_id", q1,
                "topic_ids", List.of(other.topic1()))).contains("topicIds"));

        // Validation errors name the field.
        assertTrue(callError(staff, "update_exam_question", Map.of("question_id", q1, "correct_choice", "Z"))
                .contains("correctChoice"));
        assertTrue(callError(staff, "add_exam_question", Map.of("exam_id", examId, "type", "ESSAY", "max_points", 1))
                .contains("CHOICE, OPEN or FREE"));

        // remove_exam_question deletes the question and its answers; the rest moves up.
        JsonNode removed = call(staff, "remove_exam_question", Map.of("question_id", q1));
        assertEquals(6, removed.get("questions").size());
        assertTrue(removed.get("questions").valueStream().noneMatch(q -> q.get("id").asLong() == q1));
        assertEquals(0, count("select count(*) from exam_answer where question_id = ?", q1));
        results = call(staff, "exam_results", Map.of("exam_id", examId));
        assertEquals(2, results.get(0).get("answers").size());
        assertEquals(20, results.get(0).get("totalPoints").asDouble(), 1e-6);
        assertTrue(callError(staff, "remove_exam_question", Map.of("question_id", q1)).contains("not found"));
        // The tool is marked destructive.
        for (JsonNode t : body(rpc(staff, "tools/list", null)).get("result").get("tools")) {
            if (t.get("name").asString().equals("remove_exam_question")) {
                assertTrue(t.get("annotations").get("destructiveHint").asBoolean());
            }
        }
    }

    @Test
    void updateExamReplacesTheWholeExam() throws Exception {
        Fx f = fx();
        String staff = token(f.owner());
        JsonNode created = createExam(f, staff);
        long examId = created.get("id").asLong();
        long q1 = created.get("questions").get(0).get("id").asLong();
        long groupId = created.get("groups").get(0).get("id").asLong();
        long tf1 = created.get("questions").get(1).get("id").asLong();

        // Only q1 (by id) and the group with one of its questions are sent back: the others are deleted.
        JsonNode updated = call(staff, "update_exam", Map.of("exam_id", examId, "title", "Midterm v2",
                "questions", List.of(map("id", q1, "type", "CHOICE", "text", "Changed", "max_points", 10,
                                "choice_labels", "ABCD", "correct_choice", "C"),
                        map("id", tf1, "type", "CHOICE", "text", "Kept in group", "max_points", 5,
                                "choice_labels", "DY", "correct_choice", "Y", "group_ref", "g")),
                "groups", List.of(map("id", groupId, "ref", "g", "text", "Still a group"))));
        assertEquals("Midterm v2", updated.get("title").asString());
        assertEquals("2026-11-20", updated.get("examDate").asString(), "the date is kept when not sent");
        assertEquals(2, updated.get("forms").asInt());
        assertEquals(2, updated.get("questions").size());
        assertEquals(q1, updated.get("questions").get(0).get("id").asLong());
        assertEquals("Changed", updated.get("questions").get(0).get("text").asString());
        assertEquals("C", updated.get("questions").get(0).get("correctChoice").asString());
        assertEquals(groupId, updated.get("groups").get(0).get("id").asLong());
        assertEquals("Still a group", updated.get("groups").get(0).get("text").asString());
        assertEquals(2, count("select count(*) from exam_question where exam_id = ?", examId));
    }

    @Test
    void resultsAndAnalysis() throws Exception {
        Fx f = fx();
        String staff = token(f.owner());
        JsonNode created = createExam(f, staff);
        long examId = created.get("id").asLong();
        long q1 = created.get("questions").get(0).get("id").asLong();
        long openId = created.get("questions").get(3).get("id").asLong();
        User second = newStudent(f.course());

        // CHOICE is scored from the key, OPEN takes the points given.
        JsonNode r1 = call(staff, "save_exam_result", Map.of("exam_id", examId, "student_no", f.student().studentNo(),
                "answers", List.of(map("question_id", q1, "answer", "B"), map("question_id", openId, "points", 20))));
        assertEquals(30, r1.get("totalPoints").asDouble(), 1e-6);
        assertEquals(f.student().name(), r1.get("fullName").asString());
        JsonNode r2 = call(staff, "save_exam_result", Map.of("exam_id", examId, "student_no", second.studentNo(),
                "answers", List.of(map("questionId", q1, "answer", "A"), map("question_id", openId, "points", 10))));
        assertEquals(10, r2.get("totalPoints").asDouble(), 1e-6);

        // Only roster students; wrong answers and points are refused with the field name.
        String notOnRoster = callError(staff, "save_exam_result", Map.of("exam_id", examId,
                "student_no", "99999999999", "answers", List.of(map("question_id", q1, "answer", "B"))));
        assertTrue(notOnRoster.contains("not on the course roster"), notOnRoster);
        assertTrue(callError(staff, "save_exam_result", Map.of("exam_id", examId, "student_no", f.student().studentNo(),
                "answers", List.of(map("question_id", q1, "answer", "E")))).contains("answers[0].answer"));
        assertTrue(callError(staff, "save_exam_result", Map.of("exam_id", examId, "student_no", f.student().studentNo(),
                "answers", List.of(map("question_id", openId, "points", 99)))).contains("answers[0].points"));

        JsonNode results = call(staff, "exam_results", Map.of("exam_id", examId));
        assertEquals(2, results.size());
        JsonNode first = results.get(0).get("studentNo").asString().equals(f.student().studentNo()) ? results.get(0)
                : results.get(1);
        assertEquals(2, first.get("answers").size());
        assertEquals(q1, first.get("answers").get(0).get("questionId").asLong());
        assertEquals("B", first.get("answers").get(0).get("answer").asString());
        assertEquals(10, first.get("answers").get(0).get("points").asDouble(), 1e-6);

        JsonNode analysis = call(staff, "exam_analysis", Map.of("exam_id", examId));
        assertEquals(examId, analysis.get("examId").asLong());
        assertEquals(2, analysis.get("resultCount").asInt());
        assertEquals(20, analysis.get("average").asDouble(), 1e-6);
        assertEquals(4, analysis.get("questions").size());
        assertEquals(1, analysis.get("questions").get(0).get("choiceCounts").get("B").asInt());
        assertEquals(2, analysis.get("students").size());

        // Nonexistent exams.
        assertTrue(callError(staff, "exam_results", Map.of("exam_id", 999_999_999)).contains("Exam not found."));
    }

    @Test
    void audienceAndCourseChecks() throws Exception {
        Fx f = fx();
        String staff = token(f.owner());
        JsonNode created = createExam(f, staff);
        long examId = created.get("id").asLong();
        long q1 = created.get("questions").get(0).get("id").asLong();

        // Students do not see the tools at all.
        String st = token(f.student());
        for (String tool : List.of("list_exams", "get_exam", "create_exam", "update_exam", "add_exam_question",
                "update_exam_question", "remove_exam_question", "set_exam_question_topics", "exam_results",
                "save_exam_result", "exam_analysis")) {
            rpc(st, "tools/call", Map.of("name", tool, "arguments", Map.of("exam_id", examId, "question_id", q1)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.error.code").value(-32602))
                    .andExpect(jsonPath("$.error.message").value("Unknown tool: " + tool));
        }

        // Staff of another course see the tools but may not use them on this exam.
        Fx other = fx();
        String ot = token(other.owner());
        assertTrue(callError(ot, "get_exam", Map.of("exam_id", examId)).contains("Only the staff of this course"));
        assertTrue(callError(ot, "update_exam_question", Map.of("question_id", q1, "text", "Hacked"))
                .contains("Only the staff of this course"));
        assertTrue(callError(ot, "remove_exam_question", Map.of("question_id", q1)).contains("Only the staff"));
        assertTrue(callError(ot, "exam_results", Map.of("exam_id", examId)).contains("Only the staff"));
        assertTrue(callError(ot, "save_exam_result", Map.of("exam_id", examId, "student_no", other.student().studentNo()))
                .contains("Only the staff"));
        // Not a member of the course at all: the course argument itself is refused with a hint.
        assertTrue(callError(ot, "list_exams", Map.of("course", f.course().code())).contains("list_courses"));
        assertEquals("What is a regression test?", call(staff, "get_exam", Map.of("exam_id", examId))
                .get("questions").get(0).get("text").asString());
        assertEquals(0, count("select count(*) from exam_result where exam_id = ?", examId));

        // A student who is staff elsewhere is refused too.
        User mixed = newStudent(f.course());
        addEnrollment(other.course(), mixed.id(), EnrollmentRole.INSTRUCTOR);
        assertTrue(callError(token(mixed), "get_exam", Map.of("exam_id", examId)).contains("Only the staff"));
        assertEquals(0, call(token(mixed), "list_exams", Map.of("course", other.course().code())).size());
    }
}
