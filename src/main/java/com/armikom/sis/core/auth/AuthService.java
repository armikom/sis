package com.armikom.sis.core.auth;

import com.armikom.sis.common.PublicBaseUrl;
import com.armikom.sis.config.AppProperties;
import com.armikom.sis.core.course.EnrollmentRepository;
import com.armikom.sis.core.mail.MailService;
import com.armikom.sis.core.person.Person;
import com.armikom.sis.core.person.PersonRepository;
import com.armikom.sis.core.person.PersonService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** The two ways to sign in: a mailed single-use link, or username + password. */
@Service
public class AuthService {

    /** At most this many links per person in one link lifetime (question-pool: 3 per 15 minutes). */
    static final int MAX_LINKS_PER_WINDOW = 3;

    private final JdbcClient db;
    private final PersonRepository personRepo;
    private final EnrollmentRepository enrollmentRepo;
    private final PasswordEncoder encoder;
    private final MailService mail;
    private final PublicBaseUrl baseUrl;
    private final AppProperties props;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(JdbcClient db, PersonRepository personRepo, EnrollmentRepository enrollmentRepo,
                       PasswordEncoder encoder, MailService mail, PublicBaseUrl baseUrl, AppProperties props,
                       Clock clock) {
        this.db = db;
        this.personRepo = personRepo;
        this.enrollmentRepo = enrollmentRepo;
        this.encoder = encoder;
        this.mail = mail;
        this.baseUrl = baseUrl;
        this.props = props;
        this.clock = clock;
        this.dummyHash = encoder.encode("timing-equaliser-" + Tokens.random());
    }

    /**
     * Mails a single-use link when the address belongs to a person who may sign in: active, not deleted, and
     * with a live enrollment or system-admin rights. Returns whether the address is such a person (the same
     * information question-pool's {@code on_roster} gives); rate-limited requests also return true.
     */
    @Transactional
    public boolean requestLink(String rawEmail) {
        String email = PersonService.normalizeEmail(rawEmail);
        if (email == null || email.length() > 254) return false;
        Optional<Person> person = personRepo.findByEmail(email).filter(Person::isUsable)
                .filter(p -> p.isSystemAdmin() || enrollmentRepo.hasLiveEnrollment(p.getId()));
        if (person.isEmpty()) return false;

        long personId = person.get().getId();
        Instant now = Instant.now(clock);
        Duration ttl = Duration.ofMinutes(props.auth().linkMinutes());
        int recent = db.sql("select count(*) from login_token where person_id = :p and created_at > :since")
                .param("p", personId).param("since", Timestamp.from(now.minus(ttl)))
                .query(Integer.class).single();
        if (recent >= MAX_LINKS_PER_WINDOW) return true;

        String token = Tokens.random();
        db.sql("""
                        insert into login_token (token_hash, person_id, created_at, expires_at)
                        values (:h, :p, :c, :e)""")
                .param("h", Tokens.sha256(token)).param("p", personId)
                .param("c", Timestamp.from(now)).param("e", Timestamp.from(now.plus(ttl)))
                .update();
        String link = baseUrl.forMailLinks() + "/login/verify?token=" + token;
        mail.sendAsync(email, "SIS - giriş bağlantınız / your sign-in link", linkText(link, ttl), link);
        return true;
    }

    private static String linkText(String link, Duration ttl) {
        long minutes = ttl.toMinutes();
        return """
                Merhaba,

                SIS'e giriş yapmak için bu bağlantıyı açın:
                %s

                Bağlantı yalnızca bir kez ve %d dakika boyunca çalışır.
                Bu isteği siz yapmadıysanız bu e-postayı dikkate almayın.

                ---

                Hello,

                Open this link to sign in to SIS:
                %s

                The link works one time and only for %d minutes.
                If you did not ask for it, you can ignore this e-mail.
                """.formatted(link, minutes, link, minutes);
    }

    /** Consumes a mailed token; the person id when it was valid, unused, not expired and the person may sign in. */
    @Transactional
    public Optional<Long> consumeLink(String token) {
        if (token == null || token.isBlank() || token.length() > 128) return Optional.empty();
        Timestamp now = Timestamp.from(Instant.now(clock));
        Optional<Long> personId = db.sql("""
                        update login_token set used_at = :now
                        where token_hash = :h and used_at is null and expires_at > :now
                        returning person_id""")
                .param("h", Tokens.sha256(token.trim())).param("now", now)
                .query(Long.class).optional();
        return personId.filter(id -> personRepo.findById(id).map(Person::isUsable).orElse(false));
    }

    /** Checks username + password; the person id on success. */
    @Transactional(readOnly = true)
    public Optional<Long> checkPassword(String username, String password) {
        if (username == null || password == null) return Optional.empty();
        Optional<Person> person = personRepo.findByUsername(username.trim());
        String hash = person.map(Person::getPasswordHash).orElse(null);
        if (hash == null) {
            encoder.matches(password, dummyHash);   // same cost whether or not the user exists
            return Optional.empty();
        }
        boolean ok;
        try {
            ok = encoder.matches(password, hash);
        } catch (IllegalArgumentException e) {
            ok = false;   // unknown {id} prefix
        }
        return ok && person.get().isUsable() ? Optional.of(person.get().getId()) : Optional.empty();
    }
}
