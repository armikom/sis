package com.armikom.sis.agent;

import com.armikom.sis.agent.McpTool.Audience;
import com.armikom.sis.attendance.AttendanceController;
import com.armikom.sis.attendance.AttendanceDto;
import com.armikom.sis.attendance.AttendanceService;
import com.armikom.sis.attendance.CourseStudentController;
import com.armikom.sis.attendance.TeacherDecision;
import com.armikom.sis.checkin.AttemptState;
import com.armikom.sis.export.CsvExportService;
import com.armikom.sis.security.DeviceHint;
import com.armikom.sis.session.BleMode;
import com.armikom.sis.session.ClassSession;
import com.armikom.sis.session.GeoMode;
import com.armikom.sis.session.SessionController.SessionDetailDto;
import com.armikom.sis.session.SessionController.SessionSummaryDto;
import com.armikom.sis.session.SessionService;
import com.armikom.sis.session.SessionStatus;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.armikom.sis.agent.McpTool.bool;
import static com.armikom.sis.agent.McpTool.integer;
import static com.armikom.sis.agent.McpTool.oneOf;
import static com.armikom.sis.agent.McpTool.schema;
import static com.armikom.sis.agent.McpTool.str;
import static com.armikom.sis.agent.McpTool.strList;

/**
 * MCP tools for course staff about attendance (yoklama): sessions, attendance records, manual attendance, reports
 * and the CSV export. They call the same services as the staff screens. The session key, the display configuration,
 * raw tokens and frame images never leave the server through these tools.
 */
@Component
@Order(4)
public class McpAttendanceTools implements McpToolProvider {

    static final String COURSE_OF_SESSION = "select course_id from class_session where id = :id";
    static final String COURSE_OF_ATTENDANCE = """
            select s.course_id from attendance a join class_session s on s.id = a.session_id where a.id = :id""";
    private static final String SESSION_OF_ATTENDANCE = "select session_id from attendance where id = :id";
    private static final String SESSION_NOT_FOUND = "Session not found.";
    private static final String ATTENDANCE_NOT_FOUND = "Attendance record not found.";

    private final JdbcClient db;
    private final SessionService sessions;
    private final AttendanceService attendance;
    private final CourseStudentController students;
    private final CsvExportService export;
    private final DeviceHint deviceHint;

    public McpAttendanceTools(JdbcClient db, SessionService sessions, AttendanceService attendance,
                              CourseStudentController students, CsvExportService export, DeviceHint deviceHint) {
        this.db = db;
        this.sessions = sessions;
        this.attendance = attendance;
        this.students = students;
        this.export = export;
        this.deviceHint = deviceHint;
    }

