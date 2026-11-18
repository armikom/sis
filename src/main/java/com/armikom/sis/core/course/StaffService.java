package com.armikom.sis.core.course;

import com.armikom.sis.common.ApiException;
import com.armikom.sis.common.ErrorCode;
import com.armikom.sis.core.auth.CourseAccess;
import com.armikom.sis.core.person.Person;
import com.armikom.sis.core.person.PersonRepository;
import com.armikom.sis.core.person.PersonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Course staff: OWNER, INSTRUCTOR and ASSISTANT enrollments. Managed by the owner or a system admin. */
@Service
public class StaffService {

    static final List<EnrollmentRole> STAFF_ROLES = List.of(EnrollmentRole.OWNER, EnrollmentRole.INSTRUCTOR,
            EnrollmentRole.ASSISTANT);

    private final EnrollmentRepository enrollmentRepo;
    private final PersonRepository personRepo;
    private final PersonService personService;
    private final CourseAccess access;
    private final Clock clock;

    public StaffService(EnrollmentRepository enrollmentRepo, PersonRepository personRepo, PersonService personService,
                        CourseAccess access, Clock clock) {
        this.enrollmentRepo = enrollmentRepo;
        this.personRepo = personRepo;
        this.personService = personService;
        this.access = access;
        this.clock = clock;
    }

    public record StaffMember(long personId, String fullName, String email, String nickname, String role) {
        static StaffMember from(Enrollment e) {
            Person p = e.getPerson();
            return new StaffMember(p.getId(), p.getFullName(), p.getEmail(), p.getNickname(), e.getRole().name());
        }
    }

    @Transactional(readOnly = true)
    public List<StaffMember> list(long courseId) {
        access.requireStaff(courseId);
        return enrollmentRepo.findLiveByCourseAndRoles(courseId, STAFF_ROLES).stream().map(StaffMember::from).toList();
    }

    /**
     * Adds (or changes the role of) a staff member by e-mail. A person that does not exist is created with a
     * staff nickname; a soft-deleted person is brought back (as question-pool's addTeacher did).
     */
    @Transactional
    public StaffMember add(long courseId, String rawEmail, String rawFullName, String rawRole) {
        Course course = access.requireOwnerOrAdmin(courseId);
        String email = PersonService.normalizeEmail(rawEmail);
        if (email == null || !PersonService.isEmail(email)) throw invalid("email", "Geçerli bir e-posta yazın");
        String name = rawFullName == null ? "" : rawFullName.trim();
        if (name.length() > 200) throw invalid("fullName", "En fazla 200 karakter");
        EnrollmentRole role;
        try {
            role = EnrollmentRole.valueOf((rawRole == null ? "INSTRUCTOR" : rawRole).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("role", "OWNER|INSTRUCTOR|ASSISTANT");
        }
        if (!role.isStaff()) throw invalid("role", "OWNER|INSTRUCTOR|ASSISTANT");

        Optional<Person> found = personRepo.findByEmail(email);
        Person p;
        if (found.isPresent()) {
            p = found.get();
            if (!p.isUsable()) {
                p.setActive(true);
                p.setDeletedAt(null);
            }
            if (!name.isEmpty()) p.setFullName(name);
        } else {
            if (name.isEmpty()) throw invalid("fullName", "Ad soyad zorunlu");
            p = personService.createStaff(name, email);
        }
        Optional<Enrollment> existing = enrollmentRepo.findByCourse_IdAndPerson_Id(courseId, p.getId());
        Enrollment e;
        if (existing.isPresent()) {
            e = existing.get();
            if (e.isLive() && e.getRole() == EnrollmentRole.OWNER && role != EnrollmentRole.OWNER) {
                ensureAnotherOwner(courseId, p.getId());
            }
            e.setRole(role);
            e.setDeletedAt(null);
        } else {
            e = enrollmentRepo.save(new Enrollment(course, p, role));
        }
        return StaffMember.from(e);
    }

    @Transactional
    public void remove(long courseId, long personId) {
        access.requireOwnerOrAdmin(courseId);
        if (access.currentPerson().personId() == personId) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("personId", "Kendinizi dersten çıkaramazsınız"));
        }
        Enrollment e = enrollmentRepo.findLive(courseId, personId)
                .filter(x -> x.getRole().isStaff())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (e.getRole() == EnrollmentRole.OWNER) ensureAnotherOwner(courseId, personId);
        e.setDeletedAt(Instant.now(clock));
    }

    private void ensureAnotherOwner(long courseId, long personId) {
        boolean another = enrollmentRepo.findLiveByCourseAndRoles(courseId, List.of(EnrollmentRole.OWNER)).stream()
                .anyMatch(x -> !x.getPerson().getId().equals(personId));
        if (!another) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("role", "Dersin en az bir sahibi (OWNER) olmalı"));
        }
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, Map.of(field, message));
    }
}
