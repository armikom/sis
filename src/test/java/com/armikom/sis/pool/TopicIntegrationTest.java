package com.armikom.sis.pool;

import com.armikom.sis.support.ApiTestSupport;
import com.armikom.sis.support.PostgresTestcontainer;
import com.armikom.sis.support.TestClockConfig;
import com.armikom.sis.support.TestPeople;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Topics (docs/API-POOL.md, "Topics"): derivation from chapter texts, question links, preview access. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, PostgresTestcontainer.class, TestPeople.Config.class})
class TopicIntegrationTest extends ApiTestSupport {

    static final String DOC_EN = """
            # Testing

            ## Contents
            - all

            ## 0. Overview
            Intro text.

            ## 1. The RIPR model (slides 8–11)
            RIPR body.

            ### 1.1 Reach
            Reach body.

            ## Key terms
            terms

            ## 2. Coverage
            Coverage body.
            """;
    static final String DOC_TR = """
            ## 0. Genel bakış
            Giriş.

            ## 1. RIPR modeli
            Gövde.

            ## 3. Sadece Türkçe
            Yalnız TR.

            ## Ideas for your questions
            x
            """;

    @Autowired TopicSync topicSync;

    record Fx(User owner, CourseRef course, long chapter, User author, User other) {
        String staffBase() { return "/api/pool/courses/" + course.id() + "/staff"; }
    }

