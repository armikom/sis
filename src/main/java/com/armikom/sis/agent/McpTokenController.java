package com.armikom.sis.agent;

import com.armikom.sis.common.PublicBaseUrl;
import com.armikom.sis.core.auth.CourseAccess;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** The "AI agent" screen: each signed-in person manages their own MCP token here. */
@RestController
@RequestMapping("/api/mcp-token")
public class McpTokenController {

    private final JdbcClient db;
    private final McpTokenService tokens;
    private final CourseAccess access;
    private final PublicBaseUrl baseUrl;

    public McpTokenController(JdbcClient db, McpTokenService tokens, CourseAccess access, PublicBaseUrl baseUrl) {
        this.db = db;
        this.tokens = tokens;
        this.access = access;
        this.baseUrl = baseUrl;
    }

    @GetMapping
    public Map<String, Object> info() {
        long me = access.currentPerson().personId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endpoint", baseUrl.current() + "/mcp");
        db.sql("select hint, created_at, last_used_at from mcp_token where person_id = :u").param("u", me)
                .query().listOfRows().stream().findFirst().ifPresentOrElse(t -> {
                    out.put("has_token", true);
                    out.putAll(t);
                }, () -> out.put("has_token", false));
        return out;
    }

    /** Creates a new token (the old one stops working). The token is in this answer only. */
    @PostMapping
    public Map<String, Object> create() {
        String token = tokens.newToken(access.currentPerson().personId());
        Map<String, Object> out = info();
        out.put("token", token);
        return out;
    }

    @DeleteMapping
    public Map<String, Object> delete() {
        tokens.delete(access.currentPerson().personId());
        return Map.of("ok", true);
    }
}