    @Override
    public List<McpTool> tools() {
        Audience t = Audience.INSTRUCTOR;
        Map<String, Object> sessionId = integer("Session id, see list_sessions.");
        Map<String, Object> attendanceId = integer("Attendance record id, see session_attendances.");
        return List.of(
                // ---------- sessions ----------
                McpTool.read(t, "list_sessions", "Attendance sessions of the course",
                        "Lists the attendance sessions of the course, newest first, with status (DRAFT, OPEN, "
                                + "CLOSED), closing time, number of attendance records and flagged records waiting "
                                + "for a decision.",
                        schema("status", oneOf("Only sessions in this state.", names(SessionStatus.values()))),
                        c -> listSessions(c)),
                McpTool.read(t, "get_session", "One attendance session",
                        "All settings and times of one attendance session: classroom, duration, required scans, "
                                + "location and BLE mode, thresholds, join link, record counts. The session key and "
                                + "the QR display configuration are not available by MCP.",
                        schema("session_id!", sessionId),
                        c -> detail(sessions.get(sessionOf(c)))),
                McpTool.write(t, "create_session", "Create an attendance session",
                        "Creates a new attendance session in DRAFT state; it is not open yet (use open_session). "
                                + "Show the title and settings and ask the staff member before you create it. "
                                + "Unset settings take the site defaults.",
                        schema("title!", str("Title of the session, for example 'Week 5 lecture' (1-200 characters)."),
                                "duration_minutes", integer("How long the session stays open after opening (1-240)."),
                                "classroom", str("Classroom id or its exact name (optional)."),
                                "required_scans", integer("QR scans a student needs for a check-in (2-6)."),
                                "geo_mode", oneOf("Location check: OFF, or FLAG (the classroom needs coordinates).",
                                        names(GeoMode.values())),
                                "ble_mode", oneOf("BLE beacon check: OFF, or OPTIONAL (the classroom needs a BLE "
                                        + "key).", names(BleMode.values())),
                                "frame_capture", bool("Store a camera frame of each check-in.")),
                        c -> createSession(c)),
                McpTool.write(t, "open_session", "Open an attendance session",
                        "Opens a DRAFT session: students can check in from now until the duration ends. The QR "
                                + "display itself runs on the web site.",
                        schema("session_id!", sessionId),
                        c -> detail(sessions.open(sessionOf(c)))),
                McpTool.write(t, "extend_session", "Extend an open session",
                        "Adds minutes to the closing time of an OPEN session.",
                        schema("session_id!", sessionId, "minutes!", integer("Minutes to add (1-240).")),
                        c -> detail(sessions.extend(sessionOf(c), Math.toIntExact(c.args().num("minutes"))))),
                McpTool.write(t, "close_session", "Close an open session",
                        "Closes an OPEN session now; no more check-ins.",
                        schema("session_id!", sessionId),
                        c -> detail(sessions.close(sessionOf(c)))),
                McpTool.write(t, "reopen_session", "Reopen a closed session",
                        "Reopens a CLOSED session for the given minutes (default 15) with a new code.",
                        schema("session_id!", sessionId, "minutes", integer("Minutes to stay open (1-240, default 15).")),
                        c -> {
                            Integer minutes = c.args().optInt("minutes");
                            return detail(sessions.reopen(sessionOf(c), minutes != null ? minutes : 15));
                        }),

                // ---------- attendance records ----------
                McpTool.read(t, "session_attendances", "Attendance records of a session",
                        "The attendance records of one session, newest first: student, source (CHECKIN or MANUAL), "
                                + "status, flags, latency and thresholds, location state, BLE, teacher decision and "
                                + "note. Filter with only: PENDING (flagged, waiting for a decision), PRESENT, "
                                + "REJECTED or EXCUSED (away with leave).",
                        schema("session_id!", sessionId,
                                "only", oneOf("Only records in this state.", "PENDING", "PRESENT", "REJECTED",
                                        "EXCUSED")),
                        c -> listAttendances(c)),
                McpTool.write(t, "decide_attendance", "Approve or reject an attendance record",
                        "Sets the teacher decision of an attendance record: APPROVED counts as present, REJECTED as "
                                + "absent, EXCUSED (izinli) marks the student away with leave (neither present nor "
                                + "absent), NONE removes an earlier decision. Ask the staff member before you reject.",
                        schema("attendance_id!", attendanceId,
                                "decision!", oneOf("The decision (APPROVE and REJECT are accepted too).",
                                        names(TeacherDecision.values())),
                                "note", str("Note for the record (optional, up to 500 characters).")),
                        c -> decide(c)),
                McpTool.write(t, "add_attendance", "Add students by hand",
                        "Marks students as present in a session by student number (source MANUAL, approved). Only "
                                + "students on the course roster are accepted; the result lists the numbers that are "
                                + "not on the roster, the ones already recorded (skipped) and format errors. Ask the "
                                + "staff member before you run it. With excused=true the students are recorded as "
                                + "away with leave (izinli: not present, not absent), e.g. for a sports event or a "
                                + "medical report; put the reason in note.",
                        schema("session_id!", sessionId,
                                "student_numbers!", strList("Student numbers to mark present (or excused)."),
                                "note", str("Note stored on each record (optional)."),
                                "excused", bool("Record as away with leave (izinli) instead of present.")),
                        c -> addAttendance(c)),
                new McpTool("remove_attendance", "Delete an attendance record",
                        "Deletes one attendance record (and its stored frame) for good. Ask the staff member and "
                                + "name the student before you do it.",
                        schema("attendance_id!", attendanceId), t, false, true, c -> removeAttendance(c)),
                McpTool.read(t, "rejected_attempts", "Rejected check-in attempts",
                        "Check-in attempts of a session that did not become an attendance record (default state "
                                + "REJECTED): student number, result code, device hint, the student who already holds "
                                + "the device if there is a conflict, and the time.",
                        schema("session_id!", sessionId,
                                "state", oneOf("Attempt state (default REJECTED).", names(AttemptState.values()))),
                        c -> attempts(c)),

                // ---------- reports ----------
                McpTool.read(t, "attendance_report", "Attendance report of the course",
                        "One row per roster student with the number of opened sessions and how many they were "
                                + "present, pending (flagged, no decision yet), rejected, excused (away with leave) or "
                                + "absent in, plus present_pct (excused sessions are left out of the base); and the "
                                + "list of opened sessions.",
                        schema(), c -> report(c.courseId())),
                McpTool.read(t, "student_attendance", "One student's attendance",
                        "Profile and one row per opened session for one student of the course (PRESENT, PENDING, "
                                + "REJECTED or ABSENT with source, flags and note). Find the student by student "
                                + "number, e-mail, id or a part of the name; several matches come back as a list. "
                                + "EXCUSED rows (away with leave) are listed too.",
                        schema("student!", str("Student number, e-mail, id or part of the name.")),
                        c -> studentAttendance(c.courseId(), c.args().str("student"))),
                McpTool.read(t, "session_export", "CSV export of a session",
                        "The CSV file of one session as the web site exports it (semicolon separated, Turkish "
                                + "headers): every record and the roster students who did not attend.",
                        schema("session_id!", sessionId),
                        c -> {
                            CsvExportService.Export e = export.export(sessionOf(c));
                            return Map.of("filename", e.filename(), "csv", e.content());
                        }));
    }

