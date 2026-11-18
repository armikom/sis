package com.armikom.sis.agent;

import com.armikom.sis.core.auth.SisPrincipal;
import com.armikom.sis.core.auth.Tokens;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Personal MCP tokens (one per person; only the SHA-256 hash is stored). Ported from question-pool's AuthService. */
@Service
public class McpTokenService {

    static final String PREFIX = "sis_";

    private final JdbcClient db;

    public McpTokenService(JdbcClient db) {
        this.db = db;
    }

    /**
     * Creates the personal MCP token of a person and returns it. The token is shown only once; a new token replaces
     * the old one, so an agent that still uses the old token stops working.
     */
    public String newToken(long personId) {
        String token = PREFIX + Tokens.random();
        db.sql("""
                insert into mcp_token (token_hash, person_id, hint) values (:h, :u, :hint)
                on conflict (person_id) do update set token_hash = excluded.token_hash, hint = excluded.hint,
                    created_at = now(), last_used_at = null""").param("h", Tokens.sha256(token)).param("u", personId)
                .param("hint", token.substring(0, 7) + "…" + token.substring(token.length() - 4)).update();
        return token;
    }

    public void delete(long personId) {
        db.sql("delete from mcp_token where person_id = :u").param("u", personId).update();
    }

    /** The usable (active, not deleted) person of a token; records the use at most once a minute. */
    public Optional<SisPrincipal> personFor(String token) {
        if (token == null || token.isBlank() || token.length() > 200) {
            return Optional.empty();
        }
        String hash = Tokens.sha256(token.trim());
        Optional<SisPrincipal> person = db.sql("""
                select p.id, p.full_name, p.email, p.student_no, p.nickname, p.system_admin
                from mcp_token t join person p on p.id = t.person_id
                where t.token_hash = :h and p.active and p.deleted_at is null""").param("h", hash)
                .query((rs, i) -> new SisPrincipal(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getBoolean(6)))
                .optional();
        if (person.isPresent()) {
            db.sql("""
                    update mcp_token set last_used_at = now()
                    where token_hash = :h and (last_used_at is null or last_used_at < now() - interval '1 minute')""")
                    .param("h", hash).update();
        }
        return person;
    }
}
