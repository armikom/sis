package com.armikom.sis.exam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Numbering and choice labels of exam questions.
 * <p>
 * Questions are stored in entry order ({@code position} 1..n). On the paper a group of sub-questions takes one
 * number: with questions Q1, Q2, group(Q3, Q4), Q5 the labels are 1, 2, 3a, 3b, 4. Only the order, the group id and
 * the sub-label are needed, so labels are computed from the question rows alone.
 */
final class ExamLabels {

    static final int MIN_LABELS = 2;
    static final int MAX_LABELS = 10;
    static final String DEFAULT_LABELS = "ABCDEFGHIJ";

    private ExamLabels() {}

    /** Number printed for each group (group id → number), walking the questions in position order. */
    static Map<Long, Integer> groupNumbers(List<ExamQuestion> ordered) {
        Map<Long, Integer> numbers = new LinkedHashMap<>();
        int n = 0;
        for (ExamQuestion q : ordered) {
            if (q.getGroupId() == null) {
                n++;
            } else if (!numbers.containsKey(q.getGroupId())) {
                numbers.put(q.getGroupId(), ++n);
            }
        }
        return numbers;
    }

    /** Printed label of each question (question id → "4" or "3a"). */
    static Map<Long, String> labels(List<ExamQuestion> ordered) {
        Map<Long, String> out = new LinkedHashMap<>();
        Map<Long, Integer> groups = new LinkedHashMap<>();
        Map<Long, Integer> indexInGroup = new LinkedHashMap<>();
        int n = 0;   // one shared counter: an ungrouped question and a whole group each take one number
        for (ExamQuestion q : ordered) {
            if (q.getGroupId() == null) {
                out.put(q.getId(), String.valueOf(++n));
            } else {
                if (!groups.containsKey(q.getGroupId())) groups.put(q.getGroupId(), ++n);
                int i = indexInGroup.merge(q.getGroupId(), 1, Integer::sum) - 1;
                String sub = q.getSubLabel() == null || q.getSubLabel().isBlank() ? autoSubLabel(i) : q.getSubLabel();
                out.put(q.getId(), groups.get(q.getGroupId()) + sub);
            }
        }
        return out;
    }

    /** a, b, …, z, then aa, ab, … */
    static String autoSubLabel(int index) {
        StringBuilder sb = new StringBuilder();
        int i = index;
        do {
            sb.insert(0, (char) ('a' + i % 26));
            i = i / 26 - 1;
        } while (i >= 0);
        return sb.toString();
    }

    /** Default labels for a choice count: 4 → "ABCD". */
    static String defaultLabels(int count) {
        return DEFAULT_LABELS.substring(0, Math.max(0, Math.min(MAX_LABELS, count)));
    }

    /**
     * Normalises a label set typed by staff: separators (whitespace, comma, slash, pipe) are dropped, letters are
     * upper-cased. Returns null unless the result is {@link #MIN_LABELS}..{@link #MAX_LABELS} distinct characters
     * from A-Z / 0-9.
     */
    static String normalizeLabels(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[\\s,/|]+", "").toUpperCase(Locale.ROOT);
        if (s.length() < MIN_LABELS || s.length() > MAX_LABELS) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!ok || s.indexOf(c) != i) return null;
        }
        return s;
    }

    /** The answer as stored: upper-cased single character that is one of the labels; null otherwise. */
    static String choice(String raw, String labels) {
        if (raw == null || labels == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return s.length() == 1 && labels.indexOf(s.charAt(0)) >= 0 ? s : null;
    }

    /** The same position in a new label set of equal size ("B" with AB → TF gives "F"); unknown values stay as they are. */
    static String relabel(String value, String oldLabels, String newLabels) {
        if (value == null || value.length() != 1 || oldLabels == null || newLabels == null
                || oldLabels.length() != newLabels.length()) return value;
        int i = oldLabels.indexOf(value.charAt(0));
        return i < 0 ? value : String.valueOf(newLabels.charAt(i));
    }

    /** "A-D" for ABCD, "D/Y" for DY: how a label set is named in messages. */
    static String describe(String labels) {
        if (labels == null || labels.isEmpty()) return "";
        if (labels.length() >= 3 && DEFAULT_LABELS.startsWith(labels)) {
            return labels.charAt(0) + "-" + labels.charAt(labels.length() - 1);
        }
        return String.join("/", labels.split(""));
    }
}
