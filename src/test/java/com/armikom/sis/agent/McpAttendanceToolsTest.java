package com.armikom.sis.agent;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The attendance tools of the MCP server (docs/MCP-attendance.md) through JSON-RPC. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, PostgresTestcontainer.class, TestPeople.Config.class})
class McpAttendanceToolsTest extends ApiTestSupport {

    /** A course with attendance (no pool), its owner and two roster students. */
    record Fx(User owner, CourseRef course, User s1, User s2) {}

    Fx fx() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, false, 2);
        return new Fx(owner, course, newStudent(course), newStudent(course));
    }

    // ---- JSON-RPC helpers -----------------------------------------------------------------------

    String token(User u) throws Exception {
        return body(doPost(u, "/api/mcp-token", null).andExpect(status().isOk())).get("token").asString();
    }

    ResultActions rpc(String token, String method, Object params) throws Exception {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("id", next());
        msg.put("method", method);
        if (params != null) msg.put("params", params);
        return mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(toJson(msg))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    JsonNode toolResult(String token, String tool, Map<String, Object> args) throws Exception {
        JsonNode r = body(rpc(token, "tools/call", Map.of("name", tool, "arguments", args))
                .andExpect(status().isOk()));
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

    /** Arguments with the course of the fixture (staff of newStaff also own a seed course). */
    static Map<String, Object> in(Fx f, Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("course", f.course().code());
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    // ---- tests ----------------------------------------------------------------------------------

    @Test
    void sessionLifecycleRecordsAndReports() throws Exception {
        Fx f = fx();
        String t = token(f.owner());

        // Create: DRAFT, no join link yet, the session key is not in the answer.
        JsonNode created = call(t, "create_session", in(f, "title", "Week 1 lecture", "duration_minutes", 30,
                "required_scans", 3));
        long sessionId = created.get("id").asLong();
        assertEquals("DRAFT", created.get("status").asString());
        assertEquals(30, created.get("durationMinutes").asInt());
        assertEquals(3, created.get("requiredScans").asInt());
        assertTrue(created.get("joinUrl").isNull());
        assertFalse(created.has("sessionKeyB64"));
        assertTrue(callError(t, "create_session", in(f, "title", "Nowhere", "classroom", "No such room"))
                .contains("classroom"));

        // List and detail.
        JsonNode list = call(t, "list_sessions", in(f));
        assertEquals(1, list.size());
        assertEquals(sessionId, list.get(0).get("id").asLong());
        assertEquals(0, call(t, "list_sessions", in(f, "status", "OPEN")).size());
        assertEquals("Week 1 lecture", call(t, "get_session", in(f, "session_id", sessionId)).get("title").asString());

        // State errors are tool errors, not server errors.
        assertFalse(callError(t, "extend_session", in(f, "session_id", sessionId, "minutes", 5)).isBlank());
        assertFalse(callError(t, "reopen_session", in(f, "session_id", sessionId)).isBlank());
        assertTrue(callError(t, "get_session", in(f, "session_id", 999_999_999)).contains("not found"));

        // Open.
        JsonNode opened = call(t, "open_session", in(f, "session_id", sessionId));
        assertEquals("OPEN", opened.get("status").asString());
        assertTrue(opened.get("joinUrl").asString().endsWith("/c/" + opened.get("code").asString()));
        assertEquals(1, call(t, "list_sessions", in(f, "status", "OPEN")).size());
        JsonNode extended = call(t, "extend_session", in(f, "session_id", sessionId, "minutes", 10));
        assertEquals("OPEN", extended.get("status").asString());

        // Manual attendance: a roster student is added, an unknown number is reported, a repeat is skipped.
        JsonNode bulk = call(t, "add_attendance", in(f, "session_id", sessionId,
                "student_numbers", List.of(f.s1().studentNo(), "99999999"), "note", "late slip"));
        assertEquals(1, bulk.get("added").asInt());
        assertEquals(0, bulk.get("skipped").asInt());
        assertEquals("99999999", bulk.get("notInRoster").get(0).asString());
        assertEquals(1, call(t, "add_attendance", in(f, "session_id", sessionId,
                "student_numbers", List.of(f.s1().studentNo()))).get("skipped").asInt());

        JsonNode records = call(t, "session_attendances", in(f, "session_id", sessionId));
        assertEquals(1, records.size());
        JsonNode rec = records.get(0);
        long attId = rec.get("id").asLong();
        assertEquals(f.s1().studentNo(), rec.get("studentNo").asString());
        assertEquals("MANUAL", rec.get("source").asString());
        assertEquals("APPROVED", rec.get("teacherDecision").asString());
        assertEquals("late slip", rec.get("teacherNote").asString());
        assertTrue(rec.get("present").asBoolean());
        assertEquals(0, call(t, "session_attendances", in(f, "session_id", sessionId, "only", "PENDING")).size());
        assertEquals(1, call(t, "session_attendances", in(f, "session_id", sessionId, "only", "PRESENT")).size());
        assertEquals(0, call(t, "rejected_attempts", in(f, "session_id", sessionId)).size());

        // Reject, then the report counts it as rejected; the other student is absent.
        JsonNode decided = call(t, "decide_attendance", in(f, "attendance_id", attId, "decision", "REJECT",
                "note", "wrong room"));
        assertEquals("REJECTED", decided.get("teacherDecision").asString());
        assertFalse(decided.get("present").asBoolean());
        assertEquals(1, call(t, "session_attendances", in(f, "session_id", sessionId, "only", "REJECTED")).size());

        JsonNode report = call(t, "attendance_report", in(f));
        assertEquals(1, report.get("sessions").size());
        assertEquals(sessionId, report.get("sessions").get(0).get("id").asLong());
        Map<String, JsonNode> byNo = new LinkedHashMap<>();
        report.get("students").forEach(r -> byNo.put(r.get("student_no").asString(), r));
        assertEquals(2, byNo.size());
        JsonNode r1 = byNo.get(f.s1().studentNo());
        assertEquals(1, r1.get("sessions").asInt());
        assertEquals(0, r1.get("present").asInt());
        assertEquals(1, r1.get("rejected").asInt());
        assertEquals(0, r1.get("absent").asInt());
        assertEquals(0.0, r1.get("present_pct").asDouble());
        JsonNode r2 = byNo.get(f.s2().studentNo());
        assertEquals(1, r2.get("absent").asInt());
        assertEquals(0, r2.get("present").asInt());

        // Approve again: present.
        call(t, "decide_attendance", in(f, "attendance_id", attId, "decision", "APPROVED"));
        report = call(t, "attendance_report", in(f));
        for (JsonNode r : report.get("students")) {
            if (r.get("student_no").asString().equals(f.s1().studentNo())) {
                assertEquals(1, r.get("present").asInt());
                assertEquals(100.0, r.get("present_pct").asDouble());
            }
        }

        // One student's rows; a vague query lists the matches.
        JsonNode student = call(t, "student_attendance", in(f, "student", f.s1().studentNo()));
        assertEquals(f.s1().id(), student.get("personId").asLong());
        assertEquals(1, student.get("attendance").size());
        assertEquals("PRESENT", student.get("attendance").get(0).get("state").asString());
        assertEquals("ABSENT", call(t, "student_attendance", in(f, "student", f.s2().studentNo()))
                .get("attendance").get(0).get("state").asString());
        JsonNode many = call(t, "student_attendance", in(f, "student", "Öğrenci"));
        assertEquals(2, many.get("matches").size());
        assertTrue(callError(t, "student_attendance", in(f, "student", "nobody@example.edu")).contains("No student"));

        // Close, export, reopen.
        assertEquals("CLOSED", call(t, "close_session", in(f, "session_id", sessionId)).get("status").asString());
        assertFalse(callError(t, "extend_session", in(f, "session_id", sessionId, "minutes", 5)).isBlank());
        JsonNode export = call(t, "session_export", in(f, "session_id", sessionId));
        assertTrue(export.get("filename").asString().startsWith("yoklama_" + f.course().code()));
        String csv = export.get("csv").asString();
        assertTrue(csv.contains("ogrenciNo;adSoyad"), csv);
        assertTrue(csv.contains(f.s1().studentNo() + ";" + f.s1().name() + ";ONAYLANDI;EVET"), csv);
        assertTrue(csv.contains(f.s2().studentNo() + ";" + f.s2().name() + ";GELMEDI"), csv);
        JsonNode reopened = call(t, "reopen_session", in(f, "session_id", sessionId, "minutes", 5));
        assertEquals("OPEN", reopened.get("status").asString());
        call(t, "close_session", in(f, "session_id", sessionId));

        // Delete a record.
        assertEquals(attId, call(t, "remove_attendance", in(f, "attendance_id", attId)).get("deleted").asLong());
        assertEquals(0, call(t, "session_attendances", in(f, "session_id", sessionId)).size());
        assertTrue(callError(t, "remove_attendance", in(f, "attendance_id", attId)).contains("not found"));
    }

    @Test
    void onlyTheStaffOfTheCourseMayUseTheTools() throws Exception {
        Fx f = fx();
        String owner = token(f.owner());
        long sessionId = call(owner, "create_session", in(f, "title", "Private")).get("id").asLong();

        // A student does not see the tools at all.
        rpc(token(f.s1()), "tools/call", Map.of("name", "list_sessions", "arguments", Map.of()))
                .andExpect(jsonPath("$.error.code").value(-32602));
        rpc(token(f.s1()), "tools/call", Map.of("name", "attendance_report", "arguments", Map.of()))
                .andExpect(jsonPath("$.error.code").value(-32602));

        // The staff of another course: by course and by session.
        String other = token(newStaff());
        assertTrue(callError(other, "list_sessions", in(f)).contains("list_courses"));
        String bySession = callError(other, "get_session", Map.of("session_id", sessionId));
        assertTrue(bySession.contains("Only the staff of"), bySession);
        assertTrue(callError(other, "open_session", Map.of("session_id", sessionId)).contains("Only the staff of"));
        assertEquals("DRAFT", call(owner, "get_session", in(f, "session_id", sessionId)).get("status").asString());
    }
}
