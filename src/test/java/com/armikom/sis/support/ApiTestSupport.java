package com.armikom.sis.support;

import com.armikom.sis.core.auth.UserSessionService;
import com.armikom.sis.core.course.CourseRepository;
import com.armikom.sis.core.course.Enrollment;
import com.armikom.sis.core.course.EnrollmentRepository;
import com.armikom.sis.core.course.EnrollmentRole;
import com.armikom.sis.core.person.Person;
import com.armikom.sis.core.person.PersonRepository;
import com.armikom.sis.core.person.PersonService;
import com.armikom.sis.security.RateLimitFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared helpers for the integration tests that need real people with real {@code sis_session} cookies:
 * staff owners, roster students, courses, pool chapters and questions. The concrete test class carries the
 * {@code @SpringBootTest} annotations; everything here talks to the application through MockMvc.
 */
public abstract class ApiTestSupport {

    /** Unique numbers for course codes, student numbers and e-mail addresses (one JVM, one database). */
    protected static final AtomicInteger SEQ = new AtomicInteger(100_000);

    @Autowired protected MockMvc mvc;
    @Autowired protected JsonMapper json;
    @Autowired protected TestPeople people;
    @Autowired protected RateLimitFilter rateLimit;
    @Autowired protected PersonRepository persons;
    @Autowired protected PersonService personService;
    @Autowired protected UserSessionService sessions;
    @Autowired protected CourseRepository courseRepo;
    @Autowired protected EnrollmentRepository enrollments;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected TransactionTemplate tx;

    @BeforeEach
    void clearRateLimits() {
        rateLimit.clear();
    }

    // ---- people ---------------------------------------------------------------------------------

    /** A signed-in person: the id, names and a session cookie. */
    public record User(long id, String name, String studentNo, String nickname, Cookie cookie) {
        public RequestPostProcessor as() {
            return request -> {
                List<Cookie> all = new ArrayList<>();
                if (request.getCookies() != null) all.addAll(Arrays.asList(request.getCookies()));
                all.add(cookie);
                request.setCookies(all.toArray(new Cookie[0]));
                return request;
            };
        }
    }

    public record CourseRef(long id, String code) {}

    protected static int next() {
        return SEQ.incrementAndGet();
    }

    protected Cookie sessionCookieOf(long personId) {
        MockHttpServletResponse res = new MockHttpServletResponse();
        sessions.start(personId, res);
        return res.getCookie(sessions.cookieName());
    }

    protected User userOf(Person p) {
        return new User(p.getId(), p.getFullName(), p.getStudentNo(), p.getNickname(), sessionCookieOf(p.getId()));
    }

    /** A staff person (password login "parola123") who owns a private seed course, so they may create courses. */
    protected User newStaff() {
        String username = "tst" + next();
        long id = people.staff(username);
        Person p = persons.findById(id).orElseThrow();
        return new User(id, p.getFullName(), null, p.getNickname(), people.cookie(username));
    }

    /** A system admin with a session. */
    protected User newAdmin() {
        Person p = tx.execute(st -> {
            Person a = new Person("Yönetici " + next(), personService.newStaffNickname());
            a.setSystemAdmin(true);
            return persons.save(a);
        });
        return userOf(p);
    }

    /** A student on the roster of the course, with a session. */
    protected User newStudent(CourseRef course) {
        String no = "77" + next();
        people.enroll(course.id(), no, "Öğrenci " + no);
        return userOf(persons.findByStudentNo(no).orElseThrow());
    }

    /** Puts an existing student on the roster of one more course. */
    protected void enroll(CourseRef course, User student) {
        people.enroll(course.id(), student.studentNo(), student.name());
    }

    /** A live enrollment with any role for an existing person (e.g. staff in one course, student in another). */
    protected void addEnrollment(CourseRef course, long personId, EnrollmentRole role) {
        tx.executeWithoutResult(st -> enrollments.save(new Enrollment(courseRepo.getReferenceById(course.id()),
                persons.getReferenceById(personId), role)));
    }

