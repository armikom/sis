package com.armikom.sis.core.course;

import com.armikom.sis.common.ApiException;
import com.armikom.sis.common.ErrorCode;
import com.armikom.sis.config.AppProperties;
import com.armikom.sis.core.auth.CourseAccess;
import com.armikom.sis.core.person.Person;
import com.armikom.sis.core.person.PersonRepository;
import com.armikom.sis.core.person.PersonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** The student list of a course: STUDENT enrollments, upserting people by student number, then e-mail. */
@Service
public class RosterService {

    private final EnrollmentRepository enrollmentRepo;
    private final PersonRepository personRepo;
    private final PersonService personService;
    private final CourseAccess access;
    private final Clock clock;
    private final Pattern studentNoPattern;
    private final RosterParser parser;

    public RosterService(EnrollmentRepository enrollmentRepo, PersonRepository personRepo, PersonService personService,
                         CourseAccess access, Clock clock, AppProperties props) {
        this.enrollmentRepo = enrollmentRepo;
        this.personRepo = personRepo;
        this.personService = personService;
        this.access = access;
        this.clock = clock;
        this.studentNoPattern = Pattern.compile(props.checkin().studentNoPattern());
        this.parser = new RosterParser(studentNoPattern);
    }

    public enum Mode { MERGE, REPLACE }

    public record ImportResult(int imported, int created, int updated, int reactivated, int removed, int skipped,
                               List<RosterParser.ImportError> errors) {}

    @Transactional(readOnly = true)
    public List<RosterStudent> list(long courseId) {
        access.requireStaff(courseId);
        return enrollmentRepo.roster(courseId);
    }

    @Transactional
    public RosterStudent add(long courseId, String rawStudentNo, String rawFullName, String rawEmail) {
        Course course = access.requireStaff(courseId);
        String no = rawStudentNo == null ? "" : rawStudentNo.trim();
        if (!studentNoPattern.matcher(no).matches()) throw invalid("studentNo", "Geçersiz format");
        String name = rawFullName == null ? "" : rawFullName.trim();
        if (name.isEmpty() || name.length() > RosterParser.MAX_NAME) throw invalid("fullName", "1-200 karakter olmalı");
        String email = PersonService.normalizeEmail(rawEmail);
        if (email != null && !PersonService.isEmail(email)) throw invalid("email", "Geçersiz e-posta");
        if (enrollmentRepo.rosterStudent(courseId, no).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("studentNo", "Bu öğrenci zaten listede"));
        }
        Outcome o = upsert(course, no, name, email);
        if (o.error() != null) throw new ApiException(ErrorCode.CONFLICT, Map.of("studentNo", o.error()));
        Person p = o.person();
        return new RosterStudent(p.getId(), p.getStudentNo(), p.getFullName(), p.getEmail());
    }

    /** Removes a student from the course (soft delete of the enrollment); their records stay. */
    @Transactional
    public void remove(long courseId, long personId) {
        access.requireStaff(courseId);
        enrollmentRepo.findLive(courseId, personId)
                .filter(e -> e.getRole() == EnrollmentRole.STUDENT)
                .ifPresent(e -> e.setDeletedAt(Instant.now(clock)));
    }

    @Transactional
    public ImportResult importText(long courseId, String text, String mode) {
        Course course = access.requireStaff(courseId);
        return apply(course, parser.parse(text), parseMode(mode));
    }

    @Transactional
    public ImportResult importStream(long courseId, InputStream in, String mode) {
        Course course = access.requireStaff(courseId);
        Mode m = parseMode(mode);
        RosterParser.Parsed parsed;
        try {
            parsed = parser.parse(in);
        } catch (IOException e) {
            return new ImportResult(0, 0, 0, 0, 0, 0, List.of(new RosterParser.ImportError(0, "Dosya okunamadı.")));
        }
        return apply(course, parsed, m);
    }

    private ImportResult apply(Course course, RosterParser.Parsed parsed, Mode mode) {
        int created = 0, updated = 0, reactivated = 0, imported = 0, removed = 0;
        List<RosterParser.ImportError> errors = new ArrayList<>(parsed.errors());
        Set<Long> inFile = new HashSet<>();
        for (RosterParser.Row row : parsed.rows()) {
            Outcome o = upsert(course, row.studentNo(), row.fullName(), row.email());
            if (o.error() != null) {
                errors.add(new RosterParser.ImportError(row.line(), o.error()));
                continue;
            }
            inFile.add(o.person().getId());
            imported++;
            if (o.createdPerson()) created++;
            else updated++;
            if (o.reactivated()) reactivated++;
        }
        if (mode == Mode.REPLACE) {
            Instant now = Instant.now(clock);
            for (Enrollment e : enrollmentRepo.findLiveByCourseAndRoles(course.getId(), List.of(EnrollmentRole.STUDENT))) {
                if (!inFile.contains(e.getPerson().getId())) {
                    e.setDeletedAt(now);
                    removed++;
                }
            }
        }
        errors.sort(Comparator.comparingInt(RosterParser.ImportError::line));
        return new ImportResult(imported, created, updated, reactivated, removed, parsed.skipped(), errors);
    }

    private record Outcome(Person person, boolean createdPerson, boolean reactivated, String error) {
        static Outcome error(String message) { return new Outcome(null, false, false, message); }
    }

    /**
     * Finds the person by student number, then by e-mail; creates them when missing; sets name / e-mail /
     * number when given; re-activates a soft-deleted person or enrollment; enrolls as STUDENT.
     */
    private Outcome upsert(Course course, String studentNo, String fullName, String email) {
        Person p = personRepo.findByStudentNo(studentNo).orElse(null);
        if (p == null && email != null) {
            p = personRepo.findByEmail(email).orElse(null);
            if (p != null && p.getStudentNo() != null && !p.getStudentNo().equals(studentNo)) {
                return Outcome.error("E-posta başka bir öğrenci numarasıyla kayıtlı: " + email);
            }
        }
        boolean createdPerson = false;
        boolean reactivated = false;
        if (p == null) {
            p = personService.createStudent(fullName, studentNo, email);
            createdPerson = true;
        } else {
            if (email != null && !email.equals(p.getEmail())) {
                Optional<Person> other = personRepo.findByEmail(email);
                if (other.isPresent() && !other.get().getId().equals(p.getId())) {
                    return Outcome.error("E-posta başka bir kişide kayıtlı: " + email);
                }
                p.setEmail(email);
            }
            if (p.getStudentNo() == null) p.setStudentNo(studentNo);
            if (fullName != null && !fullName.isBlank()) p.setFullName(fullName);
            if (!p.isUsable()) {
                p.setActive(true);
                p.setDeletedAt(null);
                reactivated = true;
            }
        }
        Optional<Enrollment> existing = enrollmentRepo.findByCourse_IdAndPerson_Id(course.getId(), p.getId());
        if (existing.isEmpty()) {
            enrollmentRepo.save(new Enrollment(course, p, EnrollmentRole.STUDENT));
        } else {
            Enrollment e = existing.get();
            if (e.isLive() && e.getRole() != EnrollmentRole.STUDENT) {
                return Outcome.error("Bu kişi dersin personeli: " + studentNo);
            }
            if (!e.isLive()) {
                e.setDeletedAt(null);
                e.setRole(EnrollmentRole.STUDENT);
                reactivated = true;
            }
        }
        return new Outcome(p, createdPerson, reactivated, null);
    }

    private static Mode parseMode(String mode) {
        if (mode == null || mode.isBlank()) return Mode.MERGE;
        try {
            return Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("mode", "MERGE|REPLACE");
        }
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, Map.of(field, message));
    }
}
