package com.armikom.sis.exam;

import com.armikom.sis.exam.ExamDtos.OptionDto;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The per-student report inside the notification mail: group stems and every question (label, text, options,
 * given answer, key, right / wrong / blank, points). Plain text and a simple HTML table; rich (HTML) content is
 * reduced to text and images are left out, since mail clients block them anyway — the link leads to the full page.
 */
final class ExamReportMail {

    private ExamReportMail() {}

    static void append(StringBuilder text, StringBuilder html, List<ExamQuestion> questions, Map<Long, String> labels,
                       Map<Long, ExamQuestionGroup> groups, Map<Long, List<OptionDto>> opts, List<ExamAnswer> answers) {
        Map<Long, ExamAnswer> byQ = answers.stream().collect(Collectors.toMap(a -> a.getQuestion().getId(), Function.identity()));
        text.append("\nSINAV DÖKÜMÜ\n");
        html.append("<h3 style=\"margin:18px 0 6px\">Sınav dökümü</h3>")
            .append("<table cellpadding=\"4\" cellspacing=\"0\" style=\"border-collapse:collapse;font-size:13px\">")
            .append("<tr style=\"background:#eee\"><th align=\"left\">Soru</th><th align=\"left\">Cevabınız</th>")
            .append("<th align=\"left\">Doğru</th><th align=\"left\">Durum</th><th align=\"right\">Puan</th></tr>");
        Long prevGroup = null;
        for (ExamQuestion q : questions) {
            Long gid = q.getGroupId();
            if (gid != null && !gid.equals(prevGroup)) {
                ExamQuestionGroup g = groups.get(gid);
                String number = labels.get(q.getId()).replaceAll("[^0-9].*$", "");
                String stem = g == null ? "" : summary(g.getHtml(), g.getText());
                text.append("\n").append(number).append(". ").append(stem).append("\n");
                html.append("<tr><td colspan=\"5\" style=\"background:#f3f6fb;border-top:1px solid #ccc\"><b>")
                    .append(number).append(".</b> ").append(ExamNotifyService.esc(stem)).append("</td></tr>");
            }
            prevGroup = gid;
            ExamAnswer a = byQ.get(q.getId());
            String answer = a == null || q.getType() == QuestionType.FREE ? null : a.getAnswerText();
            BigDecimal points = a == null ? null : a.getPoints();
            String status = status(q, answer, points);
            String stem = summary(q.getHtml(), q.getText());
            String key = q.getType() == QuestionType.CHOICE ? q.getCorrectChoice() : null;
            String answerText = answer == null || answer.isBlank() ? "—" : answer;
            if (q.getType() == QuestionType.CHOICE) {
                answerText = withOption(answerText, opts.get(q.getId()));
                if (key != null) key = withOption(key, opts.get(q.getId()));
            }
            String pts = (points == null ? "—" : ExamMath.normalize(points).toPlainString()) + " / "
                    + ExamMath.normalize(q.getMaxPoints()).toPlainString();
            text.append(labels.get(q.getId())).append(". ").append(stem.isEmpty() ? "" : stem + " ")
                .append("| Cevabınız: ").append(answerText)
                .append(key != null ? " | Doğru: " + key : "").append(" | ").append(status).append(" | ").append(pts).append("\n");
            html.append("<tr style=\"border-top:1px solid #ddd;vertical-align:top\"><td><b>").append(ExamNotifyService.esc(labels.get(q.getId())))
                .append(".</b> ").append(ExamNotifyService.esc(stem)).append("</td><td>").append(ExamNotifyService.esc(answerText))
                .append("</td><td>").append(key == null ? "" : ExamNotifyService.esc(key)).append("</td><td style=\"color:")
                .append(color(status)).append("\">").append(status).append("</td><td align=\"right\">").append(pts).append("</td></tr>");
        }
        html.append("</table>");
    }

    /** "B — V/R" when the option has a text; the label alone otherwise. */
    private static String withOption(String letter, List<OptionDto> options) {
        if (options == null) return letter;
        for (OptionDto o : options) {
            if (o.letter().equals(letter) && o.text() != null && !o.text().isBlank()) {
                return letter + " — " + clip(o.text().strip(), 120);
            }
        }
        return letter;
    }

    static String status(ExamQuestion q, String answer, BigDecimal points) {
        if (q.getType() == QuestionType.CHOICE) {
            if (answer == null || answer.isBlank()) return "Boş";
            if (q.getCorrectChoice() == null) return "Puanlanmadı";
            return q.getCorrectChoice().equals(answer) ? "Doğru" : "Yanlış";
        }
        if (points == null) return "Puanlanmadı";
        if (points.compareTo(q.getMaxPoints()) >= 0) return "Doğru";
        return points.signum() > 0 ? "Kısmi" : "Yanlış";
    }

    private static String color(String status) {
        return switch (status) {
            case "Doğru" -> "#1b7f3b";
            case "Yanlış", "Boş" -> "#b3261e";
            case "Kısmi" -> "#9a6700";
            default -> "#555";
        };
    }

    /** Rich content as one line of text (tags dropped), else the plain text's first line; clipped. */
    static String summary(String html, String text) {
        String s;
        if (html != null && !html.isBlank()) {
            s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                    .replaceAll("(?i)<br\\s*/?>|</p>|</div>|</li>|</tr>", " ")
                    .replaceAll("<[^>]+>", " ")
                    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        } else {
            s = text == null ? "" : text;
        }
        s = s.replaceAll("```[\\s\\S]*?```", " ").replaceAll("\\s+", " ").strip();
        return clip(s, ExamNotifyService.MAX_MAIL_TEXT);
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
