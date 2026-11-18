package com.armikom.sis.exam;

import com.armikom.sis.support.ApiTestSupport;
import com.armikom.sis.support.PostgresTestcontainer;
import com.armikom.sis.support.TestClockConfig;
import com.armikom.sis.support.TestPeople;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exam question topics (explicit + via the pool question), publishing, and the student's result detail. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, PostgresTestcontainer.class, TestPeople.Config.class})
class ExamTopicsIntegrationTest extends ApiTestSupport {

    static Map<String, Object> q(String type, int max, String key, Long pool, List<Long> topicIds, Long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (id != null) m.put("id", id);
        m.put("type", type);
        m.put("maxPoints", max);
        if ("CHOICE".equals(type)) {
            m.put("choiceCount", 4);
            m.put("correctChoice", key);
        }
        m.put("poolQuestionId", pool);
        m.put("topicIds", topicIds);
        m.put("text", type + " question");
        return m;
    }

    static Map<String, Object> option(String letter, String text, String rationale) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("letter", letter);
        m.put("text", text);
        m.put("rationale", rationale);
        return m;
    }

    static Map<String, Object> answer(long questionId, String answer, Object points) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("questionId", questionId);
        m.put("answer", answer);
        m.put("points", points);
        return m;
    }

    static List<Long> ids(JsonNode topics) {
        List<Long> out = new ArrayList<>();
        topics.forEach(t -> out.add(t.get("id").asLong()));
        return out;
    }

    @Test
    void effectiveTopicsPublishingAndReviewTopics() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, true, 3);
        long chapter = body(doPost(owner, "/api/pool/courses/" + course.id() + "/staff/chapters",
                Map.of("number", 2, "title", "Static testing", "doc_en", "## 1. Alpha\na\n## 2. Beta\nb\n## 3. Gamma\ng\n"))
                .andExpect(status().isOk())).get("id").asLong();
        Map<Integer, Long> topic = new HashMap<>();
        body(doGet(owner, "/api/pool/chapters/" + chapter)).get("topics")
                .forEach(t -> topic.put(t.get("number").asInt(), t.get("id").asLong()));
        long a = topic.get(1), b = topic.get(2), g = topic.get(3);

        User author = newStudent(course);
        Map<String, Object> pq = questionBody(chapter, "B");
        pq.put("topic_ids", List.of(b));
        long poolQ = body(doPost(author, "/api/pool/questions", pq).andExpect(status().isOk())).get("id").asLong();

        // Topics of another course are refused per question.
        User stranger = newStaff();
        CourseRef foreign = newCourse(stranger, true, 2);
        long fch = body(doPost(stranger, "/api/pool/courses/" + foreign.id() + "/staff/chapters",
                Map.of("number", 1, "title", "F", "doc_en", "## 1. Foreign\n"))).get("id").asLong();
        long foreignTopic = body(doGet(stranger, "/api/pool/chapters/" + fch)).get("topics").get(0).get("id").asLong();
        doPost(owner, "/api/courses/" + course.id() + "/exams", Map.of("title", "Bad",
                "questions", List.of(q("CHOICE", 1, "A", null, List.of(foreignTopic), null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details['questions[0].topicIds']").exists());

        // Options: only within choiceCount for CHOICE, only empty for OPEN/FREE.
        Map<String, Object> badLetter = q("CHOICE", 1, "A", null, null, null);
        badLetter.put("options", List.of(option("E", "e", null)));
        Map<String, Object> badOpen = q("OPEN", 1, null, null, null, null);
        badOpen.put("options", List.of(option("A", "a", null)));
        doPost(owner, "/api/courses/" + course.id() + "/exams", Map.of("title", "Bad", "questions",
                List.of(badLetter, badOpen))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details['questions[0].options[0].letter']").exists())
                .andExpect(jsonPath("$.details['questions[1].options']").exists());

        Map<String, Object> q2 = q("CHOICE", 2, "C", null, List.of(a), null);
        q2.put("explanation", "Because C.");
        q2.put("options", List.of(option("C", "c text", "right"), option("A", "a text", "wrong")));
        q2.put("text", "x".repeat(3000));
        JsonNode exam = body(doPost(owner, "/api/courses/" + course.id() + "/exams", Map.of("title", "Midterm",
                "examDate", "2026-11-10", "questions", List.of(
                        q("CHOICE", 2, "B", poolQ, List.of(a), null),
                        q2,
                        q("OPEN", 4, null, null, List.of(g), null),
                        q("FREE", 1, null, null, null, null)))).andExpect(status().isCreated()));
        JsonNode created2 = exam.get("questions").get(1);
        assertEquals(3000, created2.get("text").asString().length());
        assertEquals("Because C.", created2.get("explanation").asString());
        assertEquals("A", created2.get("options").get(0).get("letter").asString());
        assertEquals("a text", created2.get("options").get(0).get("text").asString());
        assertEquals("wrong", created2.get("options").get(0).get("rationale").asString());
        assertEquals("C", created2.get("options").get(1).get("letter").asString());
        assertEquals(0, exam.get("questions").get(0).get("options").size());
        assertTrue(exam.get("questions").get(0).get("explanation").isNull());
        long examId = exam.get("id").asLong();
        assertFalse(exam.get("published").asBoolean());
        JsonNode qs = exam.get("questions");
        assertEquals(List.of(a), json.convertValue(qs.get(0).get("topicIds"), List.class).stream()
                .map(x -> ((Number) x).longValue()).toList());
        assertEquals(List.of(a, b), ids(qs.get(0).get("topics")), "explicit ∪ pool question's topics");
        JsonNode t0 = qs.get(0).get("topics").get(0);
        assertEquals(chapter, t0.get("chapterId").asLong());
        assertEquals(2, t0.get("chapterNumber").asInt());
        assertEquals("Static testing", t0.get("chapterTitle").asString());
        assertEquals(1, t0.get("number").asInt());
        assertEquals("Alpha", t0.get("titleEn").asString());
        assertEquals("", t0.get("titleTr").asString());
        assertEquals(0, qs.get(3).get("topics").size());

        // Update: null keeps the explicit links, a list replaces them.
        List<Long> qIds = new ArrayList<>();
        qs.forEach(x -> qIds.add(x.get("id").asLong()));
        JsonNode updated = body(doPut(owner, "/api/exams/" + examId, Map.of("title", "Midterm", "examDate", "2026-11-10",
                "questions", List.of(
                        q("CHOICE", 2, "B", poolQ, null, qIds.get(0)),
                        q("CHOICE", 2, "C", null, List.of(g, a), qIds.get(1)),
                        q("OPEN", 4, null, null, List.of(g), qIds.get(2)),
                        q("FREE", 1, null, null, null, qIds.get(3))))).andExpect(status().isOk()));
        assertEquals(List.of(a, b), ids(updated.get("questions").get(0).get("topics")));
        assertEquals(List.of(a, g), ids(updated.get("questions").get(1).get("topics")));
        // Absent explanation / options leave them as they were.
        assertEquals("Because C.", updated.get("questions").get(1).get("explanation").asString());
        assertEquals(2, updated.get("questions").get(1).get("options").size());

        // A student's result: Q1 right, Q2 wrong, Q3 1/4, Q4 full.
        User s1 = newStudent(course);
        User s2 = newStudent(course);
        User s3 = newStudent(course);
        doPut(owner, "/api/exams/" + examId + "/results/" + s1.studentNo(), Map.of("answers", List.of(
                answer(qIds.get(0), "B", null), answer(qIds.get(1), "A", null), answer(qIds.get(2), "text", 1),
                answer(qIds.get(3), null, 1)))).andExpect(status().isOk());
        doPut(owner, "/api/exams/" + examId + "/results/" + s2.studentNo(), Map.of("answers", List.of(
                answer(qIds.get(0), "A", null), answer(qIds.get(1), "C", null), answer(qIds.get(2), null, 4),
                answer(qIds.get(3), null, 0)))).andExpect(status().isOk());

        String list = "/api/courses/" + course.id() + "/my-results";
        String detail = list + "/" + examId;
        assertEquals(0, body(doGet(s1, list).andExpect(status().isOk())).size());
        doGet(s1, detail).andExpect(status().isNotFound());
        doPost(s1, "/api/exams/" + examId + "/publish", null).andExpect(status().isForbidden());

        JsonNode published = body(doPost(owner, "/api/exams/" + examId + "/publish", null).andExpect(status().isOk()));
        assertTrue(published.get("published").asBoolean());
        assertEquals(1, body(doGet(s1, list)).size());

        JsonNode d = body(doGet(s1, detail).andExpect(status().isOk()));
        assertEquals(examId, d.get("examId").asLong());
        assertEquals("Midterm", d.get("title").asString());
        assertEquals("2026-11-10", d.get("examDate").asString());
        assertEquals(4, d.get("points").asDouble(), 1e-6);
        assertEquals(9, d.get("maxPoints").asDouble(), 1e-6);
        JsonNode dq = d.get("questions");
        assertEquals(4, dq.size());
        assertEquals(1, dq.get(0).get("position").asInt());
        assertEquals("CHOICE question", dq.get(0).get("text").asString());
        assertEquals("B", dq.get(0).get("myAnswer").asString());
        assertEquals("B", dq.get(0).get("correctChoice").asString());
        assertTrue(dq.get(0).get("correct").asBoolean());
        assertEquals(2, dq.get(0).get("points").asDouble(), 1e-6);
        assertEquals(List.of(a, b), ids(dq.get(0).get("topics")));
        assertFalse(dq.get(1).get("correct").asBoolean());
        assertEquals("A", dq.get(1).get("myAnswer").asString());
        assertEquals(0, dq.get(1).get("points").asDouble(), 1e-6);
        assertEquals("OPEN", dq.get(2).get("type").asString());
        assertTrue(dq.get(2).get("correct").isNull());
        assertEquals("text", dq.get(2).get("myAnswer").asString());
        assertTrue(dq.get(3).get("myAnswer").isNull());
        assertTrue(dq.get(3).get("correct").isNull());
        assertEquals("Because C.", dq.get(1).get("explanation").asString());
        assertEquals("right", dq.get(1).get("options").get(1).get("rationale").asString());
        assertEquals(0, dq.get(0).get("options").size());

        // Class aggregates: totals 4 and 6.
        JsonNode cls = d.get("stats");
        assertEquals(2, cls.get("participants").asInt());
        assertEquals(5, cls.get("average").asDouble(), 1e-6);
        assertEquals(5, cls.get("median").asDouble(), 1e-6);
        assertEquals(6, cls.get("maxPoints").asDouble(), 1e-6);
        JsonNode st0 = dq.get(0).get("stats");
        assertEquals(2, st0.get("answered").asInt());
        assertEquals(0.5, st0.get("correctShare").asDouble(), 1e-6);
        assertEquals(1, st0.get("averagePoints").asDouble(), 1e-6);
        assertEquals(Map.of("A", 1, "B", 1, "C", 0, "D", 0, "blank", 0),
                json.convertValue(st0.get("choiceCounts"), Map.class));
        assertEquals(0.5, dq.get(2).get("stats").get("correctShare").asDouble(), 1e-6);
        assertEquals(2.5, dq.get(2).get("stats").get("averagePoints").asDouble(), 1e-6);
        assertEquals(0, dq.get(3).get("stats").get("choiceCounts").size());

        // Gamma: Q2 + Q3 missed of 2; Alpha: Q2 missed of 2 (Q1 right); Beta: nothing missed → not listed.
        JsonNode review = d.get("reviewTopics");
        assertEquals(2, review.size(), review.toString());
        assertEquals(g, review.get(0).get("topic").get("id").asLong());
        assertEquals(2, review.get(0).get("missed").asInt());
        assertEquals(2, review.get(0).get("total").asInt());
        assertEquals(a, review.get(1).get("topic").get("id").asLong());
        assertEquals(1, review.get(1).get("missed").asInt());
        assertEquals(2, review.get(1).get("total").asInt());

        // No other students' data, no flags or attendance internals.
        String raw = text(doGet(s1, detail));
        for (String forbidden : List.of(s2.studentNo(), s1.studentNo(), s2.name(), "fullName", "flags", "personId",
                "\"note")) {
            assertFalse(raw.contains(forbidden), forbidden + " in " + raw);
        }
        doGet(s3, detail).andExpect(status().isNotFound());
        doGet(newStudent(foreign), detail).andExpect(status().isForbidden());
        doGet(s1, "/api/courses/" + foreign.id() + "/my-results/" + examId).andExpect(status().isForbidden());

        JsonNode unpublished = body(doPost(owner, "/api/exams/" + examId + "/unpublish", null)
                .andExpect(status().isOk()));
        assertFalse(unpublished.get("published").asBoolean());
        doGet(s1, detail).andExpect(status().isNotFound());
        assertEquals(0, body(doGet(s1, list)).size());
        assertEquals(0, body(doGet(s2, list)).size());
        // The staff exam list shows the flag too.
        assertFalse(body(doGet(owner, "/api/courses/" + course.id() + "/exams")).get(0).get("published").asBoolean());
    }

    @Test
    void poolBuilderCopiesOptionsAndExplanation() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, true, 5);
        long chapter = chapter(owner, course, 1, "Basics");
        User author = newStudent(course);
        long first = approvedQuestion(author, owner, course, chapter, "C");
        jdbc.sql("update question set explanation = 'Pool explanation' where id = :id").param("id", first).update();
        long examId = body(doPost(owner, "/api/pool/courses/" + course.id() + "/staff/exams",
                Map.of("title", "Quiz", "forms", 1, "counts", Map.of(String.valueOf(chapter), 1)))
                .andExpect(status().isOk())).get("id").asLong();
        JsonNode item = body(doGet(owner, "/api/exams/" + examId).andExpect(status().isOk())).get("questions").get(0);
        JsonNode pq = getQuestion(owner, first);
        assertEquals("C", item.get("correctChoice").asString());
        assertEquals("Pool explanation", item.get("explanation").asString());
        assertEquals(4, item.get("options").size());
        for (int i = 0; i < 4; i++) {
            String l = String.valueOf((char) ('a' + i));
            JsonNode o = item.get("options").get(i);
            assertEquals(l.toUpperCase(java.util.Locale.ROOT), o.get("letter").asString());
            assertEquals(pq.get("option_" + l).asString(), o.get("text").asString());
            assertEquals(pq.get("rationale_" + l).asString(), o.get("rationale").asString());
        }

        // Swap replaces the copied data with the new question's.
        long second = approvedQuestion(author, owner, course, chapter, "A");
        doPost(owner, "/api/pool/courses/" + course.id() + "/staff/exams/" + examId + "/items/" + item.get("id").asLong()
                + "/swap", null).andExpect(status().isOk());
        JsonNode swapped = body(doGet(owner, "/api/exams/" + examId)).get("questions").get(0);
        assertEquals(second, swapped.get("poolQuestionId").asLong());
        assertEquals(getQuestion(owner, second).get("option_b").asString(),
                swapped.get("options").get(1).get("text").asString());
        assertTrue(swapped.get("explanation").isNull());
        doGet(owner, "/api/pool/courses/" + course.id() + "/staff/exams/" + examId + "/forms/1")
                .andExpect(status().isOk());
    }
}
