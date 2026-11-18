package com.armikom.sis.pool;

import com.armikom.sis.support.ApiTestSupport;
import com.armikom.sis.support.PostgresTestcontainer;
import com.armikom.sis.support.TestClockConfig;
import com.armikom.sis.support.TestPeople;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The exam builder of the pool (staff): draws, forms with shuffled options, swap, remove, delete. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, PostgresTestcontainer.class, TestPeople.Config.class})
class PoolExamBuilderIntegrationTest extends ApiTestSupport {

    static final List<String> LETTERS = List.of("A", "B", "C", "D");

    /** Chapter 1: 6 approved questions, chapter 2: 4, chapter 3: 2 (keys cycle through A..D). */
    record Fx(User owner, CourseRef course, User reader, long ch1, long ch2, long ch3, List<Long> q1, List<Long> q2,
              List<Long> q3) {
        String base() { return "/api/pool/courses/" + course.id() + "/staff/exams"; }
    }

    Fx fx() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, true, 20);
        User author = newStudent(course);
        User reader = newStudent(course);
        long ch1 = chapter(owner, course, 1, "Fundamentals");
        long ch2 = chapter(owner, course, 2, "Test design");
        long ch3 = chapter(owner, course, 3, "Tools");
        List<Long> q1 = approved(author, owner, course, ch1, 6, 0);
        List<Long> q2 = approved(author, owner, course, ch2, 4, 1);
        List<Long> q3 = approved(author, owner, course, ch3, 2, 2);
        // One pending and one rejected question must never be drawn.
        submitQuestion(author, ch1, "A");
        long rejected = submitQuestion(author, ch1, "A");
        decide(owner, course, rejected, "REJECT", "No.");
        return new Fx(owner, course, reader, ch1, ch2, ch3, q1, q2, q3);
    }

    List<Long> approved(User author, User owner, CourseRef course, long chapter, int n, int keyShift) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(approvedQuestion(author, owner, course, chapter, LETTERS.get((i + keyShift) % 4)));
        }
        return ids;
    }

    ResultActions create(Fx f, String title, Integer forms, Map<Long, Integer> counts) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("title", title);
        if (forms != null) req.put("forms", forms);
        req.put("counts", counts);
        return doPost(f.owner(), f.base(), req);
    }

    long createOk(Fx f, String title, Integer forms, Map<Long, Integer> counts) throws Exception {
        return body(create(f, title, forms, counts).andExpect(status().isOk())).get("id").asLong();
    }

    /** The pool questions of the course by id (staff review list), with options and key. */
    Map<Long, JsonNode> poolQuestions(Fx f) throws Exception {
        Map<Long, JsonNode> m = new HashMap<>();
        body(doGet(f.owner(), "/api/pool/courses/" + f.course().id() + "/staff/questions?status=APPROVED"))
                .forEach(n -> m.put(n.get("id").asLong(), n));
        return m;
    }

    JsonNode form(Fx f, long exam, int form) throws Exception {
        return body(doGet(f.owner(), f.base() + "/" + exam + "/forms/" + form).andExpect(status().isOk()));
    }

    List<Map<String, Object>> rows(long examId) {
        return jdbc.sql("""
                select id, position, type, text, topic, max_points, choice_count, correct_choice, pool_question_id
                from exam_question where exam_id = ? order by position""").param(examId).query().listOfRows();
    }

    // ---- drawing --------------------------------------------------------------------------------

    @Test
    void drawingTooManyQuestionsSavesNothing() throws Exception {
        Fx f = fx();
        create(f, "Midterm", 2, Map.of(f.ch1(), 7)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Week 1 has only 6 usable approved questions, but you asked for 7."));
        // The first chapter alone would work: the failed draw of the second one rolls the whole exam back.
        create(f, "Midterm", 2, Map.of(f.ch1(), 2, f.ch2(), 99)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Week 2 has only 4 usable approved questions, but you asked for 99."));
        assertEquals(0, count("select count(*) from exam where course_id = ?", f.course().id()));
        assertEquals(0, count("select count(*) from exam_question eq join exam e on e.id = eq.exam_id "
                + "where e.course_id = ?", f.course().id()));
        assertTrue(body(doGet(f.owner(), f.base()).andExpect(status().isOk())).isEmpty());
    }

    @Test
    void createValidation() throws Exception {
        Fx f = fx();
        create(f, "  ", 2, Map.of(f.ch1(), 1)).andExpect(status().isBadRequest());
        create(f, "x".repeat(121), 2, Map.of(f.ch1(), 1)).andExpect(status().isBadRequest());
        create(f, "Quiz", 0, Map.of(f.ch1(), 1)).andExpect(status().isBadRequest());
        create(f, "Quiz", 5, Map.of(f.ch1(), 1)).andExpect(status().isBadRequest());
        create(f, "Quiz", 2, Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Ask for at least one question."));
        create(f, "Quiz", 2, Map.of(f.ch1(), 0)).andExpect(status().isBadRequest());
        // A chapter of another course is ignored, so asking only for it draws nothing.
        Fx other = fx();
        create(f, "Quiz", 2, Map.of(other.ch1(), 1)).andExpect(status().isBadRequest());
        assertEquals(0, count("select count(*) from exam where course_id = ?", f.course().id()));
        assertEquals(0, count("select count(*) from exam where course_id = ?", other.course().id()));
        // Forms default to 2.
        long id = body(doPost(f.owner(), f.base(), Map.of("title", "Default forms", "counts", Map.of(f.ch1(), 1)))
                .andExpect(status().isOk())).get("id").asLong();
        assertEquals(2, count("select forms from exam where id = ?", id));
    }

    @Test
    void twoFormsHaveTheSameQuestionsInDifferentOrder() throws Exception {
        Fx f = fx();
        long exam = createOk(f, "Final", 2, Map.of(f.ch1(), 4, f.ch2(), 3));
        Map<Long, JsonNode> pool = poolQuestions(f);

        JsonNode a = form(f, exam, 1);
        JsonNode b = form(f, exam, 2);
        assertEquals("A", a.get("form").asString());
        assertEquals("B", b.get("form").asString());
        assertEquals("Final", a.get("title").asString());
        assertEquals(7, a.get("questions").size());
        assertEquals(7, b.get("questions").size());

        Set<Long> idsA = new HashSet<>(), idsB = new HashSet<>();
        a.get("questions").forEach(q -> idsA.add(q.get("question_id").asLong()));
        b.get("questions").forEach(q -> idsB.add(q.get("question_id").asLong()));
        assertEquals(idsA, idsB, "both forms hold the same questions");
        assertEquals(7, idsA.size());
        assertTrue(f.q1().containsAll(idsA.stream().filter(f.q1()::contains).toList()));
        assertEquals(4, idsA.stream().filter(f.q1()::contains).count());
        assertEquals(3, idsA.stream().filter(f.q2()::contains).count());
        assertTrue(idsA.stream().noneMatch(f.q3()::contains));

        for (JsonNode form : List.of(a, b)) {
            int number = 0, lastChapter = 0;
            for (JsonNode q : form.get("questions")) {
                assertEquals(++number, q.get("number").asInt());
                int chapter = q.get("chapter_number").asInt();
                assertTrue(chapter >= lastChapter, "questions stay in chapter order");
                lastChapter = chapter;
                // The key of the form points at the pool question's correct option, wherever it moved to.
                JsonNode original = pool.get(q.get("question_id").asLong());
                assertNotNull(original);
                List<String> options = new ArrayList<>();
                q.get("options").forEach(o -> options.add(o.asString()));
                assertEquals(4, options.size());
                Set<String> originalOptions = new HashSet<>();
                for (String l : List.of("option_a", "option_b", "option_c", "option_d")) {
                    originalOptions.add(original.get(l).asString());
                }
                assertEquals(originalOptions, new HashSet<>(options), "options are a permutation");
                String key = q.get("correct").asString();
                assertTrue(LETTERS.contains(key));
                String expected = original.get("option_" + original.get("correct").asString().toLowerCase(java.util.Locale.ROOT)).asString();
                assertEquals(expected, options.get(LETTERS.indexOf(key)), "key matches after shuffling");
                assertEquals(original.get("stem").asString(), q.get("stem").asString());
            }
        }

        // Different order of questions or of options between the two forms (the chance of a full match is nil).
        List<Long> orderA = new ArrayList<>(), orderB = new ArrayList<>();
        a.get("questions").forEach(q -> orderA.add(q.get("question_id").asLong()));
        b.get("questions").forEach(q -> orderB.add(q.get("question_id").asLong()));
        Map<Long, List<String>> optsA = new HashMap<>(), optsB = new HashMap<>();
        a.get("questions").forEach(q -> optsA.put(q.get("question_id").asLong(), strings(q.get("options"))));
        b.get("questions").forEach(q -> optsB.put(q.get("question_id").asLong(), strings(q.get("options"))));
        assertTrue(!orderA.equals(orderB) || !optsA.equals(optsB), "forms differ");
        assertNotEquals(optsA, optsB, "the option order differs between forms");

        // Deterministic: the same form comes out the same on every request (a printed form matches its key).
        assertEquals(a, form(f, exam, 1));
        assertEquals(b, form(f, exam, 2));
        doGet(f.owner(), f.base() + "/" + exam + "/forms/3").andExpect(status().isNotFound());
        doGet(f.owner(), f.base() + "/" + exam + "/forms/0").andExpect(status().isNotFound());
    }

    static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    @Test
    void examRowsCarryThePoolQuestion() throws Exception {
        Fx f = fx();
        long exam = createOk(f, "Quiz 1", 3, Map.of(f.ch1(), 2, f.ch2(), 2, f.ch3(), 1));
        Map<Long, JsonNode> pool = poolQuestions(f);

        Map<String, Object> examRow = jdbc.sql("select course_id, title, forms, seed, created_at from exam where id = ?")
                .param(exam).query().singleRow();
        assertEquals(f.course().id(), ((Number) examRow.get("course_id")).longValue());
        assertEquals("Quiz 1", examRow.get("title"));
        assertEquals(3, ((Number) examRow.get("forms")).intValue());
        assertNotNull(examRow.get("seed"));
        assertNotNull(examRow.get("created_at"));

        List<Map<String, Object>> rows = rows(exam);
        assertEquals(5, rows.size());
        List<Integer> chapterNumbers = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            assertEquals(i + 1, ((Number) r.get("position")).intValue());
            assertEquals("CHOICE", r.get("type"));
            assertEquals(4, ((Number) r.get("choice_count")).intValue());
            assertEquals(0, new java.math.BigDecimal("1").compareTo((java.math.BigDecimal) r.get("max_points")));
            long poolId = ((Number) r.get("pool_question_id")).longValue();
            JsonNode q = pool.get(poolId);
            assertNotNull(q, "pool_question_id points at an approved question of the course");
            assertEquals(q.get("correct").asString(), r.get("correct_choice"));
            assertEquals(q.get("stem").asString(), r.get("text"));
            assertEquals(q.get("chapter_id").asLong() == f.ch1() ? "Fundamentals"
                    : q.get("chapter_id").asLong() == f.ch2() ? "Test design" : "Tools", r.get("topic"));
            chapterNumbers.add(q.get("chapter_number").asInt());
        }
        assertEquals(List.of(1, 1, 2, 2, 3), chapterNumbers, "exam questions follow chapter order");

        // The unified exam API sees the same rows, with the link to the pool.
        long examId = exam;
        JsonNode viaExamApi = body(doGet(f.owner(), "/api/exams/" + examId).andExpect(status().isOk()));
        assertEquals(5, viaExamApi.get("questions").size());
        assertEquals(3, viaExamApi.get("forms").asInt());
        for (JsonNode q : viaExamApi.get("questions")) {
            assertFalse(q.get("poolQuestionId").isNull());
            assertEquals("CHOICE", q.get("type").asString());
        }

        // The builder lists the exam with its question count; plain exams without pool questions stay out.
        doPost(f.owner(), "/api/courses/" + f.course().id() + "/exams",
                Map.of("title", "Plain exam", "questions", List.of())).andExpect(status().isCreated());
        JsonNode list = body(doGet(f.owner(), f.base()).andExpect(status().isOk()));
        assertEquals(1, list.size());
        assertEquals(exam, list.get(0).get("id").asLong());
        assertEquals(5, list.get(0).get("questions").asInt());
        assertEquals(3, list.get(0).get("forms").asInt());

        // The detail shows the items with the original key and the statistics.
        JsonNode detail = body(doGet(f.owner(), f.base() + "/" + exam).andExpect(status().isOk()));
        assertEquals(5, detail.get("items").size());
        assertTrue(detail.path("seed").isMissingNode(), "the shuffle seed stays private");
        JsonNode item = detail.get("items").get(0);
        assertEquals(pool.get(item.get("question_id").asLong()).get("correct").asString(), item.get("correct").asString());
        assertEquals(0, item.get("attempts").asInt());
    }

    @Test
    void flaggedQuestionsAreSkippedUnlessAskedOtherwise() throws Exception {
        Fx f = fx();
        // Chapter 3 has two approved questions; one gets an open report.
        attempt(f.reader(), f.q3().get(0), "A");
        doPost(f.reader(), "/api/pool/questions/" + f.q3().get(0) + "/flag", Map.of("reason", "The key looks wrong"))
                .andExpect(status().isOk());
        create(f, "Without flagged", 2, Map.of(f.ch3(), 2)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Week 3 has only 1 usable approved questions, but you asked for 2."));
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("title", "With flagged");
        req.put("counts", Map.of(f.ch3(), 2));
        req.put("skip_flagged", false);
        long id = body(doPost(f.owner(), f.base(), req).andExpect(status().isOk())).get("id").asLong();
        assertEquals(2, rows(id).size());
        // Resolving the report makes the question usable again.
        long flag = body(doGet(f.owner(), "/api/pool/courses/" + f.course().id() + "/staff/flags")).get(0)
                .get("id").asLong();
        doPost(f.owner(), "/api/pool/courses/" + f.course().id() + "/staff/flags/" + flag + "/resolve", null)
                .andExpect(status().isOk());
        createOk(f, "After resolve", 2, Map.of(f.ch3(), 2));
    }

    // ---- editing --------------------------------------------------------------------------------

    @Test
    void swapReplacesAQuestionOfTheSameChapter() throws Exception {
        Fx f = fx();
        long exam = createOk(f, "Quiz", 2, Map.of(f.ch1(), 3, f.ch2(), 1));
        Map<Long, JsonNode> pool = poolQuestions(f);
        JsonNode before = body(doGet(f.owner(), f.base() + "/" + exam));
        JsonNode first = before.get("items").get(0);
        long item = first.get("item_id").asLong();
        long oldQuestion = first.get("question_id").asLong();
        Set<Long> usedBefore = new HashSet<>();
        before.get("items").forEach(i -> usedBefore.add(i.get("question_id").asLong()));

        doPost(f.owner(), f.base() + "/" + exam + "/items/" + item + "/swap", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        JsonNode after = body(doGet(f.owner(), f.base() + "/" + exam));
        assertEquals(4, after.get("items").size());
        Set<Long> usedAfter = new HashSet<>();
        for (JsonNode i : after.get("items")) usedAfter.add(i.get("question_id").asLong());
        assertEquals(4, usedAfter.size(), "no question twice in one exam");
        assertFalse(usedAfter.contains(oldQuestion));
        usedAfter.removeAll(usedBefore);
        assertEquals(1, usedAfter.size());
        long replacement = usedAfter.iterator().next();
        assertTrue(f.q1().contains(replacement), "same chapter");

        // The row carries the new question's data.
        Map<String, Object> row = jdbc.sql("select text, correct_choice, pool_question_id from exam_question where id = ?")
                .param(item).query().singleRow();
        assertEquals(replacement, ((Number) row.get("pool_question_id")).longValue());
        assertEquals(pool.get(replacement).get("correct").asString(), row.get("correct_choice"));
        assertEquals(pool.get(replacement).get("stem").asString(), row.get("text"));

        // Chapter 3 has two questions and the exam uses both: nothing left to swap in.
        long small = createOk(f, "Small", 1, Map.of(f.ch3(), 2));
        long smallItem = body(doGet(f.owner(), f.base() + "/" + small)).get("items").get(0).get("item_id").asLong();
        doPost(f.owner(), f.base() + "/" + small + "/items/" + smallItem + "/swap", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This chapter has no other approved question to use."));
        // An item of another exam of the same course is not addressable through this exam.
        doPost(f.owner(), f.base() + "/" + exam + "/items/" + smallItem + "/swap", null)
                .andExpect(status().isNotFound());
        doPost(f.owner(), f.base() + "/" + exam + "/items/999999999/swap", null).andExpect(status().isNotFound());
    }

    @Test
    void removeRenumbersThePositions() throws Exception {
        Fx f = fx();
        long exam = createOk(f, "Quiz", 2, Map.of(f.ch1(), 2, f.ch2(), 2));
        List<Map<String, Object>> before = rows(exam);
        assertEquals(4, before.size());
        long removedItem = ((Number) before.get(1).get("id")).longValue();
        long keptA = ((Number) before.get(0).get("id")).longValue();
        long keptB = ((Number) before.get(2).get("id")).longValue();
        long keptC = ((Number) before.get(3).get("id")).longValue();

        doPost(f.owner(), f.base() + "/" + exam + "/items/" + removedItem + "/remove", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));

        List<Map<String, Object>> after = rows(exam);
        assertEquals(3, after.size());
        assertEquals(List.of(keptA, keptB, keptC), after.stream().map(r -> ((Number) r.get("id")).longValue()).toList());
        assertEquals(List.of(1, 2, 3), after.stream().map(r -> ((Number) r.get("position")).intValue()).toList());
        assertEquals(3, body(doGet(f.owner(), f.base() + "/" + exam)).get("items").size());
        assertEquals(3, body(doGet(f.owner(), f.base())).get(0).get("questions").asInt());
        assertEquals(3, form(f, exam, 1).get("questions").size());
    }

    @Test
    void deleteWorksWithoutResults() throws Exception {
        Fx f = fx();
        long exam = createOk(f, "Throwaway", 1, Map.of(f.ch1(), 1));
        doDelete(f.owner(), f.base() + "/" + exam).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        doGet(f.owner(), f.base() + "/" + exam).andExpect(status().isNotFound());
        assertEquals(0, count("select count(*) from exam_question where exam_id = ?", exam));
        doDelete(f.owner(), f.base() + "/" + exam).andExpect(status().isNotFound());
        // The pool questions themselves stay.
        assertEquals(12, poolQuestions(f).size());
    }

    @Test
    void examIsLockedOnceAResultExists() throws Exception {
        Fx f = fx();
        long exam = createOk(f, "Locked", 2, Map.of(f.ch1(), 2, f.ch2(), 1));
        JsonNode items = body(doGet(f.owner(), f.base() + "/" + exam)).get("items");
        long item = items.get(0).get("item_id").asLong();
        List<Map<String, Object>> before = rows(exam);

        // A result for a roster student (through the unified exam API).
        User student = newStudent(f.course());
        long examQuestion = ((Number) before.get(0).get("id")).longValue();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("questionId", examQuestion);
        answer.put("answer", "A");
        answer.put("points", 1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("form", "A");
        result.put("answers", List.of(answer));
        doPut(f.owner(), "/api/exams/" + exam + "/results/" + student.studentNo(), result).andExpect(status().isOk());

        String locked = "This exam already has results and cannot change.";
        doPost(f.owner(), f.base() + "/" + exam + "/items/" + item + "/swap", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(locked));
        doPost(f.owner(), f.base() + "/" + exam + "/items/" + item + "/remove", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(locked));
        doDelete(f.owner(), f.base() + "/" + exam).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(locked));
        assertEquals(before, rows(exam), "nothing changed");
        // Reading stays possible.
        doGet(f.owner(), f.base() + "/" + exam + "/forms/1").andExpect(status().isOk());

        // Without results (deleted again) the exam is editable once more.
        doDelete(f.owner(), "/api/exams/" + exam + "/results/" + student.studentNo()).andExpect(status().isNoContent());
        doPost(f.owner(), f.base() + "/" + exam + "/items/" + item + "/remove", null).andExpect(status().isOk());
        assertEquals(2, rows(exam).size());
    }

    // ---- access ---------------------------------------------------------------------------------

    @Test
    void studentsAndOtherCoursesCannotUseTheBuilder() throws Exception {
        Fx f = fx();
        long exam = createOk(f, "Private", 2, Map.of(f.ch1(), 2));
        long item = ((Number) rows(exam).get(0).get("id")).longValue();
        Map<String, Object> req = Map.of("title", "Sneaky", "counts", Map.of(f.ch1(), 1));

        doGet(f.reader(), f.base()).andExpect(status().isForbidden());
        doPost(f.reader(), f.base(), req).andExpect(status().isForbidden());
        doGet(f.reader(), f.base() + "/" + exam).andExpect(status().isForbidden());
        doGet(f.reader(), f.base() + "/" + exam + "/forms/1").andExpect(status().isForbidden());
        doDelete(f.reader(), f.base() + "/" + exam).andExpect(status().isForbidden());
        doGet(null, f.base()).andExpect(status().isUnauthorized());

        // Staff of another course: 403 on this course's paths, 404 for this exam through their own course's paths.
        Fx other = fx();
        doGet(other.owner(), f.base()).andExpect(status().isForbidden());
        doPost(other.owner(), f.base(), req).andExpect(status().isForbidden());
        doGet(other.owner(), f.base() + "/" + exam).andExpect(status().isForbidden());
        doDelete(other.owner(), f.base() + "/" + exam).andExpect(status().isForbidden());
        doGet(other.owner(), other.base() + "/" + exam).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Exam not found."));
        doGet(other.owner(), other.base() + "/" + exam + "/forms/1").andExpect(status().isNotFound());
        doDelete(other.owner(), other.base() + "/" + exam).andExpect(status().isNotFound());
        doPost(other.owner(), other.base() + "/" + exam + "/items/" + item + "/swap", null)
                .andExpect(status().isNotFound());
        doPost(other.owner(), other.base() + "/" + exam + "/items/" + item + "/remove", null)
                .andExpect(status().isNotFound());
        assertEquals(2, rows(exam).size());
        assertTrue(body(doGet(other.owner(), other.base())).isEmpty());
    }
}
