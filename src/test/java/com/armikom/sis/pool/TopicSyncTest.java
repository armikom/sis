package com.armikom.sis.pool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Parsing of numbered level-2 sections ({@code ## N. Title}) in chapter texts. */
class TopicSyncTest {

    static final String EN = """
            # Chapter 3

            ## Contents
            - overview

            ## 0. Overview
            Intro text.

            ## 1. The RIPR model (slides 8–11)
            RIPR body.

            ### 1.1 Reach
            Reach body.

            ```
            ## 9. Not a heading (code)
            ```

            ## Key terms
            terms

            ## 2. Coverage
            Coverage body.

            ## 2. Duplicate number
            ignored
            """;

    @Test
    void numberedLevelTwoHeadingsOnly() {
        Map<Integer, TopicSync.Section> s = TopicSync.sections(EN);
        assertEquals(List.of(0, 1, 2), List.copyOf(s.keySet()));
        assertEquals("Overview", s.get(0).title());
        assertEquals("The RIPR model (slides 8–11)", s.get(1).title());
        assertEquals("Coverage", s.get(2).title(), "first heading of a number wins");
    }

    @Test
    void sectionRunsToNextLevelTwoHeading() {
        assertEquals("""
                ## 1. The RIPR model (slides 8–11)
                RIPR body.

                ### 1.1 Reach
                Reach body.

                ```
                ## 9. Not a heading (code)
                ```""", TopicSync.content(EN, 1));
        assertEquals("## 0. Overview\nIntro text.", TopicSync.content(EN, 0));
        assertEquals("## 2. Coverage\nCoverage body.", TopicSync.content(EN, 2));
        assertEquals("", TopicSync.content(EN, 7));
        assertEquals("", TopicSync.content(null, 1));
    }

    @Test
    void crlfAndEmptyTitles() {
        Map<Integer, TopicSync.Section> s = TopicSync.sections("## 4.\r\nbody\r\n## 5. Five  \r\n");
        assertEquals("", s.get(4).title());
        assertEquals("Five", s.get(5).title());
        assertEquals("## 4.\nbody", s.get(4).content());
        assertTrue(TopicSync.sections("##No space\n#  1. Level one\n### 2. Level three").isEmpty());
    }
}
