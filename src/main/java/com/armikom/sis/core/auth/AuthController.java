package com.armikom.sis.core.auth;

import com.armikom.sis.common.ApiException;
import com.armikom.sis.common.ErrorCode;
import com.armikom.sis.core.course.Enrollment;
import com.armikom.sis.core.course.EnrollmentRepository;
import com.armikom.sis.core.person.Person;
import com.armikom.sis.core.person.PersonRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final UserSessionService sessions;
    private final CourseAccess access;
    private final PersonRepository personRepo;
    private final EnrollmentRepository enrollmentRepo;

    public AuthController(AuthService auth, UserSessionService sessions, CourseAccess access,
                          PersonRepository personRepo, EnrollmentRepository enrollmentRepo) {
        this.auth = auth;
        this.sessions = sessions;
        this.access = access;
        this.personRepo = personRepo;
        this.enrollmentRepo = enrollmentRepo;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record LinkRequest(String email) {}
    public record LinkResponse(boolean ok, boolean onRoster) {}
    public record VerifyRequest(String token) {}

    /** One course of the signed-in person with their role in it. */
    public record MyCourseDto(long id, String code, String name, String term, String language, String role,
                              boolean attendanceEnabled, boolean poolEnabled, boolean archived) {
        static MyCourseDto from(Enrollment e) {
            var c = e.getCourse();
            return new MyCourseDto(c.getId(), c.getCode(), c.getName(), c.getTerm(), c.getLanguage(),
                    e.getRole().name(), c.isAttendanceEnabled(), c.isPoolEnabled(), c.isArchived());
        }
    }

    /** {@code staff}: staff of at least one course, or a system admin. */
    public record MeDto(long id, String fullName, String email, String studentNo, String nickname, String username,
                        boolean systemAdmin, boolean staff, List<MyCourseDto> courses) {}

    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void csrf(CsrfToken csrfToken) {
        // Loading the deferred token makes the repository write the XSRF-TOKEN cookie.
        csrfToken.getToken();
    }

    @PostMapping("/login")
    public MeDto login(@Valid @RequestBody LoginRequest req, HttpServletResponse response) {
        long personId = auth.checkPassword(req.username(), req.password())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        sessions.start(personId, response);
        return me(personId);
    }

    @PostMapping("/request")
    public LinkResponse request(@RequestBody LinkRequest req) {
        return new LinkResponse(true, auth.requestLink(req.email()));
    }

    @PostMapping("/verify")
    public MeDto verify(@RequestBody VerifyRequest req, HttpServletResponse response) {
        long personId = auth.consumeLink(req.token()).orElseThrow(() -> new ApiException(ErrorCode.LINK_INVALID));
        sessions.start(personId, response);
        return me(personId);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.end(request, response);
    }

    @GetMapping("/me")
    public MeDto me() {
        return me(access.currentPerson().personId());
    }

    private MeDto me(long personId) {
        Person p = personRepo.findById(personId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        List<MyCourseDto> courses = enrollmentRepo.findLiveByPerson(personId).stream().map(MyCourseDto::from).toList();
        boolean staff = p.isSystemAdmin() || courses.stream().anyMatch(c -> !"STUDENT".equals(c.role()));
        return new MeDto(p.getId(), p.getFullName(), p.getEmail(), p.getStudentNo(), p.getNickname(),
                p.getUsername(), p.isSystemAdmin(), staff, courses);
    }
}
