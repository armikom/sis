package com.armikom.sis.exam;

import com.armikom.sis.common.ApiException;
import com.armikom.sis.common.ErrorCode;
import com.armikom.sis.config.AppProperties;
import com.armikom.sis.core.auth.CourseAccess;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Server copy of the answer-entry drafts (unsaved sheets), so they survive a change of browser or computer and are
 * visible to the other staff of the course. The sheet is stored as the client sends it; only its shape and size are
 * checked. Saving or deleting the student's result removes the draft (ExamService).
 */
@Service
public class ExamDraftService {

    /** Serialized sheet limit: 200 questions with long open answers stay well below it. */
    static final int MAX_SHEET_CHARS = 500_000;

    public record DraftDto(String studentNo, JsonNode sheet, String updatedAt, String updatedBy) {}

    public record DraftSavedDto(String studentNo, String updatedAt) {}

    private final JdbcClient db;
    private final ExamQueries queries;
    private final CourseAccess access;
    private final JsonMapper json;
    private final Clock clock;
    private final Pattern studentNoPattern;

    public ExamDraftService(JdbcClient db, ExamQueries queries, CourseAccess access, JsonMapper json, Clock clock,
                            AppProperties props) {
        this.db = db;
        this.queries = queries;
        this.access = access;
        this.json = json;
        this.clock = clock;
        this.studentNoPattern = Pattern.compile(props.checkin().studentNoPattern());
    }

    @Transactional(readOnly = true)
    public List<DraftDto> list(long examId) {
        queries.requireExam(examId);
        return db.sql("""
                        select d.student_no, d.sheet::text as sheet, d.updated_at, p.full_name
                        from exam_entry_draft d join person p on p.id = d.person_id
                        where d.exam_id = :exam order by d.student_no""")
                .param("exam", examId)
                .query((rs, i) -> new DraftDto(rs.getString("student_no"), json.readTree(rs.getString("sheet")),
                        rs.getTimestamp("updated_at").toInstant().toString(), rs.getString("full_name")))
                .list();
    }

    @Transactional
    public DraftSavedDto save(long examId, String rawStudentNo, JsonNode sheet) {
        queries.requireExam(examId);
        String studentNo = validStudentNo(rawStudentNo);
        if (sheet == null || !sheet.isObject()) throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("sheet", "Geçersiz taslak"));
        String text = json.writeValueAsString(sheet);
        if (text.length() > MAX_SHEET_CHARS) throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("sheet", "Taslak çok büyük"));
        Instant now = clock.instant();
        db.sql("""
                        insert into exam_entry_draft (exam_id, student_no, person_id, sheet, updated_at)
                        values (:exam, :no, :person, cast(:sheet as jsonb), :now)
                        on conflict (exam_id, student_no)
                        do update set person_id = excluded.person_id, sheet = excluded.sheet, updated_at = excluded.updated_at""")
                .param("exam", examId)
                .param("no", studentNo)
                .param("person", access.currentPerson().personId())
                .param("sheet", text)
                .param("now", Timestamp.from(now))
                .update();
        return new DraftSavedDto(studentNo, now.toString());
    }

    @Transactional
    public void delete(long examId, String rawStudentNo) {
        queries.requireExam(examId);
        remove(examId, rawStudentNo == null ? "" : rawStudentNo.trim());
    }

    /** Without an access check: for ExamService once the result was saved or deleted. */
    void remove(long examId, String studentNo) {
        db.sql("delete from exam_entry_draft where exam_id = :exam and student_no = :no")
                .param("exam", examId)
                .param("no", studentNo)
                .update();
    }

    private String validStudentNo(String raw) {
        String no = raw == null ? "" : raw.trim();
        if (!studentNoPattern.matcher(no).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("studentNo", "Geçersiz format"));
        }
        return no;
    }
}
