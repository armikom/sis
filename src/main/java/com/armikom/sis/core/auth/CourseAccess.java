package com.armikom.sis.core.auth;

import com.armikom.sis.common.ApiException;
import com.armikom.sis.common.ErrorCode;
import com.armikom.sis.core.course.Course;
import com.armikom.sis.core.course.CourseRepository;
import com.armikom.sis.core.course.EnrollmentRepository;
import com.armikom.sis.core.course.EnrollmentRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Per-course authorisation for every module. System administrators pass every check.
 * The {@code require*} methods return the course (404 when it does not exist, 403 when the current person
 * lacks the role, 401 when nobody is signed in).
 */
@Component
public class CourseAccess {

    private final CourseRepository courseRepo;
    private final EnrollmentRepository enrollmentRepo;

    public CourseAccess(CourseRepository courseRepo, EnrollmentRepository enrollmentRepo) {
        this.courseRepo = courseRepo;
        this.enrollmentRepo = enrollmentRepo;
    }

    /** The signed-in person; 401 when nobody is signed in. */
    public SisPrincipal currentPerson() {
        return currentPersonOptional().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    public Optional<SisPrincipal> currentPersonOptional() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof SisPrincipal p) {
            return Optional.of(p);
        }
        return Optional.empty();
    }

    public boolean isSystemAdmin() {
        return currentPersonOptional().map(SisPrincipal::systemAdmin).orElse(false);
    }

    public void requireSystemAdmin() {
        if (!currentPerson().systemAdmin()) throw new ApiException(ErrorCode.FORBIDDEN);
    }

    /** My live role in the course, if I am enrolled (system admins without an enrollment get empty). */
    public Optional<EnrollmentRole> roleIn(long courseId) {
        return currentPersonOptional()
                .flatMap(p -> enrollmentRepo.findLive(courseId, p.personId()))
                .map(e -> e.getRole());
    }

    public boolean isMember(long courseId) {
        return isSystemAdmin() || roleIn(courseId).isPresent();
    }

    /** OWNER, INSTRUCTOR or ASSISTANT of the course, or a system admin. */
    public boolean isStaff(long courseId) {
        return isSystemAdmin() || roleIn(courseId).map(EnrollmentRole::isStaff).orElse(false);
    }

    public Course requireMember(long courseId) {
        Course c = course(courseId);
        if (!isMember(courseId)) throw new ApiException(ErrorCode.FORBIDDEN);
        return c;
    }

    public Course requireStaff(long courseId) {
        Course c = course(courseId);
        if (!isStaff(courseId)) throw new ApiException(ErrorCode.FORBIDDEN);
        return c;
    }

    public Course requireOwnerOrAdmin(long courseId) {
        Course c = course(courseId);
        if (isSystemAdmin()) return c;
        if (roleIn(courseId).orElse(null) != EnrollmentRole.OWNER) throw new ApiException(ErrorCode.FORBIDDEN);
        return c;
    }

    /** Ids of the courses where I am staff (empty for students; admins get only their own enrollments). */
    public List<Long> staffCourseIds() {
        return enrollmentRepo.staffCourseIds(currentPerson().personId());
    }

    /** Staff in at least one course, or a system admin (may create courses and classrooms). */
    public boolean isStaffAnywhere() {
        SisPrincipal p = currentPerson();
        return p.systemAdmin() || !enrollmentRepo.staffCourseIds(p.personId()).isEmpty();
    }

    public void requireStaffAnywhere() {
        if (!isStaffAnywhere()) throw new ApiException(ErrorCode.FORBIDDEN);
    }

    private Course course(long courseId) {
        currentPerson();
        return courseRepo.findById(courseId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
}
