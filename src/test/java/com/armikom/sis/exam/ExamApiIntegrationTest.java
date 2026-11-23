package com.armikom.sis.exam;

import com.armikom.sis.security.RateLimitFilter;
import com.armikom.sis.support.PostgresTestcontainer;
import com.armikom.sis.support.TestClockConfig;
import com.armikom.sis.support.TestPeople;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exam definitions, result entry, scoring and analysis (11). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, PostgresTestcontainer.class, TestPeople.Config.class})
class ExamApiIntegrationTest {

    static final AtomicInteger COUNTER = new AtomicInteger(50_000);
    static final double EPS = 1e-6;

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired TestPeople people;
    @Autowired RateLimitFilter rateLimit;

    static TestPeople PEOPLE;
    /** Exam id → course id, so result entry can put the student on the roster first (results are roster-only). */
    final Map<Long, Long> courseOfExam = new HashMap<>();

    @BeforeEach
    void setUp() {
        PEOPLE = people;
        people.staff("t1");
        people.staff("t2");
        rateLimit.clear();
    }

    // ---- helpers ------------------------------------------------------------------------------

    static RequestPostProcessor t1() { return PEOPLE.as("t1"); }
    static RequestPostProcessor t2() { return PEOPLE.as("t2"); }

    static String studentNo() { return "3303" + COUNTER.incrementAndGet(); }

    String toJson(Object o) { return json.writeValueAsString(o); }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    long createCourse() throws Exception {
        return body(mvc.perform(post("/api/courses").with(t1()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("code", "E" + COUNTER.incrementAndGet(), "name", "Matematik I"))))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    void addRoster(long courseId, String no, String name) throws Exception {
        mvc.perform(post("/api/courses/" + courseId + "/roster").with(t1()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("studentNo", no, "fullName", name))))
                .andExpect(status().isCreated());
    }

