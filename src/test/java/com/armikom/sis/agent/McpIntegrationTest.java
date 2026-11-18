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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The MCP endpoint (docs/MCP.md): tokens, tool visibility, course selection and the pool rules through tools. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, PostgresTestcontainer.class, TestPeople.Config.class})
class McpIntegrationTest extends ApiTestSupport {

    /** A pool course with its owner, an author with one approved question (key B) and a second student. */
    record Fx(User owner, CourseRef course, long chapter, User author, User student, long question) {}

    Fx fx() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, true, 3);
        long chapter = chapter(owner, course, 1, "Fundamentals");
        User author = newStudent(course);
        User student = newStudent(course);
        long q = approvedQuestion(author, owner, course, chapter, "B");
        return new Fx(owner, course, chapter, author, student, q);
    }

    // ---- JSON-RPC helpers -----------------------------------------------------------------------

    String token(User u) throws Exception {
        JsonNode created = body(doPost(u, "/api/mcp-token", null).andExpect(status().isOk()));
        return created.get("token").asString();
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

    JsonNode result(String token, String method, Object params) throws Exception {
        JsonNode r = body(rpc(token, method, params).andExpect(status().isOk()));
        assertTrue(r.path("error").isMissingNode(), "unexpected error: " + r);
        return r.get("result");
    }

    /** The raw tool result ({content, isError}). */
    JsonNode toolResult(String token, String tool, Map<String, Object> args) throws Exception {
        return result(token, "tools/call", Map.of("name", tool, "arguments", args));
    }

    /** A successful tool call; the JSON the tool returned. */
    JsonNode call(String token, String tool, Map<String, Object> args) throws Exception {
        JsonNode r = toolResult(token, tool, args);
        String text = r.get("content").get(0).get("text").asString();
        assertFalse(r.get("isError").asBoolean(), tool + " failed: " + text);
        return json.readTree(text);
    }

    /** A tool call that must come back as a tool error; the message for the agent. */
    String callError(String token, String tool, Map<String, Object> args) throws Exception {
        JsonNode r = toolResult(token, tool, args);
        String text = r.get("content").get(0).get("text").asString();
        assertTrue(r.get("isError").asBoolean(), tool + " should have failed but returned: " + text);
        return text;
    }

    Set<String> toolNames(String token) throws Exception {
        Set<String> names = new TreeSet<>();
        result(token, "tools/list", null).get("tools").forEach(t -> names.add(t.get("name").asString()));
        return names;
    }

    static final Map<String, Object> NO_ARGS = Map.of();

    // ---- authentication -------------------------------------------------------------------------

    @Test
    void missingOrWrongTokenIs401() throws Exception {
        Fx f = fx();
        rpc(null, "tools/list", null).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"sis\""))
                .andExpect(jsonPath("$.error.code").value(-32001));
        rpc("sis_not-a-real-token", "tools/list", null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(-32001));
        rpc("", "tools/list", null).andExpect(status().isUnauthorized());
        // The session cookie is not accepted on /mcp, and a Basic header is not a bearer token.
        mvc.perform(post("/mcp").with(f.student().as()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp").header(HttpHeaders.AUTHORIZATION, "Basic abc").contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized());
        // A user's own token works, without CSRF header or cookie.
        String t = token(f.student());
        rpc(t, "ping", null).andExpect(status().isOk());
    }

    @Test
    void protocolBasics() throws Exception {
        Fx f = fx();
        String t = token(f.student());
        mvc.perform(get("/mcp")).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "POST"));
        // Bad bodies.
        mvc.perform(post("/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(-32700));
        mvc.perform(post("/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(-32600));
        // A notification gets 202 and no body.
        mvc.perform(post("/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted());
        rpc(t, "no/such/method", null).andExpect(status().isOk()).andExpect(jsonPath("$.error.code").value(-32601));
        rpc(t, "tools/call", Map.of("name", "no_such_tool", "arguments", NO_ARGS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602));

        // initialize echoes a known protocol version, falls back to the newest otherwise.
        assertEquals("2025-03-26", result(t, "initialize", Map.of("protocolVersion", "2025-03-26"))
                .get("protocolVersion").asString());
        JsonNode init = result(t, "initialize", Map.of("protocolVersion", "1999-01-01"));
        assertEquals("2025-11-25", init.get("protocolVersion").asString());
        assertEquals("sis", init.get("serverInfo").get("name").asString());
        assertTrue(init.get("capabilities").has("tools"));

        // Role specific instructions: students and staff are told different rules.
        String student = init.get("instructions").asString();
        assertTrue(student.contains("as the student \"" + f.student().nickname() + "\""), student);
        assertFalse(student.contains("staff member"), student);
        assertFalse(student.contains(f.student().name()), "the student's real name stays out");
        String staff = result(token(f.owner()), "initialize", Map.of("protocolVersion", "2025-06-18"))
                .get("instructions").asString();
        assertTrue(staff.contains("as the staff member \"" + f.owner().name() + "\""), staff);
        assertFalse(staff.contains("as the student"), staff);
    }

    @Test
    void tokenCreateReplaceDelete() throws Exception {
        Fx f = fx();
        User s = f.student();
        doGet(null, "/api/mcp-token").andExpect(status().isUnauthorized());
        doGet(s, "/api/mcp-token").andExpect(status().isOk())
                .andExpect(jsonPath("$.has_token").value(false))
                .andExpect(jsonPath("$.endpoint").value(org.hamcrest.Matchers.endsWith("/mcp")));

        JsonNode created = body(doPost(s, "/api/mcp-token", null).andExpect(status().isOk()));
        String first = created.get("token").asString();
        assertTrue(first.startsWith("sis_"), first);
        assertEquals(4 + 43, first.length());
        assertTrue(created.get("has_token").asBoolean());
        assertEquals(first.substring(0, 7) + "…" + first.substring(first.length() - 4), created.get("hint").asString());
        // Only the hash is stored, never the token.
        assertEquals(0, count("select count(*) from mcp_token where token_hash = ? or hint = ?", first, first));
        assertEquals(1, count("select count(*) from mcp_token where person_id = ?", s.id()));

        JsonNode info = body(doGet(s, "/api/mcp-token").andExpect(status().isOk()));
        assertTrue(info.get("has_token").asBoolean());
        assertEquals(created.get("hint").asString(), info.get("hint").asString());
        assertFalse(info.has("token"), "the token is shown once");
        assertTrue(info.get("last_used_at").isNull());

        rpc(first, "ping", null).andExpect(status().isOk());
        assertFalse(body(doGet(s, "/api/mcp-token")).get("last_used_at").isNull(), "use is recorded");

        // A new token replaces the old one.
        String second = body(doPost(s, "/api/mcp-token", null).andExpect(status().isOk())).get("token").asString();
        assertNotEquals(first, second);
        assertEquals(1, count("select count(*) from mcp_token where person_id = ?", s.id()));
        rpc(first, "ping", null).andExpect(status().isUnauthorized());
        rpc(second, "ping", null).andExpect(status().isOk());

        // Delete.
        doDelete(s, "/api/mcp-token").andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        rpc(second, "ping", null).andExpect(status().isUnauthorized());
        doGet(s, "/api/mcp-token").andExpect(jsonPath("$.has_token").value(false));
        doDelete(s, "/api/mcp-token").andExpect(status().isOk());

        // Tokens are personal: another person's token does not touch mine.
        String otherToken = token(f.author());
        String mine = token(s);
        doDelete(f.author(), "/api/mcp-token").andExpect(status().isOk());
        rpc(otherToken, "ping", null).andExpect(status().isUnauthorized());
        rpc(mine, "ping", null).andExpect(status().isOk());
    }

    @Test
    void tokenStopsWorkingForADeactivatedPerson() throws Exception {
        Fx f = fx();
        String t = token(f.student());
        rpc(t, "ping", null).andExpect(status().isOk());
        jdbc.sql("update person set active = false where id = ?").param(f.student().id()).update();
        try {
            rpc(t, "ping", null).andExpect(status().isUnauthorized());
        } finally {
            jdbc.sql("update person set active = true where id = ?").param(f.student().id()).update();
        }
        rpc(t, "ping", null).andExpect(status().isOk());
    }

    // ---- tools/list per audience ----------------------------------------------------------------

    @Test
    void toolListDiffersForStudentAndStaff() throws Exception {
        Fx f = fx();
        Set<String> student = toolNames(token(f.student()));
        Set<String> staff = toolNames(token(f.owner()));

        Set<String> shared = Set.of("list_courses", "whoami", "list_chapters", "read_chapter", "list_questions",
                "get_question", "get_comments", "post_comment");
        Set<String> studentOnly = Set.of("get_progress", "next_question", "answer_question", "practice_answer",
                "submit_question", "flag_question", "my_questions", "update_study_state", "log_study_session");
        Set<String> staffOnly = Set.of("class_overview", "student_detail", "decide_question", "edit_question",
                "list_flags", "resolve_flag", "hide_comment", "set_chapter_open", "create_exam", "get_exam",
                "list_exams", "question_stats", "import_students", "question_counts");
        assertTrue(student.containsAll(shared));
        assertTrue(staff.containsAll(shared));
        assertTrue(student.containsAll(studentOnly));
        assertTrue(staff.containsAll(staffOnly));
        for (String t : staffOnly) assertFalse(student.contains(t), "student must not see " + t);
        for (String t : studentOnly) assertFalse(staff.contains(t), "staff must not see " + t);
        assertNotEquals(student, staff);

        // A tool is described with a schema that has the optional course argument.
        JsonNode tools = result(token(f.student()), "tools/list", null).get("tools");
        for (JsonNode t : tools) {
            assertTrue(t.get("inputSchema").get("properties").has("course"), t.get("name").asString());
            if (t.get("name").asString().equals("next_question")) {
                assertTrue(t.get("annotations").get("readOnlyHint").asBoolean());
            }
            if (t.get("name").asString().equals("answer_question")) {
                assertFalse(t.get("annotations").get("readOnlyHint").asBoolean());
            }
        }

        // Staff in one course and student in another see both kinds.
        User mixed = newStudent(f.course());
        Fx other = fx();
        addEnrollment(other.course(), mixed.id(), EnrollmentRole.INSTRUCTOR);
        Set<String> both = toolNames(token(mixed));
        assertTrue(both.containsAll(studentOnly));
        assertTrue(both.containsAll(staffOnly));

        // A person with no pool course sees only the shared tools' kind ("ALL"), no student or staff ones.
        User nobody = newStaff();   // staff of a seed course that has no pool
        Set<String> none = toolNames(token(nobody));
        for (String t : studentOnly) assertFalse(none.contains(t));
        for (String t : staffOnly) assertFalse(none.contains(t));
        assertTrue(none.contains("list_courses"));
    }

    // ---- courses --------------------------------------------------------------------------------

    @Test
    void listCoursesShowsOnlyMyActivePoolCourses() throws Exception {
        Fx f = fx();
        // A course without the pool, an archived pool course and a pool course I am not in.
        CourseRef noPool = newCourse(f.owner(), false, 2);
        enroll(noPool, f.student());
        CourseRef archived = newCourse(f.owner(), true, 2);
        enroll(archived, f.student());
        doPost(f.owner(), "/api/courses/" + archived.id() + "/archive", null).andExpect(status().isOk());
        fx();   // someone else's course

        JsonNode courses = call(token(f.student()), "list_courses", NO_ARGS);
        assertEquals(1, courses.size(), courses.toString());
        assertEquals(f.course().id(), courses.get(0).get("id").asLong());
        assertEquals(f.course().code(), courses.get(0).get("code").asString());
        assertEquals("STUDENT", courses.get(0).get("role").asString());
        assertFalse(courses.get(0).get("staff").asBoolean());

        JsonNode staffCourses = call(token(f.owner()), "list_courses", NO_ARGS);
        assertEquals(1, staffCourses.size());
        assertEquals("OWNER", staffCourses.get(0).get("role").asString());
        assertTrue(staffCourses.get(0).get("staff").asBoolean());
    }

    @Test
    void whoamiHidesTheRealNameFromStudents() throws Exception {
        Fx f = fx();
        JsonNode me = call(token(f.student()), "whoami", NO_ARGS);
        assertEquals(f.student().nickname(), me.get("nickname").asString());
        assertFalse(me.has("full_name"));
        assertEquals("STUDENT", me.get("role").asString());
        assertEquals(3, me.get("questions_per_chapter").asInt());
        JsonNode staff = call(token(f.owner()), "whoami", NO_ARGS);
        assertEquals(f.owner().name(), staff.get("full_name").asString());
    }

    @Test
    void courseArgumentIsOptionalWithOneCourseAndRequiredWithTwo() throws Exception {
        Fx a = fx();
        String t = token(a.student());
        // One pool course: it is the default.
        JsonNode chapters = call(t, "list_chapters", NO_ARGS);
        assertEquals(1, chapters.size());
        assertEquals(1, chapters.get(0).get("number").asInt());

        // The same student joins a second pool course.
        User ownerB = newStaff();
        CourseRef b = newCourse(ownerB, true, 2);
        chapter(ownerB, b, 1, "Other one");
        chapter(ownerB, b, 2, "Other two");
        enroll(b, a.student());

        String error = callError(t, "list_chapters", NO_ARGS);
        assertTrue(error.contains("more than one course"), error);
        assertTrue(error.contains(a.course().code()) && error.contains(b.code()), error);
        assertTrue(error.contains("'course'"), error);
        assertEquals(2, call(t, "list_courses", NO_ARGS).size());

        // Explicit course: by code (spaces and case ignored) and by id (number or string).
        assertEquals(1, call(t, "list_chapters", Map.of("course", a.course().code())).size());
        assertEquals(2, call(t, "list_chapters", Map.of("course", b.code().toLowerCase(java.util.Locale.ROOT))).size());
        String spaced = b.code().substring(0, 1).toLowerCase(java.util.Locale.ROOT) + " " + b.code().substring(1);
        assertEquals(2, call(t, "list_chapters", Map.of("course", spaced)).size());
        assertEquals(2, call(t, "list_chapters", Map.of("course", String.valueOf(b.id()))).size());
        assertEquals(2, call(t, "list_chapters", Map.of("course", b.id())).size());
        assertEquals(1, call(t, "list_chapters", Map.of("course", a.course().id())).size());

        // Unknown or foreign courses are refused with a hint.
        String unknown = callError(t, "list_chapters", Map.of("course", "NOPE999"));
        assertTrue(unknown.contains("NOPE999") && unknown.contains("list_courses"), unknown);
        Fx stranger = fx();
        String foreign = callError(t, "list_chapters", Map.of("course", String.valueOf(stranger.course().id())));
        assertTrue(foreign.contains("list_courses"), foreign);
        // whoami does not fail with two courses; it explains.
        JsonNode me = call(t, "whoami", NO_ARGS);
        assertTrue(me.has("course_note"), me.toString());
        assertEquals(b.code(), call(t, "whoami", Map.of("course", b.code())).get("course").asString());
    }

    @Test
    void personWithoutPoolCourseGetsAClearError() throws Exception {
        User nobody = newStaff();
        String error = callError(token(nobody), "list_chapters", NO_ARGS);
        assertTrue(error.contains("not in any course"), error);
        assertEquals(0, call(token(nobody), "list_courses", NO_ARGS).size());
    }

    // ---- attempt first and first answer ---------------------------------------------------------

    @Test
    void nextQuestionAndAnswerQuestionFollowTheAttemptFirstRules() throws Exception {
        Fx f = fx();
        String t = token(f.student());
        String authorToken = token(f.author());

        // A pending question is never handed out, the author never gets his own question.
        long pending = submitQuestion(f.author(), f.chapter(), "C");
        JsonNode next = call(t, "next_question", NO_ARGS);
        assertEquals(f.question(), next.get("id").asLong());
        assertEquals(0, next.get("left_after_this").asInt());
        for (String hidden : List.of("correct", "rationale_a", "rationale_b", "rationale_c", "rationale_d",
                "reference", "explanation")) {
            assertFalse(next.has(hidden), hidden + " must not be in next_question: " + next);
        }
        assertTrue(next.has("option_a") && next.has("stem"));
        assertFalse(next.get("revealed").asBoolean());
        assertTrue(call(authorToken, "next_question", NO_ARGS).get("done").asBoolean());

        // get_question and get_comments obey the same rule.
        JsonNode viewed = call(t, "get_question", Map.of("question_id", f.question()));
        assertFalse(viewed.has("correct"));
        assertTrue(viewed.has("note_for_agent"));
        assertTrue(callError(t, "get_comments", Map.of("question_id", f.question())).contains("Answer the question first."));
        assertTrue(callError(t, "post_comment", Map.of("question_id", f.question(), "body", "Early comment"))
                .contains("Answer the question first."));
        assertTrue(callError(t, "flag_question", Map.of("question_id", f.question(), "reason", "Early flag, bad"))
                .contains("Answer the question first."));
        assertTrue(callError(t, "get_question", Map.of("question_id", pending)).contains("Question not found."));
        assertTrue(callError(t, "answer_question", Map.of("question_id", pending, "selected", "A"))
                .contains("Question not found."));
        // The choice must be a letter.
        assertTrue(callError(t, "answer_question", Map.of("question_id", f.question(), "selected", "E"))
                .contains("A, B, C or D"));
        assertEquals(0, count("select count(*) from question_attempt where person_id = ?", f.student().id()));

        // Answer: the key and the reasons come back with the answer.
        JsonNode answered = call(t, "answer_question", Map.of("question_id", f.question(), "selected", "A"));
        assertEquals("B", answered.get("correct").asString());
        assertFalse(answered.get("answer_was_correct").asBoolean());
        assertEquals("A", answered.get("my_selected").asString());
        assertTrue(answered.get("revealed").asBoolean());
        assertTrue(answered.has("rationale_a"));

        // Only the first answer counts: answering again is refused and nothing changes.
        String again = callError(t, "answer_question", Map.of("question_id", f.question(), "selected", "B"));
        assertTrue(again.contains("already answered") && again.contains("first answer: A"), again);
        assertEquals("A", call(t, "get_question", Map.of("question_id", f.question())).get("my_selected").asString());
        assertEquals(1, count("select count(*) from question_attempt where person_id = ?", f.student().id()));
        // The same through the web API, the first answer still stands.
        assertEquals("A", attempt(f.student(), f.question(), "B").get("my_selected").asString());

        // Practice does not change the first answer either.
        JsonNode practice = call(t, "practice_answer", Map.of("question_id", f.question(), "selected", "B"));
        assertNotNull(practice);
        assertEquals("A", call(t, "get_question", Map.of("question_id", f.question())).get("my_selected").asString());
        assertFalse(jdbc.sql("select correct from question_attempt where person_id = ? and question_id = ?")
                .param(f.student().id()).param(f.question()).query(Boolean.class).single());

        // The discussion is open now.
        call(t, "post_comment", Map.of("question_id", f.question(), "body", "I mixed up A and B"));
        assertEquals(1, call(t, "get_comments", Map.of("question_id", f.question())).size());
        // Nothing new is left to answer.
        JsonNode done = call(t, "next_question", NO_ARGS);
        assertTrue(done.get("done").asBoolean(), done.toString());
        // ...and it shows up for review.
        assertEquals(f.question(), call(t, "next_review_question", NO_ARGS).get("id").asLong());
    }

    @Test
    void authorsAndStaffCannotAnswerThroughTools() throws Exception {
        Fx f = fx();
        String error = callError(token(f.author()), "answer_question", Map.of("question_id", f.question(),
                "selected", "B"));
        assertTrue(error.contains("You cannot answer this question."), error);
        assertEquals(0, count("select count(*) from question_attempt where question_id = ?", f.question()));

        // A staff member who is a student elsewhere is still refused for this course's question.
        User mixed = newStudent(f.course());
        Fx other = fx();
        addEnrollment(other.course(), mixed.id(), EnrollmentRole.INSTRUCTOR);
        // (mixed is a student of f.course, so he may answer there, but staff of `other` may not answer in `other`.)
        String refused = callError(token(mixed), "answer_question", Map.of("question_id", other.question(),
                "selected", "A"));
        assertTrue(refused.contains("is for students"), refused);
        assertEquals(0, count("select count(*) from question_attempt where question_id = ?", other.question()));
        assertEquals("A", call(token(mixed), "answer_question", Map.of("question_id", f.question(), "selected", "A"))
                .get("my_selected").asString());
    }

    @Test
    void submitQuestionThroughToolFollowsTheWebRules() throws Exception {
        Fx f = fx();
        String t = token(f.student());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("chapter", 1);
        args.put("stem", "Which statement about regression testing is correct?");
        for (String l : List.of("a", "b", "c", "d")) {
            args.put("option_" + l, "Statement " + l);
            args.put("rationale_" + l, "This is the reason for option " + l + ".");
        }
        args.put("correct", "C");
        args.put("reference", "Section 2.3");
        Map<String, Object> tooShort = new LinkedHashMap<>(args);
        tooShort.put("stem", "Short");
        assertTrue(callError(t, "submit_question", tooShort).contains("too short"));
        call(t, "submit_question", args);
        assertEquals(1, call(t, "my_questions", NO_ARGS).size());
        // The limit of 3 per chapter applies as on the web (the fixture's author has one approved already).
        Map<String, Object> second = new LinkedHashMap<>(args);
        second.put("stem", "Another question about regression testing, also long enough?");
        for (String l : List.of("a", "b", "c", "d")) second.put("option_" + l, "Other statement " + l);
        call(t, "submit_question", second);
        Map<String, Object> third = new LinkedHashMap<>(args);
        third.put("stem", "A third question about regression testing and its purpose?");
        for (String l : List.of("a", "b", "c", "d")) third.put("option_" + l, "Third statement " + l);
        call(t, "submit_question", third);
        Map<String, Object> fourth = new LinkedHashMap<>(args);
        fourth.put("stem", "A fourth question about regression testing and its scope?");
        for (String l : List.of("a", "b", "c", "d")) fourth.put("option_" + l, "Fourth statement " + l);
        assertTrue(callError(t, "submit_question", fourth).contains("You already have 3 questions in this chapter."));
        // Unknown chapter number.
        Map<String, Object> badChapter = new LinkedHashMap<>(args);
        badChapter.put("chapter", 42);
        assertTrue(callError(t, "submit_question", badChapter).contains("There is no chapter 42"));
    }

    // ---- teacher tools --------------------------------------------------------------------------

    @Test
    void studentCannotCallTeacherTools() throws Exception {
        Fx f = fx();
        long pending = submitQuestion(f.student(), f.chapter(), "A");
        String t = token(f.student());

        // The tool does not exist for a student: JSON-RPC "unknown tool", with and without a course.
        for (String tool : List.of("decide_question", "class_overview", "hide_comment", "list_flags", "create_exam",
                "edit_question", "import_students")) {
            rpc(t, "tools/call", Map.of("name", tool, "arguments", Map.of("question_id", pending, "action", "APPROVE")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.error.code").value(-32602))
                    .andExpect(jsonPath("$.error.message").value("Unknown tool: " + tool));
        }
        assertEquals("PENDING", getQuestion(f.student(), pending).get("status").asString());

        // A person who is staff in another course sees the tool but may not use it on this course.
        User mixed = newStudent(f.course());
        Fx other = fx();
        addEnrollment(other.course(), mixed.id(), EnrollmentRole.INSTRUCTOR);
        String mt = token(mixed);
        String refused = callError(mt, "decide_question", Map.of("question_id", pending, "action", "APPROVE"));
        assertTrue(refused.contains("Only the staff of this course can use decide_question."), refused);
        assertEquals("PENDING", getQuestion(f.student(), pending).get("status").asString());
        String viaCourse = callError(mt, "class_overview", Map.of("course", f.course().code()));
        assertTrue(viaCourse.contains("Only the staff of " + f.course().code()), viaCourse);
        // ...while the same tool works for the course where he is staff.
        assertEquals(2, call(mt, "class_overview", Map.of("course", other.course().code())).size());
    }

    @Test
    void staffToolsReviewAndDecide() throws Exception {
        Fx f = fx();
        long pending = submitQuestion(f.student(), f.chapter(), "D");
        String t = token(f.owner());

        JsonNode review = call(t, "list_questions_for_review", NO_ARGS);
        assertEquals(1, review.size());
        // Lower-case input is understood whatever the server's locale is (Turkish dotted i).
        assertEquals(1, call(t, "list_questions_for_review", Map.of("status", "pending")).size());
        assertEquals(pending, review.get(0).get("id").asLong());
        assertEquals("D", review.get(0).get("correct").asString());
        assertEquals(f.student().name(), review.get(0).get("author_name").asString());

        // The note is required for REVISE, as on the web.
        assertTrue(callError(t, "decide_question", Map.of("question_id", pending, "action", "REVISE"))
                .contains("Write a note so the student knows what to change."));
        assertEquals("PENDING", getQuestion(f.student(), pending).get("status").asString());
        call(t, "decide_question", Map.of("question_id", pending, "action", "revise", "note", "Please reword it."));
        assertEquals("NEEDS_REVISION", getQuestion(f.student(), pending).get("status").asString());
        call(t, "decide_question", Map.of("question_id", pending, "action", "APPROVE"));
        assertEquals("APPROVED", getQuestion(f.author(), pending).get("status").asString());
        assertEquals(2, call(t, "question_counts", NO_ARGS).get(0).get("approved").asInt());

        // Staff of the course see the participation table, with real names.
        JsonNode overview = call(t, "class_overview", NO_ARGS);
        assertEquals(2, overview.size());
        assertTrue(overview.toString().contains(f.author().name()));

        // A person of another course is not a member: even a shared tool refuses.
        Fx stranger = fx();
        String st = token(stranger.student());
        assertTrue(callError(st, "get_question", Map.of("question_id", pending)).contains("You are not in"));
        assertTrue(callError(token(stranger.owner()), "decide_question", Map.of("question_id", pending,
                "action", "REJECT", "note", "Not mine")).contains("Only the staff"));
        assertEquals("APPROVED", getQuestion(f.author(), pending).get("status").asString());
    }

    @Test
    void chapterTextIsReadableAsResource() throws Exception {
        Fx f = fx();
        doPut(f.owner(), "/api/pool/courses/" + f.course().id() + "/staff/chapters/" + f.chapter(),
                Map.of("doc_en", "# English text", "doc_tr", "# Turkce metin")).andExpect(status().isOk());
        String t = token(f.student());
        String uri = "sis://courses/" + f.course().id() + "/chapters/1/en";
        JsonNode resources = result(t, "resources/list", null).get("resources");
        assertTrue(resources.valueStream().anyMatch(r -> uri.equals(r.get("uri").asString())), resources.toString());
        JsonNode read = result(t, "resources/read", Map.of("uri", uri)).get("contents").get(0);
        assertEquals("# English text", read.get("text").asString());
        // A course the person is not in is "not found".
        Fx other = fx();
        rpc(t, "resources/read", Map.of("uri", "sis://courses/" + other.course().id() + "/chapters/1/en"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.error.code").value(-32002));
    }

    @Test
    void topicAndExamStudyTools() throws Exception {
        Fx f = fx();
        doPut(f.owner(), "/api/pool/courses/" + f.course().id() + "/staff/chapters/" + f.chapter(),
                Map.of("doc_en", "## 1. One\nOne en.\n## 2. Two\nTwo en.\n", "doc_tr", "## 1. Bir\nBir tr.\n"))
                .andExpect(status().isOk());
        String staff = token(f.owner());
        String st = token(f.student());

        JsonNode synced = call(staff, "sync_topics", NO_ARGS);
        assertEquals(1, synced.get(0).get("chapter").asInt());
        assertEquals(2, synced.get(0).get("topics").size());
        long t1 = synced.get(0).get("topics").get(0).get("id").asLong();
        long t2 = synced.get(0).get("topics").get(1).get("id").asLong();
        JsonNode set = call(staff, "set_question_topics", Map.of("question_id", f.question(), "topic_ids", List.of(t1)));
        assertEquals(t1, set.get("topics").get(0).get("id").asLong());

        assertEquals(2, call(st, "list_topics", NO_ARGS).size());
        assertEquals(2, call(st, "list_topics", Map.of("chapter", 1)).size());
        JsonNode read = call(st, "read_topic", Map.of("topic", t1, "lang", "en"));
        assertEquals("## 1. One\nOne en.", read.get("text").asString());
        assertEquals("Bir", read.get("title_tr").asString());
        JsonNode fallback = call(st, "read_topic", Map.of("chapter", 1, "number", 2, "lang", "tr"));
        assertEquals("en", fallback.get("language").asString());
        assertEquals("## 2. Two\nTwo en.", fallback.get("text").asString());
        assertTrue(fallback.has("note"));
        assertTrue(callError(st, "read_topic", Map.of("chapter", 1, "number", 9)).contains("no topic 9"));

        // Practise by topic; a wrong first answer makes the topic weak.
        assertTrue(call(st, "next_question", Map.of("topic", t2)).get("done").asBoolean());
        assertEquals(f.question(), call(st, "next_question", Map.of("topic", t1)).get("id").asLong());
        call(st, "answer_question", Map.of("question_id", f.question(), "selected", "A"));
        assertEquals(f.question(), call(st, "next_review_question", Map.of("topic", t1)).get("id").asLong());
        Fx other = fx();
        long foreignTopic = jdbc.sql("insert into topic (chapter_id, number) values (:c, 1) returning id")
                .param("c", other.chapter()).query(Long.class).single();
        assertTrue(callError(st, "next_question", Map.of("topic", foreignTopic)).contains("no topic"));
        JsonNode weak = call(st, "review_topics", NO_ARGS);
        assertEquals(1, weak.size());
        assertEquals(t1, weak.get(0).get("topic_id").asLong());
        assertEquals(1, weak.get(0).get("pool_wrong").asInt());
        assertEquals(0, weak.get(0).get("exam_missed").asInt());

        // Students tag their own questions with topics.
        Map<String, Object> mine = new LinkedHashMap<>(questionBody(f.chapter(), "A"));
        mine.remove("chapter_id");
        mine.put("chapter", 1);
        mine.put("topic_ids", List.of(t2));
        long written = call(st, "submit_question", mine).get("id").asLong();
        assertEquals(t2, getQuestion(f.student(), written).get("topics").get(0).get("id").asLong());
        call(st, "revise_question", Map.of("question_id", written, "topic_ids", List.of(t1, t2)));
        assertEquals(2, getQuestion(f.student(), written).get("topics").size());

        // A published exam with the pool question (topic via the pool link), answered wrong.
        Map<String, Object> eq = new LinkedHashMap<>();
        eq.put("type", "CHOICE");
        eq.put("maxPoints", 1);
        eq.put("choiceCount", 4);
        eq.put("correctChoice", "B");
        eq.put("poolQuestionId", f.question());
        JsonNode exam = body(doPost(f.owner(), "/api/courses/" + f.course().id() + "/exams",
                Map.of("title", "Quiz 1", "questions", List.of(eq))).andExpect(status().isCreated()));
        long examId = exam.get("id").asLong();
        Map<String, Object> ans = new LinkedHashMap<>();
        ans.put("questionId", exam.get("questions").get(0).get("id").asLong());
        ans.put("answer", "C");
        doPut(f.owner(), "/api/exams/" + examId + "/results/" + f.student().studentNo(), Map.of("answers", List.of(ans)))
                .andExpect(status().isOk());
        assertEquals(0, call(st, "my_exam_results", NO_ARGS).size());
        assertTrue(call(staff, "publish_exam", Map.of("exam_id", examId)).get("published").asBoolean());
        assertEquals(1, call(st, "my_exam_results", NO_ARGS).size());
        JsonNode detail = call(st, "my_exam_results", Map.of("exam_id", examId));
        assertFalse(detail.get("questions").get(0).get("correct").asBoolean());
        assertEquals(1, detail.get("questions").get(0).get("stats").get("answered").asInt());
        assertTrue(detail.get("questions").get(0).has("options"));
        assertEquals(1, detail.get("stats").get("participants").asInt());
        assertEquals(t1, detail.get("reviewTopics").get(0).get("topic").get("id").asLong());

        weak = call(st, "review_topics", NO_ARGS);
        assertEquals(1, weak.get(0).get("exam_missed").asInt());
        assertEquals(1, weak.get(0).get("exam_total").asInt());
        assertEquals(t1, call(st, "get_progress", NO_ARGS).get("weak_topics").get(0).get("topic_id").asLong());

        JsonNode stats = call(staff, "topic_stats", Map.of("chapter", 1));
        JsonNode s1 = stats.get(0);
        assertEquals(t1, s1.get("topic_id").asLong());
        assertEquals(1, s1.get("approved_questions").asInt());
        assertEquals(2, s1.get("linked_questions").asInt());
        assertEquals(1, s1.get("first_answers").asInt());
        assertEquals(0, s1.get("first_answer_correct_pct").asDouble(), 1e-6);
        assertEquals(1, s1.get("exam_answers").asInt());
        assertEquals(0, s1.get("exam_correct_pct").asDouble(), 1e-6);

        String text = result(st, "prompts/get", Map.of("name", "review_exam", "arguments", Map.of("exam", examId)))
                .get("messages").get(0).get("content").get("text").asString();
        assertTrue(text.contains("exam_id=" + examId), text);
        assertTrue(text.contains("read_topic"));

        assertFalse(call(staff, "publish_exam", Map.of("exam_id", examId, "published", false))
                .get("published").asBoolean());
        assertEquals(0, call(st, "my_exam_results", NO_ARGS).size());
        assertTrue(callError(st, "my_exam_results", Map.of("exam_id", examId)).length() > 0);
        // Students do not see the teacher tool at all.
        rpc(st, "tools/call", Map.of("name", "publish_exam", "arguments", Map.of("exam_id", examId)))
                .andExpect(jsonPath("$.error.code").value(-32602));
    }
}
