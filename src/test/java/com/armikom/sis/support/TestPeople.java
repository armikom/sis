package com.armikom.sis.support;

import com.armikom.sis.core.auth.UserSessionService;
import com.armikom.sis.core.course.Course;
import com.armikom.sis.core.course.CourseRepository;
import com.armikom.sis.core.course.Enrollment;
import com.armikom.sis.core.course.EnrollmentRepository;
import com.armikom.sis.core.course.EnrollmentRole;
import com.armikom.sis.core.person.Person;
import com.armikom.sis.core.person.PersonRepository;
import com.armikom.sis.core.person.PersonService;
import jakarta.servlet.http.Cookie;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Staff accounts with real {@code sis_session} cookies, and direct roster enrollment, for integration tests.
 * A staff person owns a seed course, so they may create courses and classrooms.
 */
public class TestPeople {

    private final PersonRepository persons;
    private final PersonService personService;
    private final PasswordEncoder encoder;
    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;
    private final UserSessionService sessions;
    private final TransactionTemplate tx;
    private final Map<String, Cookie> cookies = new ConcurrentHashMap<>();

    public TestPeople(PersonRepository persons, PersonService personService, PasswordEncoder encoder,
                      CourseRepository courses, EnrollmentRepository enrollments, UserSessionService sessions,
                      TransactionTemplate tx) {
        this.persons = persons;
        this.personService = personService;
        this.encoder = encoder;
        this.courses = courses;
        this.enrollments = enrollments;
        this.sessions = sessions;
        this.tx = tx;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        public TestPeople testPeople(PersonRepository persons, PersonService personService, PasswordEncoder encoder,
                                     CourseRepository courses, EnrollmentRepository enrollments,
                                     UserSessionService sessions, TransactionTemplate tx) {
            return new TestPeople(persons, personService, encoder, courses, enrollments, sessions, tx);
        }
    }

    /** Ensures a staff person with username / password "parola123" and full name "Öğretmen {username}". */
    public long staff(String username) {
        return tx.execute(st -> persons.findByUsername(username).map(Person::getId).orElseGet(() -> {
            Person p = new Person("Öğretmen " + username, personService.newStaffNickname());
            p.setUsername(username);
            p.setPasswordHash(encoder.encode("parola123"));
            persons.save(p);
            Course seed = courses.save(new Course("SEED-" + username, "Seed", "2026-FALL"));
            enrollments.save(new Enrollment(seed, p, EnrollmentRole.OWNER));
            return p.getId();
        }));
    }

    /** The session cookie of a staff person (created on first use). */
    public Cookie cookie(String username) {
        return cookies.computeIfAbsent(username, u -> {
            long id = staff(u);
            MockHttpServletResponse res = new MockHttpServletResponse();
            sessions.start(id, res);
            return res.getCookie(sessions.cookieName());
        });
    }

    /** Adds the session cookie of {@code username} to the request, keeping other cookies. */
    public RequestPostProcessor as(String username) {
        Cookie c = cookie(username);
        return request -> {
            List<Cookie> all = new ArrayList<>();
            if (request.getCookies() != null) all.addAll(Arrays.asList(request.getCookies()));
            all.add(c);
            request.setCookies(all.toArray(new Cookie[0]));
            return request;
        };
    }

    /** Puts a student number on the course roster (creating the person) unless it is already there. */
    public void enroll(long courseId, String studentNo, String fullName) {
        tx.executeWithoutResult(st -> {
            if (enrollments.rosterStudent(courseId, studentNo).isPresent()) return;
            Person p = persons.findByStudentNo(studentNo)
                    .orElseGet(() -> personService.createStudent(fullName, studentNo, null));
            Course c = courses.getReferenceById(courseId);
            var existing = enrollments.findByCourse_IdAndPerson_Id(courseId, p.getId());
            if (existing.isPresent()) {
                existing.get().setDeletedAt(null);
                existing.get().setRole(EnrollmentRole.STUDENT);
            } else {
                enrollments.save(new Enrollment(c, p, EnrollmentRole.STUDENT));
            }
        });
    }
}
