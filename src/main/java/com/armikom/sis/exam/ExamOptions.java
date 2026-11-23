package com.armikom.sis.exam;

import com.armikom.sis.exam.ExamDtos.OptionDto;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Stored option texts and rationales of exam questions ({@code exam_question_option}). */
@Component
class ExamOptions {

    private final JdbcClient db;

    ExamOptions(JdbcClient db) {
        this.db = db;
    }

    /** Options by exam question id, ordered by letter (questions without options are absent). */
    Map<Long, List<OptionDto>> of(Collection<Long> examQuestionIds) {
        Map<Long, List<OptionDto>> out = new LinkedHashMap<>();
        if (examQuestionIds.isEmpty()) return out;
        db.sql("""
                select exam_question_id, letter, text, rationale from exam_question_option
                where exam_question_id in (:ids) order by exam_question_id, letter""")
                .param("ids", new LinkedHashSet<>(examQuestionIds))
                .query((rs, i) -> Map.entry(rs.getLong(1),
                        new OptionDto(rs.getString(2), rs.getString(3), rs.getString(4)))).list()
                .forEach(e -> out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return out;
    }

    /** Renames stored option letters by position when the label set changes size-for-size (AB → TF). */
    void relabel(long examQuestionId, String oldLabels, String newLabels) {
        List<OptionDto> current = of(List.of(examQuestionId)).getOrDefault(examQuestionId, List.of());
        if (current.isEmpty()) return;
        List<OptionDto> renamed = new ArrayList<>();
        for (OptionDto o : current) {
            renamed.add(new OptionDto(ExamLabels.relabel(o.letter(), oldLabels, newLabels), o.text(), o.rationale()));
        }
        replace(examQuestionId, renamed);
    }

    /** Replaces the options of one exam question (already validated). */
    void replace(long examQuestionId, List<OptionDto> options) {
        db.sql("delete from exam_question_option where exam_question_id = :q").param("q", examQuestionId).update();
        for (OptionDto o : options) {
            db.sql("""
                    insert into exam_question_option (exam_question_id, letter, text, rationale)
                    values (:q, :l, :t, :r)""").param("q", examQuestionId).param("l", o.letter())
                    .param("t", o.text()).param("r", o.rationale()).update();
        }
    }
}
