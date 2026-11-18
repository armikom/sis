package com.armikom.sis.core.admin;

import com.armikom.sis.common.ApiException;
import com.armikom.sis.common.ErrorCode;
import com.armikom.sis.config.AppProperties;
import com.armikom.sis.core.auth.CourseAccess;
import com.armikom.sis.core.auth.UserSessionService;
import com.armikom.sis.core.person.Person;
import com.armikom.sis.core.person.PersonRepository;
import com.armikom.sis.core.person.PersonService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** People administration for system admins (replaces yoklama's teacher screen). /api/admin/** requires ROLE_ADMIN. */
@RestController
@RequestMapping("/api/admin/persons")
public class AdminPersonController {

    static final int MIN_PASSWORD = 8;
    static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._@-]{1,64}$");

    private final PersonRepository repo;
    private final PersonService personService;
    private final PasswordEncoder encoder;
    private final CourseAccess access;
    private final UserSessionService sessions;
    private final Clock clock;
    private final Pattern studentNoPattern;

    public AdminPersonController(PersonRepository repo, PersonService personService, PasswordEncoder encoder,
                                 CourseAccess access, UserSessionService sessions, Clock clock, AppProperties props) {
        this.repo = repo;
        this.personService = personService;
        this.encoder = encoder;
        this.access = access;
        this.sessions = sessions;
        this.clock = clock;
        this.studentNoPattern = Pattern.compile(props.checkin().studentNoPattern());
    }

    public record PersonDto(long id, String fullName, String email, String studentNo, String username,
                            String nickname, boolean systemAdmin, boolean active, boolean deleted,
                            boolean hasPassword, String createdAt, String lastLoginAt) {
        static PersonDto from(Person p) {
            return new PersonDto(p.getId(), p.getFullName(), p.getEmail(), p.getStudentNo(), p.getUsername(),
                    p.getNickname(), p.isSystemAdmin(), p.isActive(), p.getDeletedAt() != null,
                    p.getPasswordHash() != null, p.getCreatedAt().toString(),
                    p.getLastLoginAt() != null ? p.getLastLoginAt().toString() : null);
        }
    }

    /**
     * Create / edit body. On edit a null field is left unchanged; an empty string clears e-mail, student number,
     * username or password.
     */
    public record PersonRequest(String fullName, String email, String studentNo, String username, String password,
                                Boolean active, Boolean systemAdmin) {}

    @GetMapping
    public List<PersonDto> list(@RequestParam(defaultValue = "") String q,
                                @RequestParam(defaultValue = "200") int limit) {
        String query = q.trim().toLowerCase(Locale.ROOT);
        return repo.search(query, PageRequest.of(0, Math.max(1, Math.min(limit, 1000)))).stream()
                .map(PersonDto::from).toList();
    }

    @GetMapping("/{id}")
    public PersonDto get(@PathVariable long id) {
        return PersonDto.from(find(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public PersonDto create(@RequestBody PersonRequest req) {
        String name = req.fullName() == null ? "" : req.fullName().trim();
        if (name.isEmpty() || name.length() > 200) throw invalid("fullName", "1-200 karakter olmalı");
        boolean staffLike = Boolean.TRUE.equals(req.systemAdmin()) || blankToNull(req.studentNo()) == null;
        Person p = new Person(name, staffLike ? personService.newStaffNickname() : personService.newNickname());
        apply(p, req);
        return PersonDto.from(repo.save(p));
    }

    @PatchMapping("/{id}")
    @Transactional
    public PersonDto patch(@PathVariable long id, @RequestBody PersonRequest req) {
        Person p = find(id);
        boolean self = p.getId() == access.currentPerson().personId();
        if (self && (Boolean.FALSE.equals(req.active()) || Boolean.FALSE.equals(req.systemAdmin()))) {
            throw new ApiException(ErrorCode.CONFLICT,
                    Map.of("active", "Kendi hesabınızı devre dışı bırakamaz veya yönetici yetkinizi kaldıramazsınız"));
        }
        if (req.fullName() != null) {
            String name = req.fullName().trim();
            if (name.isEmpty() || name.length() > 200) throw invalid("fullName", "1-200 karakter olmalı");
            p.setFullName(name);
        }
        boolean wasActive = p.isActive();
        apply(p, req);
        if (wasActive && !p.isActive()) sessions.endAllOf(p.getId());
        return PersonDto.from(p);
    }

    /** Soft delete: the person can no longer sign in and leaves every roster; their records stay. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable long id) {
        Person p = find(id);
        if (p.getId() == access.currentPerson().personId()) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("id", "Kendi hesabınızı silemezsiniz"));
        }
        p.setActive(false);
        if (p.getDeletedAt() == null) p.setDeletedAt(Instant.now(clock));
        sessions.endAllOf(p.getId());
    }

    private void apply(Person p, PersonRequest req) {
        if (req.email() != null) {
            String email = PersonService.normalizeEmail(req.email());
            if (email != null && !PersonService.isEmail(email)) throw invalid("email", "Geçersiz e-posta");
            if (email != null) repo.findByEmail(email).filter(o -> !o.getId().equals(p.getId()))
                    .ifPresent(o -> { throw conflict("email", "Bu e-posta başka bir kişide kayıtlı"); });
            p.setEmail(email);
        }
        if (req.studentNo() != null) {
            String no = blankToNull(req.studentNo());
            if (no != null && !studentNoPattern.matcher(no).matches()) throw invalid("studentNo", "Geçersiz format");
            if (no != null) repo.findByStudentNo(no).filter(o -> !o.getId().equals(p.getId()))
                    .ifPresent(o -> { throw conflict("studentNo", "Bu öğrenci numarası başka bir kişide kayıtlı"); });
            p.setStudentNo(no);
        }
        if (req.username() != null) {
            String u = blankToNull(req.username());
            if (u != null && !USERNAME.matcher(u).matches()) throw invalid("username", "Harf, rakam ve . _ @ - kullanın");
            if (u != null) repo.findByUsername(u).filter(o -> !o.getId().equals(p.getId()))
                    .ifPresent(o -> { throw conflict("username", "Bu kullanıcı adı zaten var"); });
            p.setUsername(u);
        }
        if (req.password() != null) {
            if (req.password().isEmpty()) {
                p.setPasswordHash(null);
            } else {
                if (req.password().length() < MIN_PASSWORD || req.password().length() > 72) {
                    throw invalid("password", "8-72 karakter olmalı");
                }
                p.setPasswordHash(encoder.encode(req.password()));
            }
        }
        if (req.active() != null) {
            p.setActive(req.active());
            if (req.active()) p.setDeletedAt(null);
        }
        if (req.systemAdmin() != null) p.setSystemAdmin(req.systemAdmin());
    }

    private Person find(long id) {
        return repo.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, Map.of(field, message));
    }

    private static ApiException conflict(String field, String message) {
        return new ApiException(ErrorCode.CONFLICT, Map.of(field, message));
    }
}