    // ---------- sessions ----------

    /** The session id of the call, after the course and audience check. */
    private long sessionOf(McpCall c) {
        long id = c.args().num("session_id");
        c.courseOf(COURSE_OF_SESSION, id, SESSION_NOT_FOUND);
        return id;
    }

    private List<SessionSummaryDto> listSessions(McpCall c) {
        long courseId = c.courseId();
        String status = c.args().optStr("status");
        SessionStatus statusEnum = status == null || status.isBlank() ? null
                : parse(SessionStatus.class, status, "status");
        return sessions.listAll(courseId, statusEnum).stream().map(this::summary).toList();
    }

    private SessionDetailDto createSession(McpCall c) {
        long courseId = c.courseId();
        McpTool.Args a = c.args();
        Long classroomId = a.has("classroom") ? classroomId(courseId, c.person().personId(), a.str("classroom"))
                : null;
        ClassSession s = sessions.create(new SessionService.CreateCommand(courseId, classroomId, a.str("title"),
                a.optInt("duration_minutes"), null, a.optInt("required_scans"),
                a.optStr("geo_mode"), a.optStr("ble_mode"), a.optBool("frame_capture")));
        return detail(s);
    }

    /** A classroom by id or exact name, among the ones the person or the staff of the course own. */
    private Long classroomId(long courseId, long personId, String ref) {
        String q = ref.trim();
        List<Long> ids = db.sql("""
                select c.id from classroom c
                where (cast(c.id as text) = :q or lower(c.name) = lower(:q))
                  and (c.owner_id = :me or c.owner_id in (
                        select e.person_id from enrollment e
                        where e.course_id = :course and e.deleted_at is null and e.role <> 'STUDENT'))
                order by c.id""").param("q", q).param("me", personId).param("course", courseId)
                .query(Long.class).list();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("No classroom '" + q + "' is available for this course. Give the "
                    + "classroom id or its exact name.");
        }
        if (ids.size() > 1) {
            throw new IllegalArgumentException("More than one classroom is named '" + q + "'. Give the id: " + ids);
        }
        return ids.get(0);
    }

    private SessionSummaryDto summary(ClassSession s) {
        return new SessionSummaryDto(s.getId(), s.getCourse().getId(), s.getCourse().getCode(),
                s.getCourse().getName(), s.getTitle(), s.getCode(), s.getStatus().name(), s.getDurationMinutes(),
                s.getClosesAt() != null ? s.getClosesAt().toString() : null, s.getCreatedAt().toString(),
                sessions.attendanceCount(s.getId()), sessions.flaggedPendingCount(s.getId()));
    }

    /** Same fields as the web API's session detail; the join link uses the configured public base URL. */
    private SessionDetailDto detail(ClassSession s) {
        String baseUrl = sessions.resolveBaseUrl(null);
        return new SessionDetailDto(s.getId(),
                s.getCourse().getCode(), s.getCourse().getName(), s.getCourse().getId(),
                s.getClassroom() != null ? s.getClassroom().getId() : null,
                s.getClassroom() != null ? s.getClassroom().getName() : null,
                s.getTitle(), s.getCode(), s.getStatus().name(),
                s.getDurationMinutes(), s.getRefreshMs(), s.getRequiredScans(),
                s.getMaxSpanSlots(), s.getMaxTokenAgeMs(), s.getLatencyMarginMs(),
                s.getDefaultLatencyThresholdMs(), s.getScreenAreaRatioThreshold(),
                s.getGeoMode().name(), s.getBleMode().name(),
                s.isFrameCapture(),
                s.getEpochStartMs(),
                s.getOpenedAt() != null ? s.getOpenedAt().toString() : null,
                s.getClosesAt() != null ? s.getClosesAt().toString() : null,
                s.getClosedAt() != null ? s.getClosedAt().toString() : null,
                s.getBaselineLatencyMs(), s.getBaselineAreaRatioMax(),
                sessions.effectiveLatencyThreshold(s),
                sessions.effectiveAreaThreshold(s),
                sessions.thresholdSource(s),
                s.getStatus() != SessionStatus.DRAFT ? sessions.joinUrl(s, baseUrl) : null,
                sessions.attendanceCount(s.getId()),
                sessions.flaggedPendingCount(s.getId()),
                s.getCreatedAt().toString());
    }

    // ---------- attendance records ----------

    private List<AttendanceDto> listAttendances(McpCall c) {
        long sessionId = sessionOf(c);
        String only = c.args().optStr("only");
        String state = only == null || only.isBlank() ? null : only.trim().toUpperCase(Locale.ROOT);
        if (state != null && !List.of("PENDING", "PRESENT", "REJECTED", "EXCUSED").contains(state)) {
            throw new IllegalArgumentException("The argument 'only' must be PENDING, PRESENT, REJECTED or EXCUSED.");
        }
        return attendance.list(sessionId).stream()
                .filter(a -> state == null || switch (state) {
                    case "PENDING" -> a.isPending();
                    case "PRESENT" -> a.isPresent();
                    case "EXCUSED" -> a.isExcused();
                    default -> a.getTeacherDecision() == TeacherDecision.REJECTED;
                })
                .map(a -> AttendanceDto.from(a, deviceHint.of(a.getDeviceId())))
                .toList();
    }

    /** The session of an attendance record, after the course and audience check. */
    private long sessionOfAttendance(McpCall c, long attendanceId) {
        c.courseOf(COURSE_OF_ATTENDANCE, attendanceId, ATTENDANCE_NOT_FOUND);
        return db.sql(SESSION_OF_ATTENDANCE).param("id", attendanceId).query(Long.class).single();
    }

    private AttendanceDto decide(McpCall c) {
        long attId = c.args().num("attendance_id");
        long sessionId = sessionOfAttendance(c, attId);
        String word = c.args().str("decision").trim().toUpperCase(Locale.ROOT);
        TeacherDecision decision = switch (word) {
            case "APPROVE" -> TeacherDecision.APPROVED;
            case "REJECT" -> TeacherDecision.REJECTED;
            default -> parse(TeacherDecision.class, word, "decision");
        };
        var a = attendance.decide(sessionId, attId, decision, c.args().optStr("note"));
        return AttendanceDto.from(a, deviceHint.of(a.getDeviceId()));
    }

    private AttendanceController.BulkResultDto addAttendance(McpCall c) {
        long sessionId = sessionOf(c);
        List<String> numbers = c.args().optStrList("student_numbers");
        if (numbers == null || numbers.isEmpty()) {
            throw new IllegalArgumentException("The argument 'student_numbers' must list at least one student number.");
        }
        AttendanceService.BulkResult r = attendance.bulkAdd(sessionId, numbers, c.args().optStr("note"),
                Boolean.TRUE.equals(c.args().optBool("excused")));
        return new AttendanceController.BulkResultDto(r.added(), r.skipped(), r.errors(), r.notInRoster());
    }

    private Map<String, Object> removeAttendance(McpCall c) {
        long attId = c.args().num("attendance_id");
        long sessionId = sessionOfAttendance(c, attId);
        attendance.delete(sessionId, attId);
        return Map.of("deleted", attId);
    }

    private List<AttendanceController.AttemptDto> attempts(McpCall c) {
        long sessionId = sessionOf(c);
        String state = c.args().optStr("state");
        AttemptState stateEnum = state == null || state.isBlank() ? AttemptState.REJECTED
                : parse(AttemptState.class, state, "state");
        return attendance.attempts(sessionId, stateEnum).stream()
                .map(v -> new AttendanceController.AttemptDto(v.attempt().getStudentNo(),
                        v.attempt().getResultCode(), deviceHint.of(v.attempt().getDeviceId()),
                        v.conflictStudentNo(), v.attempt().getCreatedAt().toString()))
                .toList();
    }

    // ---------- reports ----------

    /**
     * Per roster student over the opened sessions of the course. The states follow the staff student page:
     * REJECTED = teacher rejected; PRESENT = approved, or accepted without a decision; PENDING = flagged without a
     * decision; EXCUSED = away with leave (not absent, left out of the present_pct base); ABSENT = no record.
     */
    private Map<String, Object> report(long courseId) {
        List<Map<String, Object>> sessionRows = db.sql("""
                select id, title, opened_at, status from class_session
                where course_id = :course and opened_at is not null order by opened_at""")
                .param("course", courseId).query().listOfRows();
        List<Map<String, Object>> rows = db.sql("""
                select p.student_no, p.full_name,
                    (select count(*) from class_session s where s.course_id = :course and s.opened_at is not null)
                        as sessions,
                    count(a.id) filter (where a.teacher_decision = 'APPROVED'
                        or (a.teacher_decision = 'NONE' and a.status = 'ACCEPTED')) as present,
                    count(a.id) filter (where a.teacher_decision = 'NONE' and a.status = 'FLAGGED') as pending,
                    count(a.id) filter (where a.teacher_decision = 'REJECTED') as rejected,
                    count(a.id) filter (where a.teacher_decision = 'EXCUSED') as excused
                from enrollment e join person p on p.id = e.person_id
                left join attendance a on a.person_id = p.id and a.session_id in (
                    select s.id from class_session s where s.course_id = :course and s.opened_at is not null)
                where e.course_id = :course and e.deleted_at is null and e.role = 'STUDENT'
                  and p.active = true and p.deleted_at is null
                group by p.id order by p.student_no, p.full_name""")
                .param("course", courseId).query().listOfRows();
        List<Map<String, Object>> students = rows.stream().map(r -> {
            long total = ((Number) r.get("sessions")).longValue();
            long present = ((Number) r.get("present")).longValue();
            long pending = ((Number) r.get("pending")).longValue();
            long rejected = ((Number) r.get("rejected")).longValue();
            long excused = ((Number) r.get("excused")).longValue();
            long base = total - excused;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("student_no", r.get("student_no"));
            m.put("full_name", r.get("full_name"));
            m.put("sessions", total);
            m.put("present", present);
            m.put("pending", pending);
            m.put("rejected", rejected);
            m.put("excused", excused);
            m.put("absent", Math.max(0, total - present - pending - rejected - excused));
            m.put("present_pct", base <= 0 ? null : Math.round(1000.0 * present / base) / 10.0);
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessions", sessionRows);
        out.put("students", students);
        return out;
    }

    private Object studentAttendance(long courseId, String query) {
        String q = query.trim();
        List<Map<String, Object>> found = db.sql("""
                select u.id, u.full_name, u.email, u.student_no
                from enrollment e join person u on u.id = e.person_id
                where e.course_id = :course and e.role = 'STUDENT' and e.deleted_at is null and u.deleted_at is null
                  and (cast(u.id as text) = :q or lower(u.email) = lower(:q) or u.student_no = :q
                       or u.full_name ilike '%' || :q || '%')
                order by u.full_name limit 20""").param("course", courseId).param("q", q).query().listOfRows();
        if (found.isEmpty()) {
            throw new IllegalArgumentException("No student of this course matches '" + q + "'.");
        }
        if (found.size() > 1) {
            return Map.of("message", "More than one student matches. Ask again with the student number.",
                    "matches", found);
        }
        return students.student(courseId, ((Number) found.get(0).get("id")).longValue());
    }

    // ---------- helpers ----------

    private static String[] names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The argument '" + field + "' must be one of "
                    + Arrays.toString(names(type.getEnumConstants())) + ".");
        }
    }
}
