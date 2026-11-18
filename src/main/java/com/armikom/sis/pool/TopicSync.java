package com.armikom.sis.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives the topics of a chapter from the numbered level-2 headings ({@code ## N. Title}) of its English and
 * Turkish texts (docs/API-POOL.md, "Topics"). Topics are upserted by (chapter, number) so ids stay stable; a topic
 * whose number disappeared is deleted only when nothing links to it. Runs at start-up for chapters without topics.
 */
@Component
public class TopicSync implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TopicSync.class);
    private static final Pattern H2 = Pattern.compile("^##[ \\t]+(?!#)(.*?)[ \\t]*$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d{1,9})\\.(?:[ \\t]+(.*))?$");
    private static final Pattern FENCE = Pattern.compile("^[ ]{0,3}(```|~~~).*$");

    private final JdbcClient db;
    private final TransactionTemplate tx;

    public TopicSync(JdbcClient db, TransactionTemplate tx) {
        this.db = db;
        this.tx = tx;
    }

    /** One numbered section of a document: its number, heading title and Markdown (heading inclusive). */
    public record Section(int number, String title, String content) {}

    // ---------- parsing (pure) ----------

    /** Numbered level-2 sections of a Markdown text by number (first occurrence wins), in number order. */
    public static Map<Integer, Section> sections(String markdown) {
        Map<Integer, Section> out = new TreeMap<>();
        if (markdown == null || markdown.isEmpty()) return out;
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean inFence = false;
        Integer current = null;
        String currentTitle = null;
        StringBuilder buf = null;
        for (String line : lines) {
            if (FENCE.matcher(line).matches()) {
                inFence = !inFence;
            }
            Matcher h = inFence ? null : H2.matcher(line);
            if (h != null && h.matches()) {
                if (current != null) out.putIfAbsent(current, new Section(current, currentTitle, strip(buf)));
                current = null;
                buf = null;
                Matcher n = NUMBERED.matcher(h.group(1));
                if (n.matches()) {
                    current = Integer.parseInt(n.group(1));
                    currentTitle = n.group(2) == null ? "" : n.group(2).trim();
                    buf = new StringBuilder();
                }
            }
            if (buf != null) buf.append(line).append('\n');
        }
        if (current != null) out.putIfAbsent(current, new Section(current, currentTitle, strip(buf)));
        return out;
    }

    /** The section of one topic number in a text, or "" when the text has no such section. */
    public static String content(String markdown, int number) {
        Section s = sections(markdown).get(number);
        return s == null ? "" : s.content();
    }

    private static String strip(StringBuilder b) {
        int end = b.length();
        while (end > 0 && Character.isWhitespace(b.charAt(end - 1))) end--;
        return b.substring(0, end);
    }

    // ---------- syncing ----------

    /** Re-derives the topics of one chapter (no access check; callers check). */
    public void sync(long chapterId) {
        tx.executeWithoutResult(st -> {
            Map<String, Object> ch = db.sql("select doc_en, doc_tr from chapter where id = :id for update")
                    .param("id", chapterId).query().listOfRows().stream().findFirst().orElse(null);
            if (ch == null) return;
            Map<Integer, Section> en = sections((String) ch.get("doc_en"));
            Map<Integer, Section> tr = sections((String) ch.get("doc_tr"));
            TreeSet<Integer> numbers = new TreeSet<>(en.keySet());
            numbers.addAll(tr.keySet());
            for (int n : numbers) {
                db.sql("""
                        insert into topic (chapter_id, number, title_en, title_tr) values (:c, :n, :en, :tr)
                        on conflict (chapter_id, number) do update
                            set title_en = excluded.title_en, title_tr = excluded.title_tr""")
                        .param("c", chapterId).param("n", n)
                        .param("en", en.containsKey(n) ? en.get(n).title() : "")
                        .param("tr", tr.containsKey(n) ? tr.get(n).title() : "").update();
            }
            List<Integer> keep = new ArrayList<>(numbers);
            keep.add(-1); // numbers are never negative; keeps the IN list non-empty
            db.sql("""
                    delete from topic t where t.chapter_id = :c and t.number not in (:nums)
                      and not exists (select 1 from question_topic qt where qt.topic_id = t.id)
                      and not exists (select 1 from exam_question_topic et where et.topic_id = t.id)""")
                    .param("c", chapterId).param("nums", keep).update();
        });
    }

    /** Syncs every chapter that has no topic yet (idempotent); returns how many chapters were synced. */
    public int syncChaptersWithoutTopics() {
        List<Long> ids = db.sql("""
                select c.id from chapter c where not exists (select 1 from topic t where t.chapter_id = c.id)
                order by c.id""").query(Long.class).list();
        for (long id : ids) sync(id);
        return ids.size();
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int n = syncChaptersWithoutTopics();
            if (n > 0) log.info("Derived topics for {} chapter(s) without topics", n);
        } catch (RuntimeException e) {
            log.warn("Topic sync at start-up failed", e);
        }
    }
}
