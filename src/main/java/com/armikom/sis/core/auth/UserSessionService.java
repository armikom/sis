package com.armikom.sis.core.auth;

import com.armikom.sis.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Sign-in sessions in {@code user_session}: an opaque random token travels in an HttpOnly cookie, only its
 * SHA-256 is stored, sessions expire after {@code app.auth.session-days} and survive restarts.
 */
@Service
public class UserSessionService {

    private final JdbcClient db;
    private final AppProperties props;
    private final Clock clock;

    public UserSessionService(JdbcClient db, AppProperties props, Clock clock) {
        this.db = db;
        this.props = props;
        this.clock = clock;
    }

    public String cookieName() {
        return props.auth().cookieName();
    }

    /** Creates a session for the person, records the login time and sets the cookie on the response. */
    @Transactional
    public void start(long personId, HttpServletResponse response) {
        String token = Tokens.random();
        Instant now = Instant.now(clock);
        Duration ttl = Duration.ofDays(props.auth().sessionDays());
        db.sql("insert into user_session (token_hash, person_id, created_at, expires_at) values (:h, :p, :c, :e)")
                .param("h", Tokens.sha256(token)).param("p", personId)
                .param("c", Timestamp.from(now)).param("e", Timestamp.from(now.plus(ttl)))
                .update();
        db.sql("update person set last_login_at = :now where id = :p")
                .param("now", Timestamp.from(now)).param("p", personId).update();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, ttl).toString());
    }

    /** The person behind a session cookie, if the session is live and the person may still sign in. */
    public Optional<SisPrincipal> principalFor(String token) {
        if (token == null || token.isBlank() || token.length() > 128) return Optional.empty();
        return db.sql("""
                        select p.id, p.full_name, p.email, p.student_no, p.nickname, p.system_admin
                        from user_session s join person p on p.id = s.person_id
                        where s.token_hash = :h and s.expires_at > :now and p.active and p.deleted_at is null""")
                .param("h", Tokens.sha256(token)).param("now", Timestamp.from(Instant.now(clock)))
                .query((rs, i) -> new SisPrincipal(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getBoolean(6)))
                .optional();
    }

    /** Ends the session of the request (if any) and clears the cookie. */
    @Transactional
    public void end(HttpServletRequest request, HttpServletResponse response) {
        String token = tokenOf(request);
        if (token != null && !token.isBlank()) {
            db.sql("delete from user_session where token_hash = :h").param("h", Tokens.sha256(token)).update();
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    /** Signs a person out everywhere (deleted or deactivated account, password change by an admin). */
    @Transactional
    public void endAllOf(long personId) {
        db.sql("delete from user_session where person_id = :p").param("p", personId).update();
        db.sql("delete from login_token where person_id = :p").param("p", personId).update();
    }

    /** Removes expired sessions and login links; returns the number of rows removed. */
    @Transactional
    public int purgeExpired() {
        Timestamp now = Timestamp.from(Instant.now(clock));
        int a = db.sql("delete from user_session where expires_at <= :now").param("now", now).update();
        int b = db.sql("delete from login_token where expires_at <= :now").param("now", now).update();
        return a + b;
    }

    public String tokenOf(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (cookieName().equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(cookieName(), value)
                .httpOnly(true)
                .secure(props.auth().cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