    Fx fx() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, true, 5);
        long chapter = chapterWithDocs(owner, course, 3, DOC_EN, DOC_TR);
        return new Fx(owner, course, chapter, newStudent(course), newStudent(course));
    }

    long chapterWithDocs(User owner, CourseRef course, int number, String en, String tr) throws Exception {
        return body(doPost(owner, "/api/pool/courses/" + course.id() + "/staff/chapters",
                Map.of("number", number, "title", "Chapter " + number, "doc_en", en, "doc_tr", tr))
                .andExpect(status().isOk())).get("id").asLong();
    }

    /** Topic number → topic JSON of a chapter (GET chapter). */
    Map<Integer, JsonNode> topics(User viewer, long chapterId) throws Exception {
        Map<Integer, JsonNode> out = new LinkedHashMap<>();
        body(doGet(viewer, "/api/pool/chapters/" + chapterId).andExpect(status().isOk())).get("topics")
                .forEach(t -> out.put(t.get("number").asInt(), t));
        return out;
    }

    // ---- derivation -----------------------------------------------------------------------------

    @Test
    void chapterSaveDerivesTopicsFromBothLanguages() throws Exception {
        Fx f = fx();
        Map<Integer, JsonNode> t = topics(f.author(), f.chapter());
        assertEquals(List.of(0, 1, 2, 3), List.copyOf(t.keySet()));
        assertEquals("Overview", t.get(0).get("title_en").asString());
        assertEquals("Genel bakış", t.get(0).get("title_tr").asString());
        assertEquals("The RIPR model (slides 8–11)", t.get(1).get("title_en").asString());
        assertEquals("RIPR modeli", t.get(1).get("title_tr").asString());
        assertEquals("", t.get(2).get("title_tr").asString());
        assertEquals("", t.get(3).get("title_en").asString());
        assertEquals("Sadece Türkçe", t.get(3).get("title_tr").asString());
        Map<String, Object> keys = json.convertValue(t.get(0), Map.class);
        assertEquals(java.util.Set.of("id", "number", "title_en", "title_tr"), keys.keySet());

        // Re-sync keeps ids; titles follow the text; a removed, unlinked topic goes away; a linked one stays.
        long id1 = t.get(1).get("id").asLong();
        long id2 = t.get(2).get("id").asLong();
        long q = submitQuestion(f.author(), f.chapter(), "A");
        doPut(f.owner(), f.staffBase() + "/questions/" + q + "/topics", Map.of("topic_ids", List.of(id2)))
                .andExpect(status().isOk());
        doPut(f.owner(), f.staffBase() + "/chapters/" + f.chapter(),
                Map.of("doc_en", "## 1. RIPR, renamed\nx\n", "doc_tr", "")).andExpect(status().isOk());
        Map<Integer, JsonNode> after = topics(f.author(), f.chapter());
        assertEquals(List.of(1, 2), List.copyOf(after.keySet()), after.toString());
        assertEquals(id1, after.get(1).get("id").asLong());
        assertEquals("RIPR, renamed", after.get(1).get("title_en").asString());
        assertEquals("", after.get(1).get("title_tr").asString());
        assertEquals(id2, after.get(2).get("id").asLong(), "linked topic survives");

        // The sync endpoint answers the chapter's topics; once unlinked the stale topic goes away.
        doPut(f.owner(), f.staffBase() + "/questions/" + q + "/topics", Map.of("topic_ids", List.of()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.topics.length()").value(0));
        JsonNode synced = body(doPost(f.owner(), f.staffBase() + "/chapters/" + f.chapter() + "/topics/sync", null)
                .andExpect(status().isOk()));
        assertEquals(1, synced.size());
        assertEquals(id1, synced.get(0).get("id").asLong());
        doPost(f.author(), f.staffBase() + "/chapters/" + f.chapter() + "/topics/sync", null)
                .andExpect(status().isForbidden());
    }

    @Test
    void startupSyncFillsChaptersWithoutTopicsOnly() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, true, 2);
        long raw = jdbc.sql("insert into chapter (course_id, number, title, doc_en) values (:c, 9, 'Raw', :d) returning id")
                .param("c", course.id()).param("d", "## 4. Four\n## 5. Five\n").query(Long.class).single();
        assertEquals(0, count("select count(*) from topic where chapter_id = ?", raw));
        assertTrue(topicSync.syncChaptersWithoutTopics() >= 1);
        assertEquals(2, count("select count(*) from topic where chapter_id = ?", raw));
        // A chapter that has topics is not touched again.
        jdbc.sql("update chapter set doc_en = '## 7. Seven' where id = :id").param("id", raw).update();
        topicSync.syncChaptersWithoutTopics();
        assertEquals(0, count("select count(*) from topic where chapter_id = ? and number = 7", raw));
    }

    // ---- question links -------------------------------------------------------------------------

    @Test
    void questionsLinkToSeveralTopicsOfTheSameCourse() throws Exception {
        Fx f = fx();
        long other = chapterWithDocs(f.owner(), f.course(), 4, "## 1. Other chapter topic\n", "");
        long a = topics(f.owner(), f.chapter()).get(1).get("id").asLong();
        long b = topics(f.owner(), f.chapter()).get(2).get("id").asLong();
        long c = topics(f.owner(), other).get(1).get("id").asLong();

        // Topics of another course are refused, on create and on every update path.
        User stranger = newStaff();
        CourseRef foreign = newCourse(stranger, true, 2);
        long foreignChapter = chapterWithDocs(stranger, foreign, 1, "## 1. Foreign\n", "");
        long foreignTopic = topics(stranger, foreignChapter).get(1).get("id").asLong();
        Map<String, Object> bad = questionBody(f.chapter(), "A");
        bad.put("topic_ids", List.of(a, foreignTopic));
        doPost(f.author(), "/api/pool/questions", bad).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A topic does not belong to this course."));
        bad.put("topic_ids", List.of(999_999_999L));
        doPost(f.author(), "/api/pool/questions", bad).andExpect(status().isBadRequest());

        Map<String, Object> body = questionBody(f.chapter(), "A");
        body.put("topic_ids", List.of(c, a, a));
        long q = body(doPost(f.author(), "/api/pool/questions", body).andExpect(status().isOk())).get("id").asLong();
        JsonNode got = getQuestion(f.author(), q);
        assertEquals(2, got.get("topics").size());
        assertEquals(a, got.get("topics").get(0).get("id").asLong(), "ordered by chapter number, number");
        assertEquals(3, got.get("topics").get(0).get("chapter_number").asInt());
        assertEquals(c, got.get("topics").get(1).get("id").asLong());
        assertEquals(java.util.Set.of("id", "chapter_id", "chapter_number", "number", "title_en", "title_tr"),
                json.convertValue(got.get("topics").get(0), Map.class).keySet());

        // Author update without topic_ids keeps them; with topic_ids replaces them.
        doPut(f.author(), "/api/pool/questions/" + q, questionBody(f.chapter(), "B")).andExpect(status().isOk());
        assertEquals(2, getQuestion(f.author(), q).get("topics").size());
        Map<String, Object> upd = questionBody(f.chapter(), "B");
        upd.put("topic_ids", List.of(b));
        doPut(f.author(), "/api/pool/questions/" + q, upd).andExpect(status().isOk());
        assertEquals(b, getQuestion(f.author(), q).get("topics").get(0).get("id").asLong());
        upd.put("topic_ids", List.of(foreignTopic));
        doPut(f.author(), "/api/pool/questions/" + q, upd).andExpect(status().isBadRequest());
        doPut(f.owner(), f.staffBase() + "/questions/" + q, upd).andExpect(status().isBadRequest());
        doPut(f.owner(), f.staffBase() + "/questions/" + q + "/topics", Map.of("topic_ids", List.of(foreignTopic)))
                .andExpect(status().isBadRequest());

        // Staff edit sets two topics; the dedicated endpoint answers the new set.
        upd.put("topic_ids", List.of(a, b));
        doPut(f.owner(), f.staffBase() + "/questions/" + q, upd).andExpect(status().isOk());
        assertEquals(2, getQuestion(f.owner(), q).get("topics").size());

        // Approved: classmates see the topics before their first answer; mine / lists carry them and filter by topic.
        decide(f.owner(), f.course(), q, "APPROVE", null);
        long untagged = approvedQuestion(f.author(), f.owner(), f.course(), f.chapter(), "C");
        JsonNode seen = getQuestion(f.other(), q);
        assertFalse(seen.get("revealed").asBoolean());
        assertEquals(2, seen.get("topics").size());
        assertEquals(0, getQuestion(f.other(), untagged).get("topics").size());

        JsonNode list = body(doGet(f.other(), "/api/pool/chapters/" + f.chapter() + "/questions?topic_id=" + a)
                .andExpect(status().isOk()));
        assertEquals(1, list.size());
        assertEquals(q, list.get(0).get("id").asLong());
        assertEquals(2, list.get(0).get("topics").size());
        assertEquals(2, body(doGet(f.other(), "/api/pool/chapters/" + f.chapter() + "/questions")).size());
        JsonNode staffList = body(doGet(f.owner(), f.staffBase() + "/questions?status=APPROVED&topic_id=" + b)
                .andExpect(status().isOk()));
        assertEquals(1, staffList.size());
        assertEquals(2, staffList.get(0).get("topics").size());
        JsonNode mine = body(doGet(f.author(), "/api/pool/courses/" + f.course().id() + "/questions/mine"));
        for (JsonNode m : mine) assertTrue(m.has("topics"), m.toString());

        // Course topics: approved questions per topic, ordered by chapter number then number.
        JsonNode all = body(doGet(f.other(), "/api/pool/courses/" + f.course().id() + "/topics")
                .andExpect(status().isOk()));
        assertEquals(5, all.size());
        assertEquals(3, all.get(0).get("chapter_number").asInt());
        assertEquals(0, all.get(0).get("number").asInt());
        assertEquals("Chapter 3", all.get(0).get("chapter_title").asString());
        Map<Long, Integer> counts = new HashMap<>();
        all.forEach(t -> counts.put(t.get("id").asLong(), t.get("question_count").asInt()));
        assertEquals(1, counts.get(a));
        assertEquals(1, counts.get(b));
        assertEquals(0, counts.get(c));
        assertEquals(4, all.get(4).get("chapter_number").asInt());
    }

    // ---- preview ----------------------------------------------------------------------------------

    @Test
    void topicPreviewWorksForMembersEvenWithThePoolOff() throws Exception {
        Fx f = fx();
        long id = topics(f.owner(), f.chapter()).get(1).get("id").asLong();
        long trOnly = topics(f.owner(), f.chapter()).get(3).get("id").asLong();
        doPut(f.owner(), "/api/courses/" + f.course().id(), Map.of("poolEnabled", false)).andExpect(status().isOk());
        doGet(f.other(), "/api/pool/chapters/" + f.chapter()).andExpect(status().isNotFound());

        JsonNode t = body(doGet(f.other(), "/api/pool/topics/" + id).andExpect(status().isOk()));
        assertEquals(f.course().id(), t.get("course_id").asLong());
        assertEquals(f.chapter(), t.get("chapter_id").asLong());
        assertEquals(3, t.get("chapter_number").asInt());
        assertEquals("Chapter 3", t.get("chapter_title").asString());
        assertEquals(1, t.get("number").asInt());
        assertEquals("## 1. The RIPR model (slides 8–11)\nRIPR body.\n\n### 1.1 Reach\nReach body.",
                t.get("content_en").asString());
        assertEquals("## 1. RIPR modeli\nGövde.", t.get("content_tr").asString());
        JsonNode tr = body(doGet(f.other(), "/api/pool/topics/" + trOnly).andExpect(status().isOk()));
        assertEquals("", tr.get("content_en").asString());
        assertEquals("## 3. Sadece Türkçe\nYalnız TR.", tr.get("content_tr").asString());
        doGet(f.other(), "/api/pool/courses/" + f.course().id() + "/topics").andExpect(status().isOk());

        User outsider = newStudent(newCourse(newStaff(), true, 2));
        doGet(outsider, "/api/pool/topics/" + id).andExpect(status().isForbidden());
        doGet(outsider, "/api/pool/courses/" + f.course().id() + "/topics").andExpect(status().isForbidden());
        doGet(f.other(), "/api/pool/topics/999999999").andExpect(status().isNotFound());
        doGet(null, "/api/pool/topics/" + id).andExpect(status().isUnauthorized());
    }
}
