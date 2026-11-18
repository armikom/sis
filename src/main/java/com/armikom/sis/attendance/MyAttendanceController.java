package com.armikom.sis.attendance;

import com.armikom.sis.core.auth.CourseAccess;
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
 * A signed-in student's own attendance in a course. Only present/absent is exposed: flags, latencies and
 * thresholds never reach students.
 */
@RestController
public class MyAttendanceController {

    private final CourseAccess access;
    private final ClassSessionRepository sessionRepo;
    private final AttendanceRepository attendanceRepo;

    public MyAttendanceController(CourseAccess access, ClassSessionRepository sessionRepo,
                                  AttendanceRepository attendanceRepo) {
        this.access = access;
        this.sessionRepo = sessionRepo;
        this.attendanceRepo = attendanceRepo;
    }

    /** {@code date}: when the session was opened. {@code present}: a record exists that the teacher did not reject. */
    public record MyAttendanceDto(long sessionId, String title, String date, boolean present) {}

    @GetMapping("/api/courses/{courseId}/my-attendance")
    @Transactional(readOnly = true)
    public List<MyAttendanceDto> myAttendance(@PathVariable long courseId) {
        access.requireMember(courseId);
        long me = access.currentPerson().personId();
        Map<Long, Attendance> mine = attendanceRepo.findByPersonIdAndSession_Course_Id(me, courseId).stream()
                .collect(Collectors.toMap(Attendance::getSessionId, Function.identity(), (a, b) -> a));
        return sessionRepo.findByCourseId(courseId).stream()
                .filter(s -> s.getOpenedAt() != null)
                .sorted(Comparator.comparing(ClassSession::getOpenedAt))
                .map(s -> {
                    Attendance a = mine.get(s.getId());
                    boolean present = a != null && a.getTeacherDecision() != TeacherDecision.REJECTED;
                    return new MyAttendanceDto(s.getId(), s.getTitle(), s.getOpenedAt().toString(), present);
                })
                .toList();
    }
}
