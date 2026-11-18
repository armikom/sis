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
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The question pool REST API (docs/API-POOL.md): access, chapters, questions, attempt-first, moderation, isolation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, PostgresTestcontainer.class, TestPeople.Config.class})
class PoolApiIntegrationTest extends ApiTestSupport {

    /** A pool course with an owner, one open chapter, an author with one approved question (key B) and a classmate. */
    record Fx(User owner, CourseRef course, long chapter, User author, User other, long question) {
        String staffBase() { return "/api/pool/courses/" + course.id() + "/staff"; }
    }

    Fx fx() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, true, 2);
        long chapter = chapter(owner, course, 1, "Introduction");
        User author = newStudent(course);
        User other = newStudent(course);
        long q = approvedQuestion(author, owner, course, chapter, "B");
        return new Fx(owner, course, chapter, author, other, q);
    }

    static void assertNoKeys(JsonNode q) {
        for (String f : List.of("correct", "rationale_a", "rationale_b", "rationale_c", "rationale_d", "reference",
                "explanation", "ai_status", "ai_review", "instructor_note")) {
            assertTrue(q.path(f).isMissingNode(), f + " must not be present: " + q);
        }
    }

    // ---- switch and membership ------------------------------------------------------------------

    @Test
    void poolDisabledIs404ForStudentsAnd409ForStaff() throws Exception {
        Fx f = fx();
        doPut(f.owner(), "/api/courses/" + f.course().id(), Map.of("poolEnabled", false)).andExpect(status().isOk());
        String me = "/api/pool/courses/" + f.course().id() + "/me";
        String chapters = "/api/pool/courses/" + f.course().id() + "/chapters";

        doGet(f.other(), me).andExpect(status().isNotFound());
        doGet(f.other(), chapters).andExpect(status().isNotFound());
        doGet(f.other(), "/api/pool/chapters/" + f.chapter()).andExpect(status().isNotFound());
        doGet(f.other(), "/api/pool/chapters/" + f.chapter() + "/questions").andExpect(status().isNotFound());
        doGet(f.other(), "/api/pool/questions/" + f.question()).andExpect(status().isNotFound());
        doPost(f.other(), "/api/pool/questions/" + f.question() + "/attempt", Map.of("selected", "A"))
                .andExpect(status().isNotFound());
        doPost(f.other(), "/api/pool/questions", questionBody(f.chapter(), "A")).andExpect(status().isNotFound());

        doGet(f.owner(), me).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("The question pool is not enabled for this course."));
        doGet(f.owner(), chapters).andExpect(status().isConflict());
        doGet(f.owner(), f.staffBase() + "/students").andExpect(status().isConflict());
        doGet(f.owner(), f.staffBase() + "/questions").andExpect(status().isConflict());
        doPost(f.owner(), f.staffBase() + "/chapters", Map.of("number", 5, "title", "X")).andExpect(status().isConflict());

        // Switching it back on restores everything, data included.
        doPut(f.owner(), "/api/courses/" + f.course().id(), Map.of("poolEnabled", true)).andExpect(status().isOk());
        doGet(f.other(), chapters).andExpect(status().isOk()).andExpect(jsonPath("$[0].approved").value(1));
    }

    @Test
    void nonMembersAndAnonymousAreRefused() throws Exception {
        Fx f = fx();
        User outsiderStaff = newStaff();   // staff of his own seed course only
        CourseRef otherCourse = newCourse(outsiderStaff, true, 2);
        User outsider = newStudent(otherCourse);

        doGet(null, "/api/pool/courses/" + f.course().id() + "/chapters").andExpect(status().isUnauthorized());
        doGet(null, "/api/pool/questions/" + f.question()).andExpect(status().isUnauthorized());

        for (User u : List.of(outsider, outsiderStaff)) {
            doGet(u, "/api/pool/courses/" + f.course().id() + "/me").andExpect(status().isForbidden());
            doGet(u, "/api/pool/courses/" + f.course().id() + "/chapters").andExpect(status().isForbidden());
            doGet(u, "/api/pool/courses/" + f.course().id() + "/questions/mine").andExpect(status().isForbidden());
            doGet(u, "/api/pool/chapters/" + f.chapter()).andExpect(status().isForbidden());
            doGet(u, "/api/pool/chapters/" + f.chapter() + "/questions").andExpect(status().isForbidden());
            doGet(u, "/api/pool/questions/" + f.question()).andExpect(status().isForbidden());
            doPost(u, "/api/pool/questions/" + f.question() + "/attempt", Map.of("selected", "A"))
                    .andExpect(status().isForbidden());
            doPost(u, "/api/pool/questions", questionBody(f.chapter(), "A")).andExpect(status().isForbidden());
            doGet(u, f.staffBase() + "/students").andExpect(status().isForbidden());
        }
        // A student of the course is a member, but not staff.
        doGet(f.other(), f.staffBase() + "/students").andExpect(status().isForbidden());
        doGet(f.other(), f.staffBase() + "/questions").andExpect(status().isForbidden());
        doPost(f.other(), f.staffBase() + "/chapters", Map.of("number", 9, "title", "Nope"))
                .andExpect(status().isForbidden());
        doPost(f.other(), f.staffBase() + "/questions/" + f.question() + "/decision", Map.of("action", "REJECT",
                "note", "no")).andExpect(status().isForbidden());
        // Nothing changed.
        assertEquals("APPROVED", getQuestion(f.author(), f.question()).get("status").asString());
    }

    // ---- chapters -------------------------------------------------------------------------------

    @Test
    void chapterLifecycle() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, true, 2);
        String base = "/api/pool/courses/" + course.id() + "/staff/chapters";
        long c1 = chapter(owner, course, 1, "Basics");
        long c2 = chapter(owner, course, 2, "Testing");

        doPost(owner, base, Map.of("number", 2, "title", "Again")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This course already has a chapter 2."));
        doPost(owner, base, Map.of("number", 0, "title", "Zero")).andExpect(status().isBadRequest());
        doPost(owner, base, Map.of("number", 1000, "title", "Big")).andExpect(status().isBadRequest());
        doPost(owner, base, Map.of("number", 3, "title", "  ")).andExpect(status().isBadRequest());
        doPost(owner, base, Map.of("title", "No number")).andExpect(status().isBadRequest());

        // Renumbering to a taken number conflicts, to a free one works.
        doPut(owner, base + "/" + c2, Map.of("number", 1)).andExpect(status().isConflict());
        doPut(owner, base + "/" + c2, Map.of("number", 7, "title", "Testing 2", "open", false))
                .andExpect(status().isOk());
        User student = newStudent(course);
        doGet(student, "/api/pool/courses/" + course.id() + "/chapters").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").value(1))
                .andExpect(jsonPath("$[1].number").value(7))
                .andExpect(jsonPath("$[1].title").value("Testing 2"))
                .andExpect(jsonPath("$[1].open").value(false));

        // A chapter with questions cannot be deleted; an empty one can.
        submitQuestion(student, c1, "A");
        doDelete(owner, base + "/" + c1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "This chapter has questions and cannot be deleted. Close it instead."));
        doDelete(owner, base + "/" + c2).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        doGet(student, "/api/pool/chapters/" + c2).andExpect(status().isNotFound());
        doDelete(owner, base + "/" + c2).andExpect(status().isNotFound());
        doGet(student, "/api/pool/chapters/" + c1).andExpect(status().isOk())
                .andExpect(jsonPath("$.course_id").value(course.id()));
    }

    @Test
    void closedChapterTakesNoStudentQuestionsButStaffMayWrite() throws Exception {
        Fx f = fx();
        doPut(f.owner(), f.staffBase() + "/chapters/" + f.chapter(), Map.of("open", false)).andExpect(status().isOk());
        doPost(f.other(), "/api/pool/questions", questionBody(f.chapter(), "A")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This chapter is closed for new questions."));
        doPost(f.owner(), "/api/pool/questions", questionBody(f.chapter(), "A")).andExpect(status().isOk());
    }

    // ---- writing questions ----------------------------------------------------------------------

    @Test
    void perChapterLimitIgnoresRejectedAndInvalidQuestions() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, true, 2);
        long chapter = chapter(owner, course, 1, "Basics");
        long chapter2 = chapter(owner, course, 2, "More");
        User s = newStudent(course);

        // Refused requests are not stored, so they do not use up the limit.
        Map<String, Object> shortStem = questionBody(chapter, "A");
        shortStem.put("stem", "too short");
        doPost(s, "/api/pool/questions", shortStem).andExpect(status().isBadRequest());
        Map<String, Object> sameOptions = questionBody(chapter, "A");
        sameOptions.put("option_b", String.valueOf(sameOptions.get("option_a")).toUpperCase(java.util.Locale.ROOT));
        doPost(s, "/api/pool/questions", sameOptions).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The four options must be different."));
        Map<String, Object> badKey = questionBody(chapter, "E");
        doPost(s, "/api/pool/questions", badKey).andExpect(status().isBadRequest());
        Map<String, Object> noReason = questionBody(chapter, "A");
        noReason.put("rationale_c", "short");
        doPost(s, "/api/pool/questions", noReason).andExpect(status().isBadRequest());
        assertEquals(0, count("select count(*) from question where author_id = ?", s.id()));

        long q1 = submitQuestion(s, chapter, "A");
        submitQuestion(s, chapter, "C");
        doPost(s, "/api/pool/questions", questionBody(chapter, "D")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You already have 2 questions in this chapter."));
        // The limit is per chapter.
        submitQuestion(s, chapter2, "D");

        // A rejected question frees a place.
        decide(owner, course, q1, "REJECT", "Not about this chapter.");
        submitQuestion(s, chapter, "B");
        doPost(s, "/api/pool/questions", questionBody(chapter, "D")).andExpect(status().isBadRequest());

        // Staff are exempt from the limit.
        for (int i = 0; i < 4; i++) submitQuestion(owner, chapter, "A");

        // My questions: all of them, rejected included, with the instructor's note.
        JsonNode mine = body(doGet(s, "/api/pool/courses/" + course.id() + "/questions/mine")
                .andExpect(status().isOk()));
        assertEquals(4, mine.size());
        JsonNode rejected = null;
        for (JsonNode m : mine) if (m.get("id").asLong() == q1) rejected = m;
        assertNotNull(rejected);
        assertEquals("REJECTED", rejected.get("status").asString());
        assertEquals("Not about this chapter.", rejected.get("instructor_note").asString());
    }

    @Test
    void aiIsOffWithoutAKey() throws Exception {
        Fx f = fx();
        doGet(f.author(), "/api/pool/courses/" + f.course().id() + "/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.ai_enabled").value(false))
                .andExpect(jsonPath("$.staff").value(false))
                .andExpect(jsonPath("$.nickname").value(f.author().nickname()))
                .andExpect(jsonPath("$.questions_per_chapter").value(2));
        // The AI check is skipped, never started.
        long q = submitQuestion(f.other(), f.chapter(), "A");
        assertEquals("SKIPPED", getQuestion(f.other(), q).get("ai_status").asString());
        doPost(f.owner(), f.staffBase() + "/questions/" + q + "/ai-check", null).andExpect(status().isOk());
        assertEquals("SKIPPED", getQuestion(f.other(), q).get("ai_status").asString());
        // Ask-AI answers 400 once the question is answered.
        attempt(f.other(), f.question(), "A");
        doPost(f.other(), "/api/pool/questions/" + f.question() + "/ask-ai", Map.of("body", "Why is B right?"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The AI helper is switched off."));
        assertEquals(0, count("select count(*) from question_comment where question_id = ?", f.question()));
    }

    @Test
    void authorEditsUntilApproved() throws Exception {
        Fx f = fx();
        long q = submitQuestion(f.other(), f.chapter(), "A");
        Map<String, Object> edit = questionBody(f.chapter(), "C");
        edit.put("stem", "Edited stem: what does the reference say about this topic?");

        doPut(f.other(), "/api/pool/questions/" + q, edit).andExpect(status().isOk());
        JsonNode edited = getQuestion(f.other(), q);
        assertEquals("PENDING", edited.get("status").asString());
        assertEquals("C", edited.get("correct").asString());
        assertTrue(edited.get("stem").asString().startsWith("Edited stem"));

        // Not the author: refused (404), and not by staff through this endpoint either.
        doPut(f.author(), "/api/pool/questions/" + q, edit).andExpect(status().isNotFound());
        doPut(f.owner(), "/api/pool/questions/" + q, edit).andExpect(status().isNotFound());
        doDelete(f.author(), "/api/pool/questions/" + q).andExpect(status().isNotFound());

        // Sent back for revision: still editable, and an edit puts it back in the queue.
        decide(f.owner(), f.course(), q, "REVISE", "Make the stem shorter.");
        assertEquals("NEEDS_REVISION", getQuestion(f.other(), q).get("status").asString());
        doPut(f.other(), "/api/pool/questions/" + q, edit).andExpect(status().isOk());
        assertEquals("PENDING", getQuestion(f.other(), q).get("status").asString());

        // Approved: frozen for the author, staff can still fix wording without changing the state.
        decide(f.owner(), f.course(), q, "APPROVE", null);
        doPut(f.other(), "/api/pool/questions/" + q, edit).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This question is already approved and cannot change."));
        doDelete(f.other(), "/api/pool/questions/" + q).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("An approved question cannot be deleted."));
        Map<String, Object> fix = questionBody(f.chapter(), "C");
        fix.put("stem", "Fixed by staff: which statement is correct according to the chapter?");
        doPut(f.owner(), f.staffBase() + "/questions/" + q, fix).andExpect(status().isOk());
        JsonNode fixed = getQuestion(f.other(), q);
        assertEquals("APPROVED", fixed.get("status").asString());
        assertTrue(fixed.get("stem").asString().startsWith("Fixed by staff"));

        // A pending question can be deleted by its author.
        long q2 = submitQuestion(f.other(), f.chapter(), "A");
        doDelete(f.other(), "/api/pool/questions/" + q2).andExpect(status().isOk());
        doGet(f.other(), "/api/pool/questions/" + q2).andExpect(status().isNotFound());
    }

    // ---- attempt first --------------------------------------------------------------------------

    @Test
    void keyAndDiscussionAreHiddenUntilTheStudentAnswers() throws Exception {
        Fx f = fx();
        String url = "/api/pool/questions/" + f.question();

        JsonNode before = getQuestion(f.other(), f.question());
        assertNoKeys(before);
        assertFalse(before.get("revealed").asBoolean());
        assertTrue(before.get("my_selected").isNull());
        assertFalse(before.get("mine").asBoolean());
        assertEquals(f.author().nickname(), before.get("author").asString());
        // The stem and the four options are there; the raw JSON carries no hint of the key.
        for (String o : List.of("option_a", "option_b", "option_c", "option_d", "stem")) {
            assertFalse(before.path(o).isMissingNode());
        }
        String rawBefore = text(doGet(f.other(), url));
        assertFalse(rawBefore.contains("Reason for option"), rawBefore);
        assertFalse(rawBefore.contains("Chapter notes"), rawBefore);

        // The discussion is closed.
        doGet(f.other(), url + "/comments").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Answer the question first."));
        doPost(f.other(), url + "/comments", Map.of("body", "I think it is B")).andExpect(status().isForbidden());
        doPost(f.other(), url + "/flag", Map.of("reason", "This looks wrong to me")).andExpect(status().isForbidden());
        doPost(f.other(), url + "/ask-ai", Map.of("body", "Help?")).andExpect(status().isForbidden());
        assertEquals(0, count("select count(*) from question_comment where question_id = ?", f.question()));
        assertEquals(0, count("select count(*) from question_flag where question_id = ?", f.question()));
        // The chapter list does not leak the key either.
        String list = text(doGet(f.other(), "/api/pool/chapters/" + f.chapter() + "/questions")
                .andExpect(status().isOk()));
        assertFalse(list.contains("\"correct\""), list);
        assertFalse(list.contains("rationale"), list);

        // After the answer the key, the reasons and the thread are open.
        JsonNode after = attempt(f.other(), f.question(), "A");
        assertTrue(after.get("revealed").asBoolean());
        assertEquals("A", after.get("my_selected").asString());
        assertEquals("B", after.get("correct").asString());
        assertTrue(after.get("rationale_a").asString().startsWith("Reason for option A"));
        assertFalse(after.path("reference").isMissingNode());
        assertTrue(after.has("explanation"));
        doGet(f.other(), url + "/comments").andExpect(status().isOk());
        doPost(f.other(), url + "/comments", Map.of("body", "I chose A and was wrong")).andExpect(status().isOk());
        doPost(f.other(), url + "/flag", Map.of("reason", "The reason for A is confusing")).andExpect(status().isOk());

        // A third student is still locked out; the author and staff are not.
        User third = newStudent(f.course());
        assertNoKeys(getQuestion(third, f.question()));
        doGet(third, url + "/comments").andExpect(status().isForbidden());
        assertEquals("B", getQuestion(f.author(), f.question()).get("correct").asString());
        assertEquals("B", getQuestion(f.owner(), f.question()).get("correct").asString());
        doGet(f.author(), url + "/comments").andExpect(status().isOk());
        doGet(f.owner(), url + "/comments").andExpect(status().isOk());
    }

    @Test
    void unapprovedQuestionsAreInvisibleAndCannotBeAnswered() throws Exception {
        Fx f = fx();
        long pending = submitQuestion(f.author(), f.chapter(), "A");
        doGet(f.other(), "/api/pool/questions/" + pending).andExpect(status().isNotFound());
        doPost(f.other(), "/api/pool/questions/" + pending + "/attempt", Map.of("selected", "A"))
                .andExpect(status().isNotFound());
        // The author sees all of it (key and AI fields), staff too.
        JsonNode own = getQuestion(f.author(), pending);
        assertEquals("A", own.get("correct").asString());
        assertTrue(own.get("mine").asBoolean());
        assertFalse(own.path("ai_status").isMissingNode());
        assertEquals("A", getQuestion(f.owner(), pending).get("correct").asString());
        // The chapter list shows waiting stems (for duplicate checks) but never a rejected question of someone else.
        long rejected = submitQuestion(f.other(), f.chapter(), "A");
        decide(f.owner(), f.course(), rejected, "REJECT", "Duplicate of another question.");
        JsonNode ids = body(doGet(f.author(), "/api/pool/chapters/" + f.chapter() + "/questions")
                .andExpect(status().isOk()));
        List<Long> seen = ids.valueStream().map(n -> n.get("id").asLong()).toList();
        assertTrue(seen.contains(pending));
        assertTrue(seen.contains(f.question()));
        assertFalse(seen.contains(rejected));
        JsonNode ownList = body(doGet(f.other(), "/api/pool/chapters/" + f.chapter() + "/questions")
                .andExpect(status().isOk()));
        assertTrue(ownList.valueStream().anyMatch(n -> n.get("id").asLong() == rejected));
        doGet(f.author(), "/api/pool/questions/" + rejected).andExpect(status().isNotFound());
    }

    @Test
    void onlyTheFirstAnswerCounts() throws Exception {
        Fx f = fx();
        assertEquals("A", attempt(f.other(), f.question(), "A").get("my_selected").asString());
        // A second attempt answers 200 with the stored answer, nothing changes.
        JsonNode again = attempt(f.other(), f.question(), "B");
        assertEquals("A", again.get("my_selected").asString());
        assertEquals("A", getQuestion(f.other(), f.question()).get("my_selected").asString());
        assertEquals(1, count("select count(*) from question_attempt where question_id = ? and person_id = ?",
                f.question(), f.other().id()));
        assertFalse(jdbc.sql("select correct from question_attempt where question_id = ? and person_id = ?")
                .param(f.question()).param(f.other().id()).query(Boolean.class).single());

        JsonNode row = studentRow(f, f.other().id());
        assertEquals(1, row.get("attempts").asInt());
        assertEquals(0, row.get("correct").asInt());

        // Bad letters are refused and store nothing.
        User third = newStudent(f.course());
        doPost(third, "/api/pool/questions/" + f.question() + "/attempt", Map.of("selected", "E"))
                .andExpect(status().isBadRequest());
        doPost(third, "/api/pool/questions/" + f.question() + "/attempt", Map.of("selected", ""))
                .andExpect(status().isBadRequest());
        assertEquals(0, count("select count(*) from question_attempt where person_id = ?", third.id()));
        // A lower-case letter is accepted.
        assertEquals("B", attempt(third, f.question(), "b").get("my_selected").asString());
    }

    @Test
    void authorAndStaffCannotAnswer() throws Exception {
        Fx f = fx();
        for (User u : List.of(f.author(), f.owner())) {
            doPost(u, "/api/pool/questions/" + f.question() + "/attempt", Map.of("selected", "B"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("You cannot answer this question."));
        }
        assertEquals(0, count("select count(*) from question_attempt where question_id = ?", f.question()));
    }

    // ---- who sees which name --------------------------------------------------------------------

    @Test
    void studentsSeeNicknamesAndStaffSeeRealNames() throws Exception {
        Fx f = fx();
        String url = "/api/pool/questions/" + f.question();
        attempt(f.other(), f.question(), "B");
        User third = newStudent(f.course());
        attempt(third, f.question(), "C");

        long c1 = commentId(f.other(), f.question(), "My comment as a student", null);
        commentId(f.owner(), f.question(), "Staff comment for everyone", c1);

        // The third student sees nicknames, "Instructor" for staff, and no real name anywhere.
        JsonNode comments = body(doGet(third, url + "/comments").andExpect(status().isOk()));
        assertEquals(2, comments.size());
        assertEquals(f.other().nickname(), comments.get(0).get("author").asString());
        assertTrue(comments.get(0).path("real_name").isNull() || comments.get(0).path("real_name").isMissingNode());
        assertEquals("Instructor", comments.get(1).get("author").asString());
        assertEquals(c1, comments.get(1).get("parent_id").asLong());
        assertFalse(comments.get(0).get("mine").asBoolean());
        assertFalse(comments.get(0).get("wrote_question").asBoolean());
        for (String raw : List.of(text(doGet(third, url + "/comments")), text(doGet(third, url)),
                text(doGet(third, "/api/pool/chapters/" + f.chapter() + "/questions")),
                text(doGet(f.other(), "/api/pool/courses/" + f.course().id() + "/questions/mine")))) {
            assertFalse(raw.contains(f.other().name()), raw);
            assertFalse(raw.contains(f.author().name()), raw);
            assertFalse(raw.contains(f.owner().name()), raw);
        }
        assertEquals(f.author().nickname(), getQuestion(third, f.question()).get("author").asString());

        // Staff see the real name next to the nickname.
        JsonNode staffView = body(doGet(f.owner(), url + "/comments").andExpect(status().isOk()));
        assertEquals(f.other().name(), staffView.get(0).get("real_name").asString());
        assertEquals(f.other().nickname(), staffView.get(0).get("author").asString());
        JsonNode review = body(doGet(f.owner(), f.staffBase() + "/questions?status=APPROVED")
                .andExpect(status().isOk()));
        assertEquals(f.author().name(), review.get(0).get("author_name").asString());
        assertEquals(f.author().nickname(), review.get(0).get("author").asString());
    }

    // ---- decisions ------------------------------------------------------------------------------

    @Test
    void decisionFlow() throws Exception {
        Fx f = fx();
        long q = submitQuestion(f.other(), f.chapter(), "D");
        String decision = f.staffBase() + "/questions/" + q + "/decision";

        // Counts and the review queue.
        JsonNode pending = body(doGet(f.owner(), f.staffBase() + "/questions?status=PENDING")
                .andExpect(status().isOk()));
        assertEquals(1, pending.size());
        assertEquals(q, pending.get(0).get("id").asLong());
        assertEquals("D", pending.get(0).get("correct").asString());
        assertEquals(1, body(doGet(f.owner(), f.staffBase() + "/questions?status=PENDING&chapter_id=" + f.chapter()))
                .size());
        assertEquals(1, body(doGet(f.owner(), f.staffBase() + "/questions?status=PENDING&chapterId=" + f.chapter()))
                .size());
        assertEquals(0, body(doGet(f.owner(), f.staffBase() + "/questions?status=PENDING&chapter_id=999999999"))
                .size());

        // Revise and reject need a note, an unknown action is refused, and nothing changes meanwhile.
        for (String action : List.of("REVISE", "REJECT")) {
            doPost(f.owner(), decision, Map.of("action", action)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Write a note so the student knows what to change."));
            Map<String, Object> blank = new LinkedHashMap<>();
            blank.put("action", action);
            blank.put("note", "   ");
            doPost(f.owner(), decision, blank).andExpect(status().isBadRequest());
        }
        doPost(f.owner(), decision, Map.of("action", "MAYBE", "note", "x")).andExpect(status().isBadRequest());
        assertEquals("PENDING", getQuestion(f.other(), q).get("status").asString());

        // Revise: the author reads the note, other students do not see the question.
        doPost(f.owner(), decision, Map.of("action", "REVISE", "note", "Reword the stem."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        JsonNode revised = getQuestion(f.other(), q);
        assertEquals("NEEDS_REVISION", revised.get("status").asString());
        assertEquals("Reword the stem.", revised.get("instructor_note").asString());
        doGet(f.author(), "/api/pool/questions/" + q).andExpect(status().isNotFound());

        // Approve: visible to everybody, note cleared, approved_at set.
        decide(f.owner(), f.course(), q, "APPROVE", null);
        assertEquals("APPROVED", getQuestion(f.author(), q).get("status").asString());
        assertTrue(getQuestion(f.other(), q).get("instructor_note").isNull());
        assertEquals(1, count("select count(*) from question where id = ? and approved_at is not null", q));

        // Reject (even an approved one, as in question-pool): hidden from others again, approved_at cleared.
        decide(f.owner(), f.course(), q, "REJECT", "Out of scope.");
        doGet(f.author(), "/api/pool/questions/" + q).andExpect(status().isNotFound());
        assertEquals("REJECTED", getQuestion(f.other(), q).get("status").asString());
        assertEquals(1, count("select count(*) from question where id = ? and approved_at is null", q));

        Map<String, Long> counts = new java.util.HashMap<>();
        body(doGet(f.owner(), f.staffBase() + "/questions/counts").andExpect(status().isOk()))
                .forEach(n -> counts.put(n.get("status").asString(), n.get("count").asLong()));
        assertEquals(1L, counts.get("APPROVED"));
        assertEquals(1L, counts.get("REJECTED"));
        assertNull(counts.get("PENDING"));

        doPost(f.owner(), f.staffBase() + "/questions/999999999/decision", Map.of("action", "APPROVE"))
                .andExpect(status().isNotFound());
    }

    // ---- flags and comments ---------------------------------------------------------------------

    @Test
    void flagsAreResolvedByStaff() throws Exception {
        Fx f = fx();
        String url = "/api/pool/questions/" + f.question();
        attempt(f.other(), f.question(), "A");
        doPost(f.other(), url + "/flag", Map.of("reason", "bad")).andExpect(status().isBadRequest());
        doPost(f.other(), url + "/flag", Map.of("reason", "x".repeat(501))).andExpect(status().isBadRequest());
        doPost(f.other(), url + "/flag", Map.of("reason", "The reason for option A is wrong"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));

        JsonNode flags = body(doGet(f.owner(), f.staffBase() + "/flags").andExpect(status().isOk()));
        assertEquals(1, flags.size());
        long flagId = flags.get(0).get("id").asLong();
        assertFalse(flags.get(0).get("resolved").asBoolean());
        assertEquals(f.question(), flags.get(0).get("question_id").asLong());
        assertEquals(f.other().name(), flags.get(0).get("student_name").asString());
        assertEquals(f.other().nickname(), flags.get(0).get("nickname").asString());
        assertEquals(1, body(doGet(f.owner(), f.staffBase() + "/questions?status=APPROVED")).get(0)
                .get("open_flags").asInt());

        doGet(f.other(), f.staffBase() + "/flags").andExpect(status().isForbidden());
        doPost(f.other(), f.staffBase() + "/flags/" + flagId + "/resolve", null).andExpect(status().isForbidden());
        assertFalse(body(doGet(f.owner(), f.staffBase() + "/flags")).get(0).get("resolved").asBoolean());

        doPost(f.owner(), f.staffBase() + "/flags/" + flagId + "/resolve", null).andExpect(status().isOk());
        assertTrue(body(doGet(f.owner(), f.staffBase() + "/flags")).get(0).get("resolved").asBoolean());
        assertEquals(0, body(doGet(f.owner(), f.staffBase() + "/questions?status=APPROVED")).get(0)
                .get("open_flags").asInt());
        doPost(f.owner(), f.staffBase() + "/flags/999999999/resolve", null).andExpect(status().isNotFound());
    }

    @Test
    void commentsCanBeHiddenByStaff() throws Exception {
        Fx f = fx();
        String url = "/api/pool/questions/" + f.question();
        attempt(f.other(), f.question(), "B");
        User third = newStudent(f.course());
        attempt(third, f.question(), "B");

        long parent = commentId(f.other(), f.question(), "First comment", null);
        long reply = commentId(third, f.question(), "A reply to the first", parent);
        doPost(third, url + "/comments", Map.of("body", "")).andExpect(status().isBadRequest());
        doPost(third, url + "/comments", Map.of("body", "x".repeat(2001))).andExpect(status().isBadRequest());
        // A parent from another question is refused.
        long otherQuestion = approvedQuestion(f.author(), f.owner(), f.course(), f.chapter(), "A");
        attempt(third, otherQuestion, "A");
        Map<String, Object> badParent = new LinkedHashMap<>();
        badParent.put("body", "Wrong thread");
        badParent.put("parent_id", parent);
        doPost(third, "/api/pool/questions/" + otherQuestion + "/comments", badParent)
                .andExpect(status().isBadRequest());

        assertEquals(2, body(doGet(third, url + "/comments")).size());
        doPost(f.other(), f.staffBase() + "/comments/" + parent + "/hide", null).andExpect(status().isForbidden());
        doPost(f.owner(), f.staffBase() + "/comments/" + parent + "/hide", null).andExpect(status().isOk());
        JsonNode left = body(doGet(third, url + "/comments"));
        assertEquals(1, left.size());
        assertEquals(reply, left.get(0).get("id").asLong());
        // The text stays for the participation report.
        JsonNode detail = body(doGet(f.owner(), f.staffBase() + "/students/" + f.other().id())
                .andExpect(status().isOk()));
        assertEquals(1, detail.get("comments").size());
        assertTrue(detail.get("comments").get(0).get("deleted").asBoolean());
        assertEquals("First comment", detail.get("comments").get(0).get("body").asString());
        assertEquals(0, studentRow(f, f.other().id()).get("comments").asInt());
        doPost(f.owner(), f.staffBase() + "/comments/999999999/hide", null).andExpect(status().isNotFound());
    }

    // ---- participation report -------------------------------------------------------------------

    @Test
    void participationReportCountsWithinTheCourse() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, true, 5);
        long ch1 = chapter(owner, course, 1, "One");
        long ch2 = chapter(owner, course, 2, "Two");
        User writer = newStudent(course);
        User solver = newStudent(course);
        User idle = newStudent(course);
        User removed = newStudent(course);

        long w1 = approvedQuestion(writer, owner, course, ch1, "A");
        long w2 = approvedQuestion(writer, owner, course, ch2, "B");
        long w3 = submitQuestion(writer, ch2, "C");                    // pending
        long w4 = submitQuestion(writer, ch2, "D");
        decide(owner, course, w4, "REJECT", "No.");                     // rejected: not in "chapters"
        long solverOwn = approvedQuestion(solver, owner, course, ch1, "C");
        assertTrue(w3 > 0 && solverOwn > 0);

        attempt(solver, w1, "A");                                      // correct
        attempt(solver, w2, "A");                                      // wrong
        attempt(removed, w1, "A");
        commentId(solver, w1, "Nice question", null);
        doPost(solver, "/api/pool/questions/" + w2 + "/flag", Map.of("reason", "I do not agree with the key"))
                .andExpect(status().isOk());

        // The same person is active in another course: those numbers must not show up here.
        User ownerB = newStaff();
        CourseRef courseB = newCourse(ownerB, true, 5);
        long chB = chapter(ownerB, courseB, 1, "B one");
        enroll(courseB, solver);
        long qB = approvedQuestion(newStudent(courseB), ownerB, courseB, chB, "A");
        attempt(solver, qB, "A");
        commentId(solver, qB, "Elsewhere", null);

        // A student who leaves the roster drops out of the report.
        doDelete(owner, "/api/courses/" + course.id() + "/roster/" + removed.id()).andExpect(status().isNoContent());

        JsonNode report = body(doGet(owner, "/api/pool/courses/" + course.id() + "/staff/students")
                .andExpect(status().isOk()));
        assertEquals(3, report.size(), report.toString());
        Fx f = new Fx(owner, course, ch1, writer, solver, w1);
        JsonNode w = studentRow(f, writer.id());
        assertEquals(4, w.get("questions").asInt());
        assertEquals(2, w.get("approved").asInt());
        assertEquals(2, w.get("chapters").asInt());
        assertEquals(0, w.get("attempts").asInt());
        assertEquals(writer.name(), w.get("full_name").asString());
        assertEquals(writer.studentNo(), w.get("student_no").asString());

        JsonNode s = studentRow(f, solver.id());
        assertEquals(1, s.get("questions").asInt());
        assertEquals(1, s.get("approved").asInt());
        assertEquals(1, s.get("chapters").asInt());
        assertEquals(2, s.get("attempts").asInt());
        assertEquals(1, s.get("correct").asInt());
        assertEquals(1, s.get("comments").asInt());
        assertEquals(1, s.get("flags").asInt());

        JsonNode i = studentRow(f, idle.id());
        for (String k : List.of("questions", "approved", "chapters", "attempts", "correct", "comments", "flags")) {
            assertEquals(0, i.get(k).asInt(), k);
        }
        assertTrue(report.valueStream().noneMatch(n -> n.get("id").asLong() == removed.id()));

        // Student detail.
        JsonNode d = body(doGet(owner, "/api/pool/courses/" + course.id() + "/staff/students/" + solver.id())
                .andExpect(status().isOk()));
        assertEquals(solver.studentNo(), d.get("student_no").asString());
        assertEquals(1, d.get("questions").size());
        assertEquals(1, d.get("comments").size());
        assertEquals(1, d.get("flags").size());
        assertEquals(2, d.get("attempts_by_chapter").size());
        assertEquals(1, d.get("attempts_by_chapter").get(0).get("attempts").asInt());
        assertEquals(1, d.get("attempts_by_chapter").get(0).get("correct").asInt());
        assertEquals(0, d.get("attempts_by_chapter").get(1).get("correct").asInt());
        doGet(owner, "/api/pool/courses/" + course.id() + "/staff/students/" + removed.id())
                .andExpect(status().isNotFound());
        doGet(owner, "/api/pool/courses/" + course.id() + "/staff/students/" + ownerB.id())
                .andExpect(status().isNotFound());
        doGet(solver, "/api/pool/courses/" + course.id() + "/staff/students").andExpect(status().isForbidden());

        // The student's own chapter list shows only his numbers of this course.
        JsonNode mine = body(doGet(solver, "/api/pool/courses/" + course.id() + "/chapters"));
        assertEquals(1, mine.get(0).get("my_questions").asInt());
        assertEquals(1, mine.get(0).get("my_attempts").asInt());
        assertEquals(1, mine.get(0).get("my_correct").asInt());
        assertEquals(1, mine.get(1).get("my_attempts").asInt());
        assertEquals(0, mine.get(1).get("my_correct").asInt());
        assertEquals(2, mine.get(0).get("approved").asInt());
    }

    // ---- isolation between courses --------------------------------------------------------------

    @Test
    void courseIsolation() throws Exception {
        Fx a = fx();
        Fx b = fx();
        // Some activity in B to address: a comment and a flag by a student of B, a pending question.
        attempt(b.other(), b.question(), "A");
        long bComment = commentId(b.other(), b.question(), "Comment in B", null);
        doPost(b.other(), "/api/pool/questions/" + b.question() + "/flag", Map.of("reason", "Flag in course B"))
                .andExpect(status().isOk());
        long bFlag = body(doGet(b.owner(), b.staffBase() + "/flags")).get(0).get("id").asLong();
        long bPending = submitQuestion(b.other(), b.chapter(), "C");
        String aStaff = a.staffBase();

        // A student of A can neither read nor act on B's chapter, questions, threads.
        User sa = a.other();
        doGet(sa, "/api/pool/courses/" + b.course().id() + "/me").andExpect(status().isForbidden());
        doGet(sa, "/api/pool/courses/" + b.course().id() + "/chapters").andExpect(status().isForbidden());
        doGet(sa, "/api/pool/chapters/" + b.chapter()).andExpect(status().isForbidden());
        doGet(sa, "/api/pool/chapters/" + b.chapter() + "/questions").andExpect(status().isForbidden());
        doGet(sa, "/api/pool/questions/" + b.question()).andExpect(status().isForbidden());
        doPost(sa, "/api/pool/questions/" + b.question() + "/attempt", Map.of("selected", "A"))
                .andExpect(status().isForbidden());
        doGet(sa, "/api/pool/questions/" + b.question() + "/comments").andExpect(status().isForbidden());
        doPost(sa, "/api/pool/questions/" + b.question() + "/comments", Map.of("body", "Hello B"))
                .andExpect(status().isForbidden());
        doPost(sa, "/api/pool/questions/" + b.question() + "/flag", Map.of("reason", "Flag from A"))
                .andExpect(status().isForbidden());
        doPost(sa, "/api/pool/questions", questionBody(b.chapter(), "A")).andExpect(status().isForbidden());
        doPut(sa, "/api/pool/questions/" + bPending, questionBody(b.chapter(), "A")).andExpect(status().isForbidden());
        doDelete(sa, "/api/pool/questions/" + bPending).andExpect(status().isForbidden());
        doGet(sa, "/api/pool/courses/" + b.course().id() + "/questions/mine").andExpect(status().isForbidden());

        // Staff of A: staff paths of A reject B's entities (404), B's staff paths reject A's staff (403).
        User oa = a.owner();
        doPost(oa, aStaff + "/questions/" + bPending + "/decision", Map.of("action", "APPROVE"))
                .andExpect(status().isNotFound());
        doPost(oa, aStaff + "/questions/" + b.question() + "/decision", Map.of("action", "REJECT", "note", "no"))
                .andExpect(status().isNotFound());
        doPut(oa, aStaff + "/questions/" + bPending, questionBody(b.chapter(), "D")).andExpect(status().isNotFound());
        doPost(oa, aStaff + "/questions/" + bPending + "/ai-check", null).andExpect(status().isNotFound());
        doPost(oa, aStaff + "/questions/" + bPending + "/explain", null).andExpect(status().isNotFound());
        doPut(oa, aStaff + "/chapters/" + b.chapter(), Map.of("title", "Hijacked")).andExpect(status().isNotFound());
        doDelete(oa, aStaff + "/chapters/" + b.chapter()).andExpect(status().isNotFound());
        doPost(oa, aStaff + "/flags/" + bFlag + "/resolve", null).andExpect(status().isNotFound());
        doPost(oa, aStaff + "/comments/" + bComment + "/hide", null).andExpect(status().isNotFound());
        doGet(oa, aStaff + "/students/" + b.other().id()).andExpect(status().isNotFound());
        assertEquals(0, body(doGet(oa, aStaff + "/questions?status=PENDING&chapter_id=" + b.chapter())).size());
        assertTrue(body(doGet(oa, aStaff + "/flags")).isEmpty());
        assertTrue(body(doGet(oa, aStaff + "/students")).valueStream()
                .noneMatch(n -> n.get("id").asLong() == b.other().id()));

        for (String path : List.of("/students", "/questions", "/questions/counts", "/flags", "/exams")) {
            doGet(oa, b.staffBase() + path).andExpect(status().isForbidden());
        }
        doPost(oa, b.staffBase() + "/chapters", Map.of("number", 9, "title", "X")).andExpect(status().isForbidden());
        doPost(oa, b.staffBase() + "/questions/" + bPending + "/decision", Map.of("action", "APPROVE"))
                .andExpect(status().isForbidden());
        doPost(oa, b.staffBase() + "/flags/" + bFlag + "/resolve", null).andExpect(status().isForbidden());
        doPost(oa, b.staffBase() + "/comments/" + bComment + "/hide", null).andExpect(status().isForbidden());

        // B is untouched.
        assertEquals("PENDING", getQuestion(b.other(), bPending).get("status").asString());
        assertEquals("Introduction", body(doGet(b.other(), "/api/pool/chapters/" + b.chapter())).get("title").asString());
        assertFalse(body(doGet(b.owner(), b.staffBase() + "/flags")).get(0).get("resolved").asBoolean());
        assertEquals(1, body(doGet(b.other(), "/api/pool/questions/" + b.question() + "/comments")).size());
        // And A does not see B's question in any of its lists.
        assertTrue(body(doGet(oa, aStaff + "/questions?status=APPROVED")).valueStream()
                .noneMatch(n -> n.get("id").asLong() == b.question()));
        assertTrue(a.course().id() != b.course().id());
    }

    // ---- helpers --------------------------------------------------------------------------------

    long commentId(User who, long questionId, String text, Long parent) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("body", text);
        if (parent != null) req.put("parent_id", parent);
        return body(doPost(who, "/api/pool/questions/" + questionId + "/comments", req).andExpect(status().isOk()))
                .get("id").asLong();
    }

    JsonNode studentRow(Fx f, long personId) throws Exception {
        for (JsonNode n : body(doGet(f.owner(), f.staffBase() + "/students").andExpect(status().isOk()))) {
            if (n.get("id").asLong() == personId) return n;
        }
        return fail("student " + personId + " is not in the participation report");
    }
}
