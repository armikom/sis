package com.armikom.sis.attendance;

import com.armikom.sis.common.ApiException;
import com.armikom.sis.common.ErrorCode;
import com.armikom.sis.core.auth.CourseAccess;
import com.armikom.sis.core.course.Enrollment;
import com.armikom.sis.core.course.EnrollmentRepository;
import com.armikom.sis.core.course.EnrollmentRole;
import com.armikom.sis.core.person.Person;
import com.armikom.sis.decision.Flag;
import com.armikom.sis.session.ClassSession;
import com.armikom.sis.session.ClassSessionRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Staff view of one student of a course: profile and attendance in every opened session. Exam history and
 * question-pool activity come from their own staff endpoints (by student number / person id).
 */
@RestController
public class CourseStudentController {

    private final CourseAccess access;
    private final EnrollmentRepository enrollmentRepo;
    private final ClassSessionRepository sessionRepo;
    private final AttendanceRepository attendanceRepo;

    public CourseStudentController(CourseAccess access, EnrollmentRepository enrollmentRepo,
                                   ClassSessionRepository sessionRepo, AttendanceRepository attendanceRepo) {
        this.access = access;
        this.enrollmentRepo = enrollmentRepo;
        this.sessionRepo = sessionRepo;
        this.attendanceRepo = attendanceRepo;
    }

    /**
     * {@code state}: PRESENT (accepted or approved), PENDING (flagged, no decision yet), REJECTED (teacher
     * rejected) or ABSENT (no record). {@code checkedInAt}/{@code source}/{@code flags}/{@code teacherNote} are
     * null or empty when there is no record.
     */
    public record SessionAttendanceDto(long sessionId, String title, String date, String sessionStatus, String state,
                                       String source, List<String> flags, String teacherNote, String checkedInAt) {}

    public record StaffStudentDto(long personId, String studentNo, String fullName, String email, String nickname,
                                   boolean active, String lastLoginAt, List<SessionAttendanceDto> attendance) {}

    @GetMapping("/api/courses/{courseId}/students/{personId}")
    @Transactional(readOnly = true)
    public StaffStudentDto student(@PathVariable long courseId, @PathVariable long personId) {
        access.requireStaff(courseId);
        Enrollment e = enrollmentRepo.findLive(courseId, personId)
                .filter(x -> x.getRole() == EnrollmentRole.STUDENT && x.getPerson().getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Person p = e.getPerson();

        Map<Long, Attendance> records = attendanceRepo.findByPersonIdAndSession_Course_Id(personId, courseId).stream()
                .collect(Collectors.toMap(Attendance::getSessionId, Function.identity(), (a, b) -> a));
        List<SessionAttendanceDto> rows = sessionRepo.findByCourseId(courseId).stream()
                .filter(s -> s.getOpenedAt() != null)
                .sorted(Comparator.comparing(ClassSession::getOpenedAt))
                .map(s -> row(s, records.get(s.getId())))
                .toList();

        return new StaffStudentDto(p.getId(), p.getStudentNo(), p.getFullName(), p.getEmail(), p.getNickname(),
                p.isActive(), p.getLastLoginAt() != null ? p.getLastLoginAt().toString() : null, rows);
    }

    private static SessionAttendanceDto row(ClassSession s, Attendance a) {
        String date = s.getOpenedAt().toString();
        if (a == null) {
            return new SessionAttendanceDto(s.getId(), s.getTitle(), date, s.getStatus().name(), "ABSENT",
                    null, List.of(), null, null);
        }
        String state = a.getTeacherDecision() == TeacherDecision.REJECTED ? "REJECTED"
                : a.isPresent() ? "PRESENT"
                : "PENDING";
        return new SessionAttendanceDto(s.getId(), s.getTitle(), date, s.getStatus().name(), state,
                a.getSource().name(), a.getFlags().stream().sorted().map(Flag::name).toList(),
                a.getTeacherNote(), a.getCreatedAt().toString());
    }
}