    static Map<String, Object> q(Long id, String type, String topic, Object maxPoints, Integer choiceCount,
                                 String correct) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("text", null);
        m.put("topic", topic);
        m.put("maxPoints", maxPoints);
        m.put("choiceCount", choiceCount);
        m.put("correctChoice", correct);
        return m;
    }

    static Map<String, Object> exam(String title, String date, List<Map<String, Object>> questions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("examDate", date);
        m.put("questions", questions);
        return m;
    }

    static Map<String, Object> ans(long questionId, String answer, Object points) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("questionId", questionId);
        m.put("answer", answer);
        m.put("points", points);
        return m;
    }

    static Map<String, Object> result(String fullName, List<Map<String, Object>> answers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fullName", fullName);
        m.put("note", null);
        m.put("answers", answers);
        return m;
    }

    ResultActions postExam(long courseId, Object req) throws Exception {
        return mvc.perform(post("/api/courses/" + courseId + "/exams").with(t1()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(req)));
    }

    JsonNode createExam(long courseId, Object req) throws Exception {
        JsonNode e = body(postExam(courseId, req).andExpect(status().isCreated()));
        courseOfExam.put(e.get("id").asLong(), courseId);
        return e;
    }

    ResultActions putExam(long examId, Object req) throws Exception {
        return mvc.perform(put("/api/exams/" + examId).with(t1()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(req)));
    }

    /** Puts the number on the exam's roster (named after the request's fullName) and saves the result. */
    @SuppressWarnings("unchecked")
    ResultActions putResult(long examId, String no, Object req) throws Exception {
        Long courseId = courseOfExam.get(examId);
        if (courseId != null && no.matches("^[0-9]{5,12}$")) {
            Object name = req instanceof Map<?, ?> m ? ((Map<String, Object>) m).get("fullName") : null;
            people.enroll(courseId, no, name != null ? name.toString() : "Öğrenci " + no);
        }
        return putResultRaw(examId, no, req);
    }

    ResultActions putResultRaw(long examId, String no, Object req) throws Exception {
        return mvc.perform(put("/api/exams/" + examId + "/results/" + no).with(t1()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(req)));
    }

    JsonNode saveResult(long examId, String no, Object req) throws Exception {
        return body(putResult(examId, no, req).andExpect(status().isOk()));
    }

    JsonNode getJson(String url) throws Exception {
        return body(mvc.perform(get(url).with(t1())).andExpect(status().isOk()));
    }

    static long qid(JsonNode exam, int index) {
        return exam.get("questions").get(index).get("id").asLong();
    }

    static Map<Long, JsonNode> answersById(JsonNode result) {
        Map<Long, JsonNode> m = new HashMap<>();
        result.get("answers").forEach(a -> m.put(a.get("questionId").asLong(), a));
        return m;
    }

    static JsonNode findBy(JsonNode array, String field, String value) {
        for (JsonNode n : array) if (value.equals(n.get(field).asString())) return n;
        return fail("no element with " + field + "=" + value);
    }

    static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    // ---- definitions --------------------------------------------------------------------------

    @Test
    void createAndUpdateExamDefinition() throws Exception {
        long courseId = createCourse();
        JsonNode created = createExam(courseId, exam("  Vize  ", "2026-11-20", List.of(
                q(null, "CHOICE", "  Türev  ", 4, 4, "b"),
                q(null, "OPEN", "   ", 6, 4, "A"),
                q(null, "FREE", null, "2.5", null, null))));
        assertEquals("Vize", created.get("title").asString());
        assertEquals("2026-11-20", created.get("examDate").asString());
        assertEquals("Matematik I", created.get("courseName").asString());
        assertEquals(12.5, created.get("totalPoints").asDouble(), EPS);
        assertEquals(0, created.get("resultCount").asInt());
        JsonNode qs = created.get("questions");
        assertEquals(3, qs.size());
        assertEquals(1, qs.get(0).get("position").asInt());
        assertEquals("B", qs.get(0).get("correctChoice").asString());
        assertEquals("Türev", qs.get(0).get("topic").asString());
        assertEquals("4", qs.get(0).get("maxPoints").toString());
        assertTrue(qs.get(1).get("topic").isNull());
        assertTrue(qs.get(1).get("choiceCount").isNull());
        assertTrue(qs.get(1).get("correctChoice").isNull());
        assertEquals(3, qs.get(2).get("position").asInt());

        long examId = created.get("id").asLong();
        long q1 = qid(created, 0), q2 = qid(created, 1), q3 = qid(created, 2);

        // Reorder (q3 first), update q1, drop q2, append a new question.
        JsonNode updated = body(putExam(examId, exam("Vize 1", null, List.of(
                q(q3, "FREE", "Limit", 3, null, null),
                q(q1, "CHOICE", "Türev", 5, 5, "E"),
                q(null, "CHOICE", null, 1, 2, null)))).andExpect(status().isOk()));
        assertEquals("Vize 1", updated.get("title").asString());
        assertTrue(updated.get("examDate").isNull());
        JsonNode uq = updated.get("questions");
        assertEquals(3, uq.size());
        assertEquals(q3, uq.get(0).get("id").asLong());
        assertEquals(1, uq.get(0).get("position").asInt());
        assertEquals(q1, uq.get(1).get("id").asLong());
        assertEquals(2, uq.get(1).get("position").asInt());
        assertEquals("E", uq.get(1).get("correctChoice").asString());
        assertEquals(3, uq.get(2).get("position").asInt());
        assertNotEquals(q2, uq.get(2).get("id").asLong());
        assertEquals(9, updated.get("totalPoints").asDouble(), EPS);

        // GET returns the same, the list shows a summary.
        assertEquals(uq, getJson("/api/exams/" + examId).get("questions"));
        JsonNode list = getJson("/api/courses/" + courseId + "/exams");
        assertEquals(1, list.size());
        assertEquals(3, list.get(0).get("questionCount").asInt());
        assertTrue(list.get(0).get("averagePoints").isNull());

        // Delete: 204, then 404.
        mvc.perform(delete("/api/exams/" + examId).with(t1()).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/exams/" + examId).with(t1())).andExpect(status().isNotFound());
    }

    @Test
    void examListIsNewestFirst() throws Exception {
        long courseId = createCourse();
        createExam(courseId, exam("Eski", "2026-10-01", List.of()));
        createExam(courseId, exam("Tarihsiz", null, List.of()));
        createExam(courseId, exam("Yeni", "2026-11-01", List.of()));
        JsonNode list = getJson("/api/courses/" + courseId + "/exams");
        assertEquals(List.of("Yeni", "Eski", "Tarihsiz"),
                List.of(list.get(0).get("title").asString(), list.get(1).get("title").asString(),
                        list.get(2).get("title").asString()));
    }

    @Test
    void definitionValidation() throws Exception {
        long courseId = createCourse();
        postExam(courseId, exam(" ", null, List.of(
                q(null, "CHOICE", null, 0, 11, null),
                q(null, "CHOICE", null, 1001, 4, "E"),
                q(null, null, "x".repeat(121), 1, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.title").exists())
                .andExpect(jsonPath("$.details['questions[0].maxPoints']").exists())
                .andExpect(jsonPath("$.details['questions[0].choiceCount']").exists())
                .andExpect(jsonPath("$.details['questions[1].maxPoints']").exists())
                .andExpect(jsonPath("$.details['questions[1].correctChoice']").exists())
                .andExpect(jsonPath("$.details['questions[2].type']").exists())
                .andExpect(jsonPath("$.details['questions[2].topic']").exists());

        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 201; i++) many.add(q(null, "FREE", null, 1, null, null));
        postExam(courseId, exam("Çok", null, many))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.questions").exists());

        postExam(courseId, Map.of("title", "x", "questions", List.of(Map.of("type", "WHAT", "maxPoints", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // A question id of another exam cannot be reused.
        JsonNode a = createExam(courseId, exam("A", null, List.of(q(null, "FREE", null, 1, null, null))));
        JsonNode b = createExam(courseId, exam("B", null, List.of(q(null, "FREE", null, 1, null, null))));
        putExam(b.get("id").asLong(), exam("B", null, List.of(q(qid(a, 0), "FREE", null, 1, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details['questions[0].id']").exists());
        // Duplicate id within one request.
        putExam(b.get("id").asLong(), exam("B", null, List.of(
                q(qid(b, 0), "FREE", null, 1, null, null), q(qid(b, 0), "FREE", null, 1, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details['questions[1].id']").exists());
    }

    @Test
    void ownership() throws Exception {
        long courseId = createCourse();
        JsonNode e = createExam(courseId, exam("Vize", null, List.of(q(null, "FREE", null, 1, null, null))));
        long examId = e.get("id").asLong();

        mvc.perform(get("/api/exams/" + examId).with(t2())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/courses/" + courseId + "/exams").with(t2())).andExpect(status().isForbidden());
        mvc.perform(post("/api/courses/" + courseId + "/exams").with(t2()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(exam("X", null, List.of()))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/exams/" + examId).with(t2()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(exam("X", null, List.of()))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/exams/" + examId).with(t2()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(put("/api/exams/" + examId + "/results/" + studentNo()).with(t2()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(result(null, List.of()))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/exams/" + examId + "/results").with(t2())).andExpect(status().isForbidden());
        mvc.perform(get("/api/exams/" + examId + "/analysis").with(t2())).andExpect(status().isForbidden());
        mvc.perform(get("/api/exams/" + examId + "/results.csv").with(t2())).andExpect(status().isForbidden());
        mvc.perform(get("/api/courses/" + courseId + "/learning").with(t2())).andExpect(status().isForbidden());
        mvc.perform(get("/api/courses/" + courseId + "/learning/students/" + studentNo()).with(t2()))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/exams/99999999").with(t1())).andExpect(status().isNotFound());
        mvc.perform(get("/api/exams/" + examId)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/exams/" + examId).with(t1())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(exam("X", null, List.of()))))
                .andExpect(status().isForbidden());   // no CSRF token
    }

    // ---- results ------------------------------------------------------------------------------

    @Test
    void resultUpsertScoring() throws Exception {
        long courseId = createCourse();
        String rostered = studentNo();
        addRoster(courseId, rostered, "Ayşe Yılmaz");
        JsonNode e = createExam(courseId, exam("Vize", null, List.of(
                q(null, "CHOICE", null, 4, 4, "B"),
                q(null, "CHOICE", null, 4, 4, null),
                q(null, "CHOICE", null, 4, 4, "A"),
                q(null, "OPEN", null, 10, null, null),
                q(null, "FREE", null, 5, null, null))));
        long examId = e.get("id").asLong();
        long c1 = qid(e, 0), c2 = qid(e, 1), c3 = qid(e, 2), open = qid(e, 3), free = qid(e, 4);

        JsonNode r = saveResult(examId, rostered, result("Başka Ad", List.of(
                ans(c1, " b ", 99),          // lowercase normalised, request points ignored
                ans(c2, "C", null),          // no key → null points
                ans(c3, "", null),           // blank stored, scored 0
                ans(open, "Türev tanımı", "7.25"),
                ans(free, "ignored", null)))); // FREE without points → not stored
        assertEquals("Ayşe Yılmaz", r.get("fullName").asString());   // roster wins
        assertEquals(11.25, r.get("totalPoints").asDouble(), EPS);
        assertEquals(3, r.get("gradedCount").asInt());
        assertFalse(r.get("complete").asBoolean());
        Map<Long, JsonNode> a = answersById(r);
        assertEquals(4, a.size());
        assertEquals("B", a.get(c1).get("answer").asString());
        assertEquals(4, a.get(c1).get("points").asDouble(), EPS);
        assertTrue(a.get(c2).get("points").isNull());
        assertTrue(a.get(c3).get("answer").isNull());
        assertEquals(0, a.get(c3).get("points").asDouble(), EPS);
        assertEquals("Türev tanımı", a.get(open).get("answer").asString());
        assertFalse(a.containsKey(free));
        long c1Row = r.get("id").asLong();

        // Second save is the full set: c2, c3 and the open answer go away, c1 is updated in place.
        JsonNode r2 = saveResult(examId, rostered, result(null, List.of(
                ans(c1, "A", null), ans(free, null, 5), ans(open, null, null))));
        assertEquals(c1Row, r2.get("id").asLong());
        Map<Long, JsonNode> a2 = answersById(r2);
        assertEquals(Set.of(c1, free), a2.keySet());
        assertEquals(0, a2.get(c1).get("points").asDouble(), EPS);
        assertEquals(5, r2.get("totalPoints").asDouble(), EPS);

        // Not on the roster: results are roster-only in SIS.
        String outsider = studentNo();
        putResultRaw(examId, outsider, result("Dış Öğrenci", List.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_IN_ROSTER"));

        // Complete when every question is graded.
        String full = studentNo();
        JsonNode complete = saveResult(examId, full, result(null, List.of(
                ans(c1, "B", null), ans(c2, "A", null), ans(c3, "A", null), ans(open, null, 10), ans(free, null, 0))));
        assertEquals(4, complete.get("gradedCount").asInt());   // c2 has no key
        assertFalse(complete.get("complete").asBoolean());

        JsonNode list = getJson("/api/exams/" + examId + "/results");
        assertEquals(2, list.size());
        List<String> nos = new ArrayList<>();
        list.forEach(n -> nos.add(n.get("studentNo").asString()));
        assertEquals(nos.stream().sorted().toList(), nos);
        assertEquals(2, getJson("/api/exams/" + examId).get("resultCount").asInt());

        mvc.perform(delete("/api/exams/" + examId + "/results/" + full).with(t1()).with(csrf()))
                .andExpect(status().isNoContent());
        assertEquals(1, getJson("/api/exams/" + examId + "/results").size());
    }

    @Test
    void resultValidation() throws Exception {
        long courseId = createCourse();
        JsonNode e = createExam(courseId, exam("Vize", null, List.of(
                q(null, "CHOICE", null, 4, 4, "B"),
                q(null, "OPEN", null, 10, null, null),
                q(null, "FREE", null, 5, null, null))));
        JsonNode other = createExam(courseId, exam("Diğer", null, List.of(q(null, "FREE", null, 5, null, null))));
        long examId = e.get("id").asLong();
        long c = qid(e, 0), open = qid(e, 1), free = qid(e, 2);
        String no = studentNo();

        putResult(examId, no, result(null, List.of(
                ans(c, "E", null),
                ans(open, "x".repeat(2001), 11),
                ans(free, null, -1),
                ans(qid(other, 0), null, 1),
                ans(c, "A", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details['answers[0].answer']").exists())
                .andExpect(jsonPath("$.details['answers[1].answer']").exists())
                .andExpect(jsonPath("$.details['answers[1].points']").exists())
                .andExpect(jsonPath("$.details['answers[2].points']").exists())
                .andExpect(jsonPath("$.details['answers[3].questionId']").exists())
                .andExpect(jsonPath("$.details['answers[4].questionId']").exists());
        putResult(examId, no, result(null, List.of(ans(c, "AB", null))))
                .andExpect(status().isBadRequest());
        putResult(examId, "abc", result(null, List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.studentNo").exists());
        // Nothing was stored by the failed requests.
        assertEquals(0, getJson("/api/exams/" + examId + "/results").size());
    }

    @Test
    void rescoringOnDefinitionChange() throws Exception {
        long courseId = createCourse();
        JsonNode e = createExam(courseId, exam("Vize", null, List.of(
                q(null, "CHOICE", "T", 4, 4, null),
                q(null, "OPEN", null, 10, null, null),
                q(null, "FREE", null, 5, null, null),
                q(null, "FREE", null, 5, null, null))));
        long examId = e.get("id").asLong();
        long c = qid(e, 0), open = qid(e, 1), free = qid(e, 2), gone = qid(e, 3);
        String s1 = studentNo(), s2 = studentNo();
        saveResult(examId, s1, result(null, List.of(ans(c, "D", null), ans(open, "x", 8), ans(free, null, 4),
                ans(gone, null, 5))));
        saveResult(examId, s2, result(null, List.of(ans(c, "B", null), ans(open, null, 3))));

        // Answer key entered later → existing answers scored.
        putExam(examId, exam("Vize", null, List.of(
                q(c, "CHOICE", "T", 4, 4, "D"), q(open, "OPEN", null, 10, null, null),
                q(free, "FREE", null, 5, null, null), q(gone, "FREE", null, 5, null, null))))
                .andExpect(status().isOk());
        Map<String, Map<Long, JsonNode>> r = results(examId);
        assertEquals(4, r.get(s1).get(c).get("points").asDouble(), EPS);
        assertEquals(0, r.get(s2).get(c).get("points").asDouble(), EPS);

        // maxPoints change on CHOICE rescored; OPEN maxPoints drop clips; FREE type → OPEN keeps nothing;
        // choiceCount shrink keeps the out-of-range letter with 0 points; removed question loses answers.
        putExam(examId, exam("Vize", null, List.of(
                q(c, "CHOICE", "T", 6, 2, "B"),
                q(open, "OPEN", null, 5, null, null),
                q(free, "OPEN", null, 5, null, null))))
                .andExpect(status().isOk());
        r = results(examId);
        assertEquals("D", r.get(s1).get(c).get("answer").asString());
        assertEquals(0, r.get(s1).get(c).get("points").asDouble(), EPS);
        assertEquals(6, r.get(s2).get(c).get("points").asDouble(), EPS);
        assertEquals(5, r.get(s1).get(open).get("points").asDouble(), EPS);   // 8 → 5
        assertEquals(3, r.get(s2).get(open).get("points").asDouble(), EPS);   // unchanged
        assertFalse(r.get(s1).containsKey(free));
        assertFalse(r.get(s1).containsKey(gone));
        assertEquals(5, getJson("/api/exams/" + examId + "/results").get(0).get("totalPoints").asDouble(), EPS);   // s1: 0 + 5

        // Choice analysis keeps the out-of-range letter.
        JsonNode counts = getJson("/api/exams/" + examId + "/analysis").get("questions").get(0).get("choiceCounts");
        assertEquals(List.of("A", "B", "D", ""), iterableNames(counts));
        assertEquals(1, counts.get("D").asInt());

        // Removing the key makes CHOICE points null again.
        putExam(examId, exam("Vize", null, List.of(
                q(c, "CHOICE", "T", 6, 2, null), q(open, "OPEN", null, 5, null, null),
                q(free, "OPEN", null, 5, null, null)))).andExpect(status().isOk());
        assertTrue(results(examId).get(s2).get(c).get("points").isNull());
    }

    // ---- choice labels, groups, html ----------------------------------------------------------

    static Map<String, Object> group(Long id, String ref, String text, String html) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("ref", ref);
        m.put("text", text);
        m.put("html", html);
        return m;
    }

    static Map<String, Object> grouped(Map<String, Object> q, String groupRef, String subLabel) {
        q.put("groupRef", groupRef);
        q.put("subLabel", subLabel);
        return q;
    }

    static Map<String, Object> labelled(Map<String, Object> q, String labels) {
        q.put("choiceLabels", labels);
        q.put("choiceCount", null);
        return q;
    }

    @Test
    void choiceLabelsGroupsAndHtml() throws Exception {
        long courseId = createCourse();
        Map<String, Object> q1 = q(null, "CHOICE", "Giriş", 4, 4, "b");
        q1.put("html", "<p>Soru <b>1</b></p>");
        Map<String, Object> tf1 = grouped(labelled(q(null, "CHOICE", "Mantık", 2, null, "d"), "d / y"), "g1", null);
        Map<String, Object> tf2 = grouped(labelled(q(null, "CHOICE", "Mantık", 2, null, "Y"), "DY"), "g1", null);
        Map<String, Object> bin = grouped(labelled(q(null, "CHOICE", "Mantık", 2, null, "1"), "10"), "g1", "iii");
        Map<String, Object> openQ = q(null, "OPEN", null, 10, null, null);
        Map<String, Object> req = exam("Vize", "2026-11-20", List.of(q1, tf1, tf2, bin, openQ));
        req.put("groups", List.of(group(null, "g1", "Aşağıdaki ifadeler için", "<p><i>D/Y</i></p>"),
                group(null, "unused", "no questions", null)));
        JsonNode created = createExam(courseId, req);
        long examId = created.get("id").asLong();
        JsonNode qs = created.get("questions");
        assertEquals(List.of("1", "2a", "2b", "2iii", "3"), labelsOf(qs));
        assertEquals("ABCD", qs.get(0).get("choiceLabels").asString());
        assertEquals("<p>Soru <b>1</b></p>", qs.get(0).get("html").asString());
        assertEquals("DY", qs.get(1).get("choiceLabels").asString());
        assertEquals(2, qs.get(1).get("choiceCount").asInt());
        assertEquals("D", qs.get(1).get("correctChoice").asString());
        assertEquals("10", qs.get(3).get("choiceLabels").asString());
        assertEquals("1", qs.get(3).get("correctChoice").asString());
        assertTrue(qs.get(0).get("groupId").isNull());
        long groupId = qs.get(1).get("groupId").asLong();
        assertEquals(groupId, qs.get(3).get("groupId").asLong());
        assertEquals(1, created.get("groups").size(), "a group without questions is dropped");
        JsonNode g = created.get("groups").get(0);
        assertEquals(groupId, g.get("id").asLong());
        assertEquals(2, g.get("position").asInt());
        assertEquals("Aşağıdaki ifadeler için", g.get("text").asString());
        assertEquals("<p><i>D/Y</i></p>", g.get("html").asString());
        assertEquals(List.of(qid(created, 1), qid(created, 2), qid(created, 3)),
                json.convertValue(g.get("questionIds"), List.class).stream().map(x -> ((Number) x).longValue()).toList());

        // Answers use the labels; anything else is rejected.
        String s1 = studentNo();
        long tfId = qid(created, 1), binId = qid(created, 3);
        putResult(examId, s1, result(null, List.of(ans(tfId, "A", null)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details['answers[0].answer']").exists());
        JsonNode saved = saveResult(examId, s1, result(null, List.of(ans(qid(created, 0), "B", null),
                ans(tfId, "y", null), ans(qid(created, 2), "Y", null), ans(binId, "1", null))));
        Map<Long, JsonNode> a = answersById(saved);
        assertEquals("Y", a.get(tfId).get("answer").asString());
        assertEquals(0, a.get(tfId).get("points").asDouble(), EPS);
        assertEquals(2, a.get(qid(created, 2)).get("points").asDouble(), EPS);
        assertEquals(2, a.get(binId).get("points").asDouble(), EPS);

        // Analysis and CSV carry the labels.
        JsonNode analysis = getJson("/api/exams/" + examId + "/analysis");
        assertEquals("2a", analysis.get("questions").get(1).get("label").asString());
        assertEquals(List.of("D", "Y", ""), iterableNames(analysis.get("questions").get(1).get("choiceCounts")));
        assertEquals(List.of("1", "0", ""), iterableNames(analysis.get("questions").get(3).get("choiceCounts")));
        String csv = mvc.perform(get("/api/exams/" + examId + "/results.csv").with(t1())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(csv.contains("S2a;S2a Puan;S2b;S2b Puan;S2iii;"), csv);

        // Update: the group keeps its id, the html of q1 is cleared with "", a non-contiguous group is rejected.
        Map<String, Object> u1 = q(qid(created, 0), "CHOICE", "Giriş", 4, 4, "B");
        u1.put("html", "");
        Map<String, Object> bad = exam("Vize", null, List.of(
                grouped(labelled(q(tfId, "CHOICE", null, 2, null, "D"), "DY"), "g", null), u1,
                grouped(labelled(q(binId, "CHOICE", null, 2, null, "1"), "10"), "g", null)));
        bad.put("groups", List.of(group(groupId, "g", null, null)));
        putExam(examId, bad).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details['questions[2].groupRef']").exists());
        Map<String, Object> ok = exam("Vize", null, List.of(u1,
                grouped(labelled(q(tfId, "CHOICE", null, 2, null, "D"), "DY"), "g", "x"),
                grouped(labelled(q(binId, "CHOICE", null, 2, null, "1"), "10"), "g", "y")));
        ok.put("groups", List.of(group(groupId, "g", "Yeni metin", null)));
        JsonNode updated = body(putExam(examId, ok).andExpect(status().isOk()));
        assertEquals(List.of("1", "2x", "2y"), labelsOf(updated.get("questions")));
        assertTrue(updated.get("questions").get(0).get("html").isNull());
        assertEquals(groupId, updated.get("groups").get(0).get("id").asLong());
        assertEquals("Yeni metin", updated.get("groups").get(0).get("text").asString());
        assertTrue(updated.get("groups").get(0).get("html").isNull());
        assertEquals("Y", answersById(findBy(getJson("/api/exams/" + examId + "/results"), "studentNo", s1))
                .get(tfId).get("answer").asString(), "answers survive a regrouping");

        // Dropping every group reference removes the group; the questions stay.
        Map<String, Object> flat = exam("Vize", null, List.of(u1,
                labelled(q(tfId, "CHOICE", null, 2, null, "D"), "DY"), labelled(q(binId, "CHOICE", null, 2, null, "1"), "10")));
        JsonNode flatExam = body(putExam(examId, flat).andExpect(status().isOk()));
        assertEquals(0, flatExam.get("groups").size());
        assertEquals(List.of("1", "2", "3"), labelsOf(flatExam.get("questions")));

        // Bad label sets.
        postExam(courseId, exam("Bad", null, List.of(
                labelled(q(null, "CHOICE", null, 1, null, null), "AAB"),
                labelled(q(null, "CHOICE", null, 1, null, "C"), "DY"),
                labelled(q(null, "CHOICE", null, 1, null, null), "A"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details['questions[0].choiceLabels']").exists())
                .andExpect(jsonPath("$.details['questions[1].correctChoice']").exists())
                .andExpect(jsonPath("$.details['questions[2].choiceLabels']").exists());
    }

    @Test
    void sameSizeLabelChangeRenamesAnswersAndOptions() throws Exception {
        long courseId = createCourse();
        Map<String, Object> q1 = q(null, "CHOICE", null, 3, 2, "B");
        q1.put("options", List.of(Map.of("letter", "A", "text", "true"), Map.of("letter", "B", "text", "false")));
        JsonNode created = createExam(courseId, exam("TF", null, List.of(q1, q(null, "CHOICE", null, 2, 4, "C"))));
        long examId = created.get("id").asLong();
        long tf = qid(created, 0), mc = qid(created, 1);
        String s1 = studentNo(), s2 = studentNo();
        saveResult(examId, s1, result(null, List.of(ans(tf, "B", null), ans(mc, "C", null))));
        saveResult(examId, s2, result(null, List.of(ans(tf, "A", null), ans(mc, "D", null))));

        // AB → TF keeps every answer (renamed); ABCD → AB (size change) keeps letters as they are.
        Map<String, Object> u1 = labelled(q(tf, "CHOICE", null, 3, null, "F"), "TF");
        Map<String, Object> u2 = labelled(q(mc, "CHOICE", null, 2, null, "A"), "AB");
        JsonNode updated = body(putExam(examId, exam("TF", null, List.of(u1, u2))).andExpect(status().isOk()));
        assertEquals("TF", updated.get("questions").get(0).get("choiceLabels").asString());
        assertEquals("F", updated.get("questions").get(0).get("correctChoice").asString());
        JsonNode opts = updated.get("questions").get(0).get("options");
        assertEquals(List.of("T", "F"), List.of(opts.get(0).get("letter").asString(), opts.get(1).get("letter").asString()));
        assertEquals("true", opts.get(0).get("text").asString());
        Map<String, Map<Long, JsonNode>> r = results(examId);
        assertEquals("F", r.get(s1).get(tf).get("answer").asString());
        assertEquals(3, r.get(s1).get(tf).get("points").asDouble(), EPS);
        assertEquals("T", r.get(s2).get(tf).get("answer").asString());
        assertEquals(0, r.get(s2).get(tf).get("points").asDouble(), EPS);
        assertEquals("C", r.get(s1).get(mc).get("answer").asString());
        assertEquals(0, r.get(s1).get(mc).get("points").asDouble(), EPS);
        assertEquals("D", r.get(s2).get(mc).get("answer").asString());
    }

    static List<String> labelsOf(JsonNode questions) {
        List<String> out = new ArrayList<>();
        questions.forEach(q -> out.add(q.get("label").asString()));
        return out;
    }

    static List<String> iterableNames(JsonNode obj) {
        List<String> out = new ArrayList<>();
        obj.propertyNames().forEach(out::add);
        return out;
    }

    Map<String, Map<Long, JsonNode>> results(long examId) throws Exception {
        Map<String, Map<Long, JsonNode>> m = new HashMap<>();
        getJson("/api/exams/" + examId + "/results").forEach(r -> m.put(r.get("studentNo").asString(), answersById(r)));
        return m;
    }

    // ---- analysis -----------------------------------------------------------------------------

    record Fixture(long courseId, long exam1, long exam2, String s1, String s2, String s3, String s4,
                   long[] q) {}

    /**
     * Exam 1 (total 20): Q1 CHOICE key B 4p "Türev", Q2 CHOICE key A 4p "İntegral", Q3 OPEN 2p "İntegral",
     * Q4 FREE 10p (no topic). S1: B,A,2,10 = 20. S2: b,C,0.5,6 = 10.5. S3: blank,A,—,4 = 8.
     * Exam 2: one FREE 10p "Türev"; S1 scores 2. S4 is on the roster without results.
     */
    Fixture fixture() throws Exception {
        long courseId = createCourse();
        String s1 = studentNo(), s2 = studentNo(), s3 = studentNo(), s4 = studentNo();
        addRoster(courseId, s1, "Ali Veli");
        addRoster(courseId, s4, "Deniz Kaya");
        JsonNode e1 = createExam(courseId, exam("Vize", "2026-11-01", List.of(
                q(null, "CHOICE", "Türev", 4, 4, "B"),
                q(null, "CHOICE", "İntegral", 4, 4, "A"),
                q(null, "OPEN", "İntegral", 2, null, null),
                q(null, "FREE", null, 10, null, null))));
        long[] q = {qid(e1, 0), qid(e1, 1), qid(e1, 2), qid(e1, 3)};
        long exam1 = e1.get("id").asLong();
        saveResult(exam1, s1, result(null, List.of(ans(q[0], "B", null), ans(q[1], "A", null),
                ans(q[2], null, 2), ans(q[3], null, 10))));
        saveResult(exam1, s2, result("Berk Can", List.of(ans(q[0], "b", null), ans(q[1], "C", null),
                ans(q[2], null, "0.5"), ans(q[3], null, 6))));
        saveResult(exam1, s3, result("Cem Ak", List.of(ans(q[0], null, null), ans(q[1], "A", null),
                ans(q[3], null, 4))));
        JsonNode e2 = createExam(courseId, exam("Quiz", "2026-11-10", List.of(q(null, "FREE", "Türev", 10, null, null))));
        long exam2 = e2.get("id").asLong();
        saveResult(exam2, s1, result(null, List.of(ans(qid(e2, 0), null, 2))));
        return new Fixture(courseId, exam1, exam2, s1, s2, s3, s4, q);
    }

    @Test
    void examAnalysis() throws Exception {
        Fixture f = fixture();
        JsonNode a = getJson("/api/exams/" + f.exam1() + "/analysis");
        assertEquals(20, a.get("totalPoints").asDouble(), EPS);
        assertEquals(3, a.get("resultCount").asInt());
        assertEquals(12.83, a.get("average").asDouble(), EPS);
        assertEquals(10.5, a.get("median").asDouble(), EPS);
        assertEquals(8, a.get("min").asDouble(), EPS);
        assertEquals(20, a.get("max").asDouble(), EPS);

        JsonNode qs = a.get("questions");
        JsonNode q1 = qs.get(0);
        assertEquals(3, q1.get("gradedCount").asInt());
        assertEquals(2.67, q1.get("averagePoints").asDouble(), EPS);
        assertEquals(8.0 / 12, q1.get("successRate").asDouble(), EPS);
        assertEquals(List.of("A", "B", "C", "D", ""), iterableNames(q1.get("choiceCounts")));
        assertEquals(2, q1.get("choiceCounts").get("B").asInt());
        assertEquals(1, q1.get("choiceCounts").get("").asInt());
        assertEquals(0, q1.get("choiceCounts").get("A").asInt());
        JsonNode q2 = qs.get(1);
        assertEquals(2, q2.get("choiceCounts").get("A").asInt());
        assertEquals(1, q2.get("choiceCounts").get("C").asInt());
        assertEquals(0, q2.get("choiceCounts").get("").asInt());
        JsonNode q3 = qs.get(2);
        assertEquals(2, q3.get("gradedCount").asInt());
        assertEquals(1.25, q3.get("averagePoints").asDouble(), EPS);
        assertEquals(0.625, q3.get("successRate").asDouble(), EPS);
        assertTrue(q3.get("choiceCounts").isNull());

        JsonNode topics = a.get("topics");
        assertEquals(2, topics.size());
        assertEquals("İntegral", topics.get(0).get("topic").asString());
        assertEquals(2, topics.get(0).get("questionCount").asInt());
        assertEquals(6, topics.get(0).get("maxPoints").asDouble(), EPS);
        assertEquals(10.5 / 16, topics.get(0).get("successRate").asDouble(), EPS);
        assertEquals(1, topics.get(0).get("weakStudentCount").asInt());
        assertEquals("Türev", topics.get(1).get("topic").asString());
        assertEquals(8.0 / 12, topics.get(1).get("successRate").asDouble(), EPS);
        assertEquals(1, topics.get(1).get("weakStudentCount").asInt());

        JsonNode st = a.get("students");
        JsonNode s1 = findBy(st, "studentNo", f.s1());
        assertEquals("Ali Veli", s1.get("fullName").asString());
        assertEquals(1.0, s1.get("percent").asDouble(), EPS);
        assertTrue(s1.get("complete").asBoolean());
        assertEquals(List.of(), strings(s1.get("weakTopics")));
        JsonNode s2 = findBy(st, "studentNo", f.s2());
        assertEquals(0.525, s2.get("percent").asDouble(), EPS);
        assertEquals(List.of("İntegral"), strings(s2.get("weakTopics")));
        JsonNode s3 = findBy(st, "studentNo", f.s3());
        assertFalse(s3.get("complete").asBoolean());
        assertEquals(List.of("Türev"), strings(s3.get("weakTopics")));

        // Summary average.
        JsonNode list = getJson("/api/courses/" + f.courseId() + "/exams");
        assertEquals("Quiz", list.get(0).get("title").asString());
        assertEquals(12.83, list.get(1).get("averagePoints").asDouble(), EPS);
        assertEquals(3, list.get(1).get("resultCount").asInt());

        // An exam without results.
        JsonNode empty = createExam(f.courseId(), exam("Boş", null, List.of(q(null, "FREE", "X", 1, null, null))));
        JsonNode ea = getJson("/api/exams/" + empty.get("id").asLong() + "/analysis");
        assertTrue(ea.get("average").isNull());
        assertTrue(ea.get("median").isNull());
        assertTrue(ea.get("topics").get(0).get("successRate").isNull());
    }

    @Test
    void courseAndStudentLearning() throws Exception {
        Fixture f = fixture();
        JsonNode c = getJson("/api/courses/" + f.courseId() + "/learning");
        assertEquals(2, c.get("exams").size());

        JsonNode topics = c.get("topics");
        assertEquals("Türev", topics.get(0).get("topic").asString());
        assertEquals(2, topics.get(0).get("questionCount").asInt());
        assertEquals(10.0 / 22, topics.get(0).get("successRate").asDouble(), EPS);
        JsonNode weak = topics.get(0).get("weakStudents");
        assertEquals(2, weak.size());
        assertEquals(f.s3(), weak.get(0).get("studentNo").asString());
        assertEquals("Cem Ak", weak.get(0).get("fullName").asString());
        assertEquals(0, weak.get(0).get("successRate").asDouble(), EPS);
        assertEquals(f.s1(), weak.get(1).get("studentNo").asString());
        assertEquals(6.0 / 14, weak.get(1).get("successRate").asDouble(), EPS);
        assertEquals("İntegral", topics.get(1).get("topic").asString());
        assertEquals(1, topics.get(1).get("weakStudents").size());

        JsonNode students = c.get("students");
        assertEquals(4, students.size());
        assertEquals(List.of(f.s3(), f.s2(), f.s1(), f.s4()),
                List.of(students.get(0).get("studentNo").asString(), students.get(1).get("studentNo").asString(),
                        students.get(2).get("studentNo").asString(), students.get(3).get("studentNo").asString()));
        assertEquals(8.0 / 18, students.get(0).get("percent").asDouble(), EPS);
        assertEquals(22.0 / 30, students.get(2).get("percent").asDouble(), EPS);
        assertEquals(2, students.get(2).get("examCount").asInt());
        assertEquals(List.of("Türev"), strings(students.get(2).get("weakTopics")));
        assertTrue(students.get(3).get("percent").isNull());
        assertEquals(0, students.get(3).get("examCount").asInt());
        assertEquals("Deniz Kaya", students.get(3).get("fullName").asString());

        JsonNode s = getJson("/api/courses/" + f.courseId() + "/learning/students/" + f.s2());
        assertEquals("Berk Can", s.get("fullName").asString());
        assertEquals(1, s.get("exams").size());
        JsonNode ex = s.get("exams").get(0);
        assertEquals(f.exam1(), ex.get("examId").asLong());
        assertEquals("2026-11-01", ex.get("examDate").asString());
        assertEquals(10.5, ex.get("totalPoints").asDouble(), EPS);
        assertEquals(20, ex.get("maxPoints").asDouble(), EPS);
        assertEquals(0.525, ex.get("percent").asDouble(), EPS);
        assertTrue(ex.get("complete").asBoolean());
        JsonNode st = s.get("topics");
        assertEquals("İntegral", st.get(0).get("topic").asString());
        assertEquals(2, st.get(0).get("questionCount").asInt());
        assertEquals(0.5, st.get(0).get("earnedPoints").asDouble(), EPS);
        assertEquals(6, st.get(0).get("maxPoints").asDouble(), EPS);
        assertEquals("Türev", st.get(1).get("topic").asString());
        JsonNode missed = s.get("missedQuestions");
        assertEquals(2, missed.size());
        assertEquals(2, missed.get(0).get("position").asInt());
        assertEquals("C", missed.get(0).get("answer").asString());
        assertEquals("A", missed.get(0).get("correctChoice").asString());
        assertEquals("Vize", missed.get(0).get("examTitle").asString());
        assertEquals(0, missed.get(0).get("points").asDouble(), EPS);
        assertEquals(3, missed.get(1).get("position").asInt());
        assertTrue(missed.get(1).get("correctChoice").isNull());

        // Roster student without results: empty lists; unknown student: 404.
        JsonNode s4 = getJson("/api/courses/" + f.courseId() + "/learning/students/" + f.s4());
        assertEquals("Deniz Kaya", s4.get("fullName").asString());
        assertEquals(0, s4.get("exams").size());
        mvc.perform(get("/api/courses/" + f.courseId() + "/learning/students/" + studentNo()).with(t1()))
                .andExpect(status().isNotFound());
    }

    // ---- CSV and course deletion ----------------------------------------------------------------

    @Test
    void resultsCsv() throws Exception {
        Fixture f = fixture();
        MvcResult r = mvc.perform(get("/api/exams/" + f.exam1() + "/results.csv").with(t1()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andReturn();
        String csv = new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertEquals("﻿OgrenciNo;AdSoyad;S1;S1 Puan;S2;S2 Puan;S3;S3 Puan;S4;S4 Puan;Toplam", lines[0]);
        assertEquals(4, lines.length);
        assertTrue(csv.contains(f.s2() + ";Berk Can;B;4;C;0;;0,5;;6;10,5\r\n"), csv);
        assertTrue(csv.contains(f.s3() + ";Cem Ak;;0;A;4;;;;4;8\r\n"), csv);
    }

    @Test
    void courseWithExamsCannotBeDeleted() throws Exception {
        long courseId = createCourse();
        long examId = createExam(courseId, exam("Vize", null, List.of())).get("id").asLong();
        mvc.perform(delete("/api/courses/" + courseId).with(t1()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(delete("/api/exams/" + examId).with(t1()).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/courses/" + courseId).with(t1()).with(csrf())).andExpect(status().isNoContent());
    }

    // ---- answer-entry drafts ------------------------------------------------------------------

    ResultActions putDraft(long examId, String no, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(put("/api/exams/" + examId + "/drafts/" + no).with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void entryDrafts() throws Exception {
        long courseId = createCourse();
        JsonNode e = createExam(courseId, exam("Vize", null, List.of(q(null, "CHOICE", null, 5, 4, "B"))));
        long examId = e.get("id").asLong();
        String a = studentNo();
        String b = studentNo();
        String sheet = "{\"base\":\"[\\\"\\\"]\",\"name\":\"\",\"cells\":[{\"entered\":true,\"answer\":\"C\",\"points\":\"\",\"text\":\"\"}],\"active\":0}";

        JsonNode saved = body(putDraft(examId, a, sheet, t1()).andExpect(status().isOk()));
        assertEquals(a, saved.get("studentNo").asString());
        assertNotNull(saved.get("updatedAt").asString());
        putDraft(examId, b, sheet, t1()).andExpect(status().isOk());
        putDraft(examId, b, sheet.replace("\"C\"", "\"D\""), t1()).andExpect(status().isOk());   // upsert

        JsonNode list = getJson("/api/exams/" + examId + "/drafts");
        assertEquals(2, list.size());
        assertEquals(a, list.get(0).get("studentNo").asString());
        assertEquals("C", list.get(0).get("sheet").get("cells").get(0).get("answer").asString());
        assertEquals("D", list.get(1).get("sheet").get("cells").get(0).get("answer").asString());
        assertFalse(list.get(0).get("updatedBy").asString().isBlank());

        // Drafts are not results.
        assertEquals(0, getJson("/api/exams/" + examId + "/results").size());

        // Saving the result removes its draft; deleting the result does too.
        saveResult(examId, a, result("Ada Example", List.of(ans(qid(e, 0), "B", null))));
        list = getJson("/api/exams/" + examId + "/drafts");
        assertEquals(1, list.size());
        assertEquals(b, list.get(0).get("studentNo").asString());
        putDraft(examId, a, sheet, t1()).andExpect(status().isOk());
        mvc.perform(delete("/api/exams/" + examId + "/results/" + a).with(t1()).with(csrf())).andExpect(status().isNoContent());
        assertEquals(1, getJson("/api/exams/" + examId + "/drafts").size());

        // Explicit delete (idempotent).
        mvc.perform(delete("/api/exams/" + examId + "/drafts/" + b).with(t1()).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/exams/" + examId + "/drafts/" + b).with(t1()).with(csrf())).andExpect(status().isNoContent());
        assertEquals(0, getJson("/api/exams/" + examId + "/drafts").size());

        // Validation: student number format, object body, size.
        putDraft(examId, "abc", sheet, t1()).andExpect(status().isBadRequest());
        putDraft(examId, a, "[1,2]", t1()).andExpect(status().isBadRequest());
        putDraft(examId, a, "\"x\"", t1()).andExpect(status().isBadRequest());
        String huge = "{\"pad\":\"" + "x".repeat(ExamDraftService.MAX_SHEET_CHARS) + "\"}";
        putDraft(examId, a, huge, t1()).andExpect(status().isBadRequest());

        // Staff of the course only.
        putDraft(examId, a, sheet, t2()).andExpect(status().isForbidden());
        mvc.perform(get("/api/exams/" + examId + "/drafts").with(t2())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/exams/" + examId + "/drafts/" + a).with(t2()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/exams/" + examId + "/drafts")).andExpect(status().isUnauthorized());

        // Drafts go with the exam.
        putDraft(examId, a, sheet, t1()).andExpect(status().isOk());
        mvc.perform(delete("/api/exams/" + examId).with(t1()).with(csrf())).andExpect(status().isNoContent());
    }
}
