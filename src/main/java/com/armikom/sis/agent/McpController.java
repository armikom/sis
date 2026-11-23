package com.armikom.sis.agent;

import com.armikom.sis.common.ApiException;
import com.armikom.sis.core.auth.CourseAccess;
import com.armikom.sis.core.auth.SisPrincipal;
import com.armikom.sis.pool.PoolAccess;
import com.armikom.sis.pool.PoolChapterController;
import com.armikom.sis.pool.PoolException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP server (Streamable HTTP transport, stateless): every request is one JSON-RPC message sent with POST to /mcp,
 * and the answer is plain JSON. The caller is identified by the personal token in "Authorization: Bearer ...", not
 * by the session cookie; /mcp is outside cookie auth and CSRF. While a message runs, the token's person is the
 * Spring Security principal, so the pool controllers and {@link CourseAccess} apply the same course rules as the
 * REST API. All state (study state, practice answers, diary) is kept in the database per person.
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private static final List<String> VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    static final String RESOURCE_PREFIX = "sis://courses/";

    private static final String STUDENT_INSTRUCTIONS = """
            You are connected to SIS (Student Information System) as the student "%s". You are their study partner: \
            SIS keeps, per course, the chapter texts and their topics, the question pool (students write and answer \
            multiple-choice questions), the published exam results with explanations, and the attendance. Exams are \
            often made of approved questions from the pool. Use list_courses to see the courses; when there is more \
            than one, pass the course code or id as 'course' to the tools. Start with my_overview (exams, \
            attendance, weak topics, pool progress). In a course without the pool, the pool tools say so: study with \
            read_chapter, list_topics, read_topic and my_exam_results instead.

            Rules:
            1. Attempt first. Never tell or hint the correct option of a question before the student chooses. Show \
            the stem and the four options, let the student choose, then call answer_question (new question) or \
            practice_answer (already answered) with the STUDENT'S choice. Never choose for the student. Only the \
            first answer counts and it cannot change.
            2. After the answer, explain with the reasons, the explanation and the chapter text (read_chapter).
            3. Keep the state. Start with get_progress. When you find a weak point, save it with update_study_state. \
            At the end of the session, write log_study_session. The course staff can read the state, notes and diary.
            Topics are the numbered sections of the chapter texts: review_topics lists the weak ones (missed exam \
            questions and wrong first answers), read_topic gives the text of one, and next_question(topic) practises it.
            4. Questions in the pool are written by the students. Give feedback on the student's draft, but do not \
            write the question for them. Ask before submit_question, revise_question, post_comment or flag_question.
            5. Do not solve lab work or homework for the student.
            6. The students may not be native English speakers: use short, simple sentences. If the student writes \
            in Turkish, answer in Turkish.
            """;

    private static final String TEACHER_INSTRUCTIONS = """
            You are connected to SIS (Student Information System) as the staff member "%s". SIS keeps, per course, \
            the roster, the attendance sessions (rotating QR check-in), the question pool (students write \
            multiple-choice questions per chapter, answer each other's questions and study with their own agents) \
            and the exams with their results. Use list_courses to see your courses; when there is more than one, \
            pass the course code or id as 'course' to the tools.

            What you can do: roster (course_roster, add_student, import_students); chapters and topics \
            (create_chapter, update_chapter, set_chapter_open, list_topics, sync_topics, set_question_topics, \
            topic_stats — topics are the numbered '## N. Title' sections of the chapter texts); pool review \
            (class_overview, student_detail, study_overview, list_questions_for_review, decide_question, \
            edit_question, question_stats, list_flags, resolve_flag, hide_comment); exams (list_exams, get_exam, \
            create_exam, create_exam_from_pool, add_exam_question, update_exam_question, set_exam_question_topics, \
            remove_exam_question, exam_results, save_exam_result, exam_analysis, publish_exam); attendance \
            (list_sessions, create_session, open_session, close_session, session_attendances, decide_attendance, \
            add_attendance, attendance_report, student_attendance, session_export).

            Rules:
            1. Show your plan and ask the staff member before any tool that changes something (every tool without \
            readOnlyHint). Be extra careful with update_exam and remove_exam_question: they delete answers.
            2. Notes for students (REVISE, REJECT, exam explanations) are in short, simple English unless the course \
            language is Turkish.
            3. Real names, numbers and e-mail addresses are private. Students only see nicknames; never put real \
            names in comments, explanations or notes that students read.
            4. Reply to the staff member in the language they write in (often Turkish).
            """;

    private final McpTokenService tokens;
    private final JsonMapper json;
    private final JdbcClient db;
    private final PoolChapterController chapters;
    private final McpCourses courses;
    private final CourseAccess access;
    private final PoolAccess pool;
    private final List<McpTool> tools = new ArrayList<>();

    public McpController(McpTokenService tokens, JsonMapper json, JdbcClient db, PoolChapterController chapters,
                         McpCourses courses, CourseAccess access, PoolAccess pool, List<McpToolProvider> providers) {
        this.tokens = tokens;
        this.json = json;
        this.db = db;
        this.chapters = chapters;
        this.courses = courses;
        this.access = access;
        this.pool = pool;
        for (McpToolProvider p : providers) tools.addAll(p.tools());
    }

    /** The server does not open event streams (GET) and keeps no transport sessions (DELETE). */
    @RequestMapping(path = "/mcp", method = {RequestMethod.GET, RequestMethod.DELETE})
    public ResponseEntity<Void> noStream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    @RequestMapping(path = "/mcp", method = RequestMethod.POST)
    public ResponseEntity<Object> handle(HttpServletRequest request, @RequestBody(required = false) String body) {
        Optional<SisPrincipal> person = tokens.personFor(bearer(request));
        if (person.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"sis\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(error(null, -32001, "Missing or wrong MCP token. Create one on the site: AI agent page."));
        }
        JsonNode msg;
        try {
            msg = json.readTree(body == null ? "" : body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body(error(null, -32700, "The body is not valid JSON."));
        }
        if (msg == null || !msg.isObject()) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body(error(null, -32600, "Send one JSON-RPC message as an object."));
        }
        JsonNode id = msg.path("id");
        String method = msg.path("method").asString("");
        // Notifications and answers from the client need no reply.
        if (id.isMissingNode() || id.isNull() || method.isEmpty()) {
            return ResponseEntity.accepted().build();
        }
        Object idValue = id.isIntegralNumber() ? (Object) id.asLong() : id.asString();
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(contextFor(person.get()));
        try {
            Object result = dispatch(person.get(), method, msg.path("params"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("jsonrpc", "2.0");
            out.put("id", idValue);
            out.put("result", result);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(out);
        } catch (RpcError e) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(error(idValue, e.code, e.getMessage()));
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static SecurityContext contextFor(SisPrincipal p) {
        List<GrantedAuthority> authorities = new ArrayList<>(2);
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (p.systemAdmin()) authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(p, null, authorities));
        return ctx;
    }

    private Object dispatch(SisPrincipal person, String method, JsonNode params) {
        return switch (method) {
            case "initialize" -> initialize(person, params);
            case "ping", "logging/setLevel" -> Map.of();
            case "tools/list" -> Map.of("tools", tools.stream().filter(t -> visible(t, person))
                    .map(McpTool::describe).toList());
            case "tools/call" -> callTool(person, params);
            case "resources/list" -> Map.of("resources", resources(person));
            case "resources/templates/list" -> Map.of("resourceTemplates", List.of(Map.of("uriTemplate",
                    RESOURCE_PREFIX + "{course_id}/chapters/{number}/{language}", "name", "chapter", "title",
                    "Chapter text", "description",
                    "Reference text of a chapter. course_id: see list_courses, number: chapter number, "
                            + "language: en or tr.", "mimeType", "text/markdown")));
            case "resources/read" -> readResource(params.path("uri").asString(""));
            case "prompts/list" -> Map.of("prompts",
                    McpPrompts.list(courses.hasStudentCourse(person), courses.hasStaffCourse(person)));
            case "prompts/get" -> McpPrompts.get(courses.hasStudentCourse(person), courses.hasStaffCourse(person),
                    params.path("name").asString(""), params.path("arguments"));
            default -> throw new RpcError(-32601, "Unknown method: " + method);
        };
    }

    /** ALL tools for everyone; student tools with a student enrollment; teacher tools for staff (or admins). */
    private boolean visible(McpTool tool, SisPrincipal person) {
        return switch (tool.audience()) {
            case ALL -> true;
            case STUDENT -> courses.hasStudentCourse(person);
            case INSTRUCTOR -> courses.hasStaffCourse(person);
        };
    }

    private Map<String, Object> initialize(SisPrincipal person, JsonNode params) {
        String asked = params.path("protocolVersion").asString("");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", VERSIONS.contains(asked) ? asked : VERSIONS.get(0));
        out.put("capabilities", Map.of("tools", Map.of("listChanged", false), "resources",
                Map.of("listChanged", false, "subscribe", false), "prompts", Map.of("listChanged", false), "logging",
                Map.of()));
        out.put("serverInfo", Map.of("name", "sis", "title", "SIS Question Pool", "version", "1.0.0"));
        boolean staff = courses.hasStaffCourse(person);
        boolean student = courses.hasStudentCourse(person);
        StringBuilder instructions = new StringBuilder();
        if (staff) {
            instructions.append(TEACHER_INSTRUCTIONS.formatted(person.fullName()));
        }
        if (student || !staff) {
            if (!instructions.isEmpty()) {
                instructions.append("\nIn the courses where this person is a student:\n");
            }
            instructions.append(STUDENT_INSTRUCTIONS.formatted(person.nickname()));
        }
        out.put("instructions", instructions.toString());
        return out;
    }

    private Map<String, Object> callTool(SisPrincipal person, JsonNode params) {
        String name = params.path("name").asString("");
        McpTool tool = tools.stream().filter(t -> t.name().equals(name) && visible(t, person)).findFirst()
                .orElseThrow(() -> new RpcError(-32602, "Unknown tool: " + name));
        JsonNode args = params.path("arguments");
        Object result;
        try {
            result = tool.handler().call(new McpCall(person,
                    new McpTool.Args(args.isObject() ? args : json.createObjectNode()), tool, courses, access, pool,
                    db));
        } catch (PoolException e) {
            return toolError(e.getMessage());
        } catch (ApiException e) {
            return toolError(e.code().name() + ": " + e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return toolError(e.getMessage());
        } catch (Exception e) {
            log.error("MCP tool {} failed for person {}", name, person.personId(), e);
            return toolError("The tool failed on the server. Try again, or tell the course staff.");
        }
        log.info("MCP tool {} by person {}", name, person.personId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of(Map.of("type", "text", "text", json.writeValueAsString(result))));
        out.put("isError", false);
        return out;
    }

    private static Map<String, Object> toolError(String message) {
        return Map.of("content", List.of(Map.of("type", "text", "text", message == null ? "Error." : message)),
                "isError", true);
    }

    private List<Map<String, Object>> resources(SisPrincipal person) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpCourses.Ref course : courses.mine(person)) {
            for (Map<String, Object> c : db.sql("select number, title from chapter where course_id = :c order by number")
                    .param("c", course.id()).query().listOfRows()) {
                for (String lang : List.of("en", "tr")) {
                    out.add(Map.of("uri", RESOURCE_PREFIX + course.id() + "/chapters/" + c.get("number") + "/" + lang,
                            "name", course.code() + "-chapter" + c.get("number") + "." + lang,
                            "title", course.code() + ": " + c.get("title") + " (" + lang.toUpperCase() + ")",
                            "mimeType", "text/markdown"));
                }
            }
        }
        return out;
    }

    private Map<String, Object> readResource(String uri) {
        String[] parts = uri.startsWith(RESOURCE_PREFIX) ? uri.substring(RESOURCE_PREFIX.length()).split("/")
                : new String[0];
        if (parts.length != 4 || !parts[0].matches("\\d+") || !"chapters".equals(parts[1])
                || !parts[2].matches("\\d+") || !List.of("en", "tr").contains(parts[3])) {
            throw new RpcError(-32002, "Resource not found: " + uri);
        }
        long courseId = Long.parseLong(parts[0]);
        Long chapterId = db.sql("select id from chapter where course_id = :c and number = :n")
                .param("c", courseId).param("n", Integer.parseInt(parts[2])).query(Long.class).optional()
                .orElseThrow(() -> new RpcError(-32002, "Resource not found: " + uri));
        Object text;
        try {
            text = chapters.get(chapterId).get("doc_" + parts[3]);
        } catch (PoolException | ApiException e) {
            throw new RpcError(-32002, "Resource not found: " + uri);
        }
        return Map.of("contents", List.of(Map.of("uri", uri, "mimeType", "text/markdown", "text", text)));
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("error", Map.of("code", code, "message", message));
        return out;
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return null;
    }

    static class RpcError extends RuntimeException {

        final int code;

        RpcError(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