    protected CourseRef newCourse(User owner, boolean pool, int questionsPerChapter) throws Exception {
        String code = "T" + next();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("code", code);
        req.put("name", "Test Dersi");
        req.put("poolEnabled", pool);
        req.put("questionsPerChapter", questionsPerChapter);
        req.put("askAiPerDay", 3);
        long id = body(doPost(owner, "/api/courses", req).andExpect(status().isCreated())).get("id").asLong();
        return new CourseRef(id, code);
    }

    // ---- HTTP -----------------------------------------------------------------------------------

    protected String toJson(Object o) {
        return json.writeValueAsString(o);
    }

    protected JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected String text(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private ResultActions send(MockHttpServletRequestBuilder b, User u, Object body) throws Exception {
        b.with(csrf());
        if (u != null) b.with(u.as());
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        return mvc.perform(b);
    }

    /** {@code u == null}: anonymous. */
    protected ResultActions doGet(User u, String url) throws Exception {
        MockHttpServletRequestBuilder b = get(url);
        if (u != null) b.with(u.as());
        return mvc.perform(b);
    }

    protected ResultActions doPost(User u, String url, Object body) throws Exception {
        return send(post(url), u, body);
    }

    protected ResultActions doPut(User u, String url, Object body) throws Exception {
        return send(put(url), u, body);
    }

    protected ResultActions doDelete(User u, String url) throws Exception {
        return send(delete(url), u, null);
    }

    // ---- question pool --------------------------------------------------------------------------

    protected long chapter(User staff, CourseRef course, int number, String title) throws Exception {
        return body(doPost(staff, "/api/pool/courses/" + course.id() + "/staff/chapters",
                Map.of("number", number, "title", title)).andExpect(status().isOk())).get("id").asLong();
    }

    /** A valid question body (snake_case, as the pool API wants it). */
    protected static Map<String, Object> questionBody(long chapterId, String correct) {
        int n = next();
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("chapter_id", chapterId);
        q.put("stem", "Question " + n + ": which of the following statements is the correct one?");
        for (String l : List.of("a", "b", "c", "d")) {
            q.put("option_" + l, "Option " + l.toUpperCase(java.util.Locale.ROOT) + " of question " + n);
            q.put("rationale_" + l, "Reason for option " + l.toUpperCase(java.util.Locale.ROOT) + " of question " + n + ".");
        }
        q.put("correct", correct);
        q.put("reference", "Chapter notes, section " + n);
        return q;
    }

    protected long submitQuestion(User author, long chapterId, String correct) throws Exception {
        return body(doPost(author, "/api/pool/questions", questionBody(chapterId, correct))
                .andExpect(status().isOk())).get("id").asLong();
    }

    protected void decide(User staff, CourseRef course, long questionId, String action, String note) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("action", action);
        req.put("note", note);
        doPost(staff, "/api/pool/courses/" + course.id() + "/staff/questions/" + questionId + "/decision", req)
                .andExpect(status().isOk());
    }

    protected long approvedQuestion(User author, User staff, CourseRef course, long chapterId, String correct)
            throws Exception {
        long id = submitQuestion(author, chapterId, correct);
        decide(staff, course, id, "APPROVE", null);
        return id;
    }

    protected JsonNode getQuestion(User viewer, long questionId) throws Exception {
        return body(doGet(viewer, "/api/pool/questions/" + questionId).andExpect(status().isOk()));
    }

    protected JsonNode attempt(User student, long questionId, String selected) throws Exception {
        return body(doPost(student, "/api/pool/questions/" + questionId + "/attempt", Map.of("selected", selected))
                .andExpect(status().isOk()));
    }

    protected long count(String sql, Object... params) {
        var spec = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) spec = spec.param(params[i]);
        return spec.query(Long.class).single();
    }
}
