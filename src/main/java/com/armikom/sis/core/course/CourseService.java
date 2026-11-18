package com.armikom.sis.core.course;

import com.armikom.sis.common.ApiException;
import com.armikom.sis.common.ErrorCode;
import com.armikom.sis.core.auth.CourseAccess;
import com.armikom.sis.core.auth.SisPrincipal;
import com.armikom.sis.core.person.PersonRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Course offerings and their settings. Authorisation through {@link CourseAccess}. */
@Service
public class CourseService {

    static final Pattern TERM = Pattern.compile("^[A-Z0-9][A-Z0-9-]{0,15}$");
    static final Set<String> LANGUAGES = Set.of("tr", "en");

    private final CourseRepository courseRepo;
    private final EnrollmentRepository enrollmentRepo;
    private final PersonRepository personRepo;
    private final CourseAccess access;
    private final JdbcClient db;
    private final Clock clock;

    public CourseService(CourseRepository courseRepo, EnrollmentRepository enrollmentRepo, PersonRepository personRepo,
                         CourseAccess access, JdbcClient db, Clock clock) {
        this.courseRepo = courseRepo;
        this.enrollmentRepo = enrollmentRepo;
        this.personRepo = personRepo;
        this.access = access;
        this.db = db;
        this.clock = clock;
    }

    /** Everything that can be set on a course; null leaves a value unchanged (or takes the default on create). */
    public record CourseData(String code, String name, String term, String language, Boolean attendanceEnabled,
                             Boolean poolEnabled, Integer questionsPerChapter, Integer askAiPerDay, String aiContext) {}

    /** A course together with the current person's role in it (null for a system admin who is not enrolled). */
    public record CourseView(Course course, EnrollmentRole myRole, long rosterCount) {}

    @Transactional(readOnly = true)
    public List<CourseView> listMine() {
        SisPrincipal me = access.currentPerson();
        Map<Long, EnrollmentRole> roles = new HashMap<>();
        for (Enrollment e : enrollmentRepo.findLiveByPerson(me.personId())) {
            roles.put(e.getCourse().getId(), e.getRole());
        }
        List<Course> courses = me.systemAdmin() ? courseRepo.findAllByOrderByTermDescCodeAsc()
                : courseRepo.findLiveForPerson(me.personId());
        return courses.stream()
                .map(c -> new CourseView(c, roles.get(c.getId()), enrollmentRepo.rosterCount(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CourseView get(long id) {
        Course c = access.requireMember(id);
        return new CourseView(c, access.roleIn(id).orElse(null), enrollmentRepo.rosterCount(id));
    }

    @Transactional
    public CourseView create(CourseData d) {
        access.requireStaffAnywhere();
        SisPrincipal me = access.currentPerson();
        String code = required(d.code(), "code", 32);
        String name = required(d.name(), "name", 200);
        String term = d.term() == null || d.term().isBlank() ? currentTerm(LocalDate.now(clock)) : term(d.term());
        if (courseRepo.existsByCodeAndTerm(code, term)) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("code", "Bu dönemde bu ders kodu zaten var"));
        }
        Course c = new Course(code, name, term);
        apply(c, d);
        courseRepo.save(c);
        enrollmentRepo.save(new Enrollment(c, personRepo.getReferenceById(me.personId()), EnrollmentRole.OWNER));
        return new CourseView(c, EnrollmentRole.OWNER, 0);
    }

    @Transactional
    public CourseView update(long id, CourseData d) {
        Course c = access.requireStaff(id);
        if (d.code() != null) c.setCode(required(d.code(), "code", 32));
        if (d.name() != null) c.setName(required(d.name(), "name", 200));
        if (d.term() != null) c.setTerm(term(d.term()));
        if (courseRepo.existsByCodeAndTermAndIdNot(c.getCode(), c.getTerm(), id)) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("code", "Bu dönemde bu ders kodu zaten var"));
        }
        apply(c, d);
        return new CourseView(c, access.roleIn(id).orElse(null), enrollmentRepo.rosterCount(id));
    }

    @Transactional
    public CourseView setArchived(long id, boolean archived) {
        Course c = access.requireOwnerOrAdmin(id);
        c.setArchivedAt(archived ? (c.getArchivedAt() != null ? c.getArchivedAt() : Instant.now(clock)) : null);
        return new CourseView(c, access.roleIn(id).orElse(null), enrollmentRepo.rosterCount(id));
    }

    /** Deletes a course that has no attendance sessions, exams or pool questions (enrollments and chapters go with it). */
    @Transactional
    public void delete(long id) {
        Course c = access.requireOwnerOrAdmin(id);
        boolean used = db.sql("""
                        select exists(select 1 from class_session where course_id = :id)
                            or exists(select 1 from exam where course_id = :id)
                            or exists(select 1 from question q join chapter ch on ch.id = q.chapter_id
                                      where ch.course_id = :id)""")
                .param("id", id).query(Boolean.class).single();
        if (used) {
            throw new ApiException(ErrorCode.CONFLICT,
                    Map.of("course", "Oturumu, sınavı veya sorusu olan ders silinemez; arşivleyin"));
        }
        // Plain SQL: enrollments and chapters go through ON DELETE CASCADE. A JPA remove would trip over the
        // enrollment the access check just loaded into the persistence context.
        db.sql("delete from course where id = :id").param("id", c.getId()).update();
    }

    private static void apply(Course c, CourseData d) {
        if (d.language() != null) {
            String lang = d.language().trim().toLowerCase(Locale.ROOT);
            if (!LANGUAGES.contains(lang)) throw invalid("language", "tr veya en olmalı");
            c.setLanguage(lang);
        }
        if (d.attendanceEnabled() != null) c.setAttendanceEnabled(d.attendanceEnabled());
        if (d.poolEnabled() != null) c.setPoolEnabled(d.poolEnabled());
        if (d.questionsPerChapter() != null) {
            if (d.questionsPerChapter() < 0 || d.questionsPerChapter() > 100) throw invalid("questionsPerChapter", "0-100 arasında olmalı");
            c.setQuestionsPerChapter(d.questionsPerChapter());
        }
        if (d.askAiPerDay() != null) {
            if (d.askAiPerDay() < 0 || d.askAiPerDay() > 1000) throw invalid("askAiPerDay", "0-1000 arasında olmalı");
            c.setAskAiPerDay(d.askAiPerDay());
        }
        if (d.aiContext() != null) {
            if (d.aiContext().length() > 20_000) throw invalid("aiContext", "En fazla 20000 karakter");
            c.setAiContext(d.aiContext().trim());
        }
    }

    /** Academic term of a date: Sep-Jan FALL (January belongs to the previous year), Feb-Jun SPRING, Jul-Aug SUMMER. */
    public static String currentTerm(LocalDate d) {
        int m = d.getMonthValue();
        if (m == 1) return (d.getYear() - 1) + "-FALL";
        if (m >= 9) return d.getYear() + "-FALL";
        if (m <= 6) return d.getYear() + "-SPRING";
        return d.getYear() + "-SUMMER";
    }

    private static String term(String raw) {
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (!TERM.matcher(t).matches()) throw invalid("term", "Örnek: 2026-FALL");
        return t;
    }

    private static String required(String value, String field, int max) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) throw invalid(field, "Zorunlu");
        if (v.length() > max) throw invalid(field, "En fazla " + max + " karakter");
        return v;
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, Map.of(field, message));
    }
}
