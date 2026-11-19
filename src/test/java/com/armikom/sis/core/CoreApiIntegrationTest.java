package com.armikom.sis.core;

import com.armikom.sis.core.mail.MailService;
import com.armikom.sis.core.person.Person;
import com.armikom.sis.crypto.Base32;
import com.armikom.sis.support.ApiTestSupport;
import com.armikom.sis.support.MutableClock;
import com.armikom.sis.support.PostgresTestcontainer;
import com.armikom.sis.support.TestClockConfig;
import com.armikom.sis.support.TestPeople;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sign-in, sessions, staff and roster management, people admin, student self-service and roster-only check-in. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, PostgresTestcontainer.class, TestPeople.Config.class})
class CoreApiIntegrationTest extends ApiTestSupport {

    /** The mail seam: mails are not sent, the login link is read from the call. */
    @MockitoBean MailService mail;
    @Autowired MutableClock clock;

    private Instant clockAtStart;

    @BeforeEach
    void rememberClock() {
        clockAtStart = clock.instant();
    }

    @AfterEach
    void restoreClock() {
        clock.set(clockAtStart);
    }

    // ---- helpers --------------------------------------------------------------------------------

    static String email() {
        return "kisi" + next() + "@example.com";
    }

    /** Mailed login links for an address, oldest first. */
    List<String> links(String to) {
        List<String> out = new ArrayList<>();
        Mockito.mockingDetails(mail).getInvocations().forEach(inv -> {
            if (inv.getMethod().getName().equals("sendAsync") && to.equals(inv.getArgument(0))) {
                out.add(inv.getArgument(3));
            }
        });
        return out;
    }

    static String tokenOf(String link) {
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    ResultActions requestLink(String address) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("email", address);
        return doPost(null, "/api/auth/request", req);
    }

    ResultActions verify(String token) throws Exception {
        return doPost(null, "/api/auth/verify", Map.of("token", token));
    }

    /** A roster student with an e-mail address (added by the owner through the API). */
    User rosterStudent(User owner, CourseRef course, String address) throws Exception {
        String no = "88" + next();
        doPost(owner, "/api/courses/" + course.id() + "/roster",
                Map.of("studentNo", no, "fullName", "Öğrenci " + no, "email", address)).andExpect(status().isCreated());
        return userOf(persons.findByStudentNo(no).orElseThrow());
    }

    Cookie sessionOf(ResultActions r) {
        return r.andReturn().getResponse().getCookie(sessions.cookieName());
    }

    String sessionCookieHeader(ResultActions r) {
        return r.andReturn().getResponse().getHeader("Set-Cookie");
    }

    ResultActions me(Cookie cookie) throws Exception {
        var b = get("/api/auth/me");
        if (cookie != null) b.cookie(cookie);
        return mvc.perform(b);
    }

    // ---- magic link -----------------------------------------------------------------------------

    @Test
    void magicLinkSignsInOnce() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, false, 2);
        String address = email();
        User student = rosterStudent(owner, course, address);

        requestLink(address).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.onRoster").value(true));
        List<String> sent = links(address);
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("/login/verify?token="), sent.get(0));
        // Only a hash is stored.
        String token = tokenOf(sent.get(0));
        assertEquals(0, count("select count(*) from login_token where token_hash = ?", token));
        assertEquals(1, count("select count(*) from login_token where person_id = ? and used_at is null",
                student.id()));

        ResultActions ok = verify(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(student.id()))
                .andExpect(jsonPath("$.email").value(address))
                .andExpect(jsonPath("$.staff").value(false))
                .andExpect(jsonPath("$.systemAdmin").value(false))
                .andExpect(jsonPath("$.courses[0].code").value(course.code()))
                .andExpect(jsonPath("$.courses[0].role").value("STUDENT"))
                .andExpect(header().string("Set-Cookie", containsString("sis_session=")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")));
        assertNotNull(sessionOf(ok));
        assertEquals(1, count("select count(*) from login_token where person_id = ? and used_at is not null",
                student.id()));
        assertFalse(sessionCookieHeader(ok).contains(token), "the link token is not the session");

        // Single use.
        verify(token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LINK_INVALID"));
        // A new request gives a new, different link.
        requestLink(address).andExpect(status().isOk());
        assertEquals(2, links(address).size());
        assertNotEquals(tokenOf(links(address).get(0)), tokenOf(links(address).get(1)));
        // Mail address is matched without regard to case and spaces.
        requestLink("  " + address.toUpperCase(java.util.Locale.ROOT) + " ").andExpect(jsonPath("$.onRoster").value(true));
        assertEquals(3, links(address).size());
    }

    @Test
    void magicLinkExpires() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, false, 2);
        String address = email();
        rosterStudent(owner, course, address);

        requestLink(address).andExpect(status().isOk());
        String late = tokenOf(links(address).get(0));
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        verify(late).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LINK_INVALID"));

        // Just inside the lifetime it still works.
        requestLink(address).andExpect(status().isOk());
        String inTime = tokenOf(links(address).get(1));
        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        verify(inTime).andExpect(status().isOk());
        // Expired rows are removed by the purge.
        assertTrue(sessions.purgeExpired() >= 1);
        assertEquals(0, count("select count(*) from login_token where token_hash = ?",
                com.armikom.sis.core.auth.Tokens.sha256(late)));
    }

    @Test
    void unknownAddressesGetTheSameAnswerAndNoMail() throws Exception {
        String stranger = email();
        requestLink(stranger).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.onRoster").value(false));
        assertTrue(links(stranger).isEmpty());
        // Garbage input answers in the same shape, never an error.
        for (String bad : new String[]{"", "   ", "not-an-email", "a".repeat(300) + "@example.com"}) {
            requestLink(bad).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.onRoster").value(false));
        }
        doPost(null, "/api/auth/request", Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.onRoster").value(false));
        Mockito.verify(mail, Mockito.never()).sendAsync(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        rateLimit.clear();   // /request and /verify share a small per-minute budget
        // Wrong or empty tokens are all "invalid link".
        for (String bad : new String[]{"nope", "", " ", "x".repeat(200)}) {
            verify(bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LINK_INVALID"));
        }
        doPost(null, "/api/auth/verify", Map.of()).andExpect(status().isBadRequest());
        // No session cookie was issued.
        verify("nope").andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void peopleWithoutALiveEnrollmentGetNoLink() throws Exception {
        // Known person, no enrollment at all.
        String loner = email();
        tx.executeWithoutResult(st -> personService.createStudent("Yalnız Kişi", "66" + next(), loner));
        requestLink(loner).andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.onRoster").value(false));
        assertTrue(links(loner).isEmpty());
        assertEquals(0, count("select count(*) from login_token t join person p on p.id = t.person_id "
                + "where p.email = ?", loner));

        rateLimit.clear();
        // Removed from the roster: the link stops, and comes back when they are re-added.
        User owner = newStaff();
        CourseRef course = newCourse(owner, false, 2);
        String address = email();
        User student = rosterStudent(owner, course, address);
        requestLink(address).andExpect(jsonPath("$.onRoster").value(true));
        assertEquals(1, links(address).size());
        doDelete(owner, "/api/courses/" + course.id() + "/roster/" + student.id()).andExpect(status().isNoContent());
        requestLink(address).andExpect(jsonPath("$.onRoster").value(false));
        assertEquals(1, links(address).size(), "no second link");
        doPost(owner, "/api/courses/" + course.id() + "/roster",
                Map.of("studentNo", student.studentNo(), "fullName", student.name(), "email", address))
                .andExpect(status().isCreated());
        requestLink(address).andExpect(jsonPath("$.onRoster").value(true));
        assertEquals(2, links(address).size());

        // A deactivated person gets none, and an issued link no longer signs in.
        String token = tokenOf(links(address).get(1));
        jdbc.sql("update person set active = false where id = ?").param(student.id()).update();
        try {
            requestLink(address).andExpect(jsonPath("$.onRoster").value(false));
            assertEquals(2, links(address).size());
            verify(token).andExpect(status().isBadRequest());
        } finally {
            jdbc.sql("update person set active = true where id = ?").param(student.id()).update();
        }

        // A system admin needs no enrollment.
        String adminMail = email();
        User admin = newAdmin();
        jdbc.sql("update person set email = ? where id = ?").param(adminMail).param(admin.id()).update();
        requestLink(adminMail).andExpect(jsonPath("$.onRoster").value(true));
        verify(tokenOf(links(adminMail).get(0))).andExpect(status().isOk()).andExpect(jsonPath("$.systemAdmin").value(true))
                .andExpect(jsonPath("$.staff").value(true));
    }

    @Test
    void atMostThreeLinksPerFifteenMinutes() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, false, 2);
        String address = email();
        rosterStudent(owner, course, address);
        for (int i = 0; i < 5; i++) {
            // Rate-limited requests answer exactly like the others.
            requestLink(address).andExpect(status().isOk()).andExpect(jsonPath("$.onRoster").value(true));
        }
        assertEquals(3, links(address).size());
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        requestLink(address).andExpect(status().isOk());
        assertEquals(4, links(address).size());
    }

    // ---- sessions -------------------------------------------------------------------------------

    @Test
    void sessionSurvivesAcrossRequestsAndLogoutEndsIt() throws Exception {
        User owner = newStaff();
        String username = persons.findById(owner.id()).orElseThrow().getUsername();

        // Password login: wrong password and unknown user are 401, no cookie.
        doPost(null, "/api/auth/login", Map.of("username", username, "password", "wrong-password"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
        doPost(null, "/api/auth/login", Map.of("username", "nobody-" + next(), "password", "parola123"))
                .andExpect(status().isUnauthorized());
        ResultActions login = doPost(null, "/api/auth/login", Map.of("username", username, "password", "parola123"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(owner.id()))
                .andExpect(jsonPath("$.staff").value(true));
        Cookie cookie = sessionOf(login);
        assertNotNull(cookie);
        assertTrue(cookie.isHttpOnly());

        // Anonymous is 401, the cookie works on every new request.
        me(null).andExpect(status().isUnauthorized());
        me(cookie).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(owner.id()));
        me(cookie).andExpect(status().isOk());
        mvc.perform(get("/api/courses").cookie(cookie)).andExpect(status().isOk());
        // Only the hash of the cookie is stored.
        assertEquals(0, count("select count(*) from user_session where token_hash = ?", cookie.getValue()));

        // A second device has its own session; logging out one leaves the other.
        Cookie second = sessionOf(doPost(null, "/api/auth/login", Map.of("username", username,
                "password", "parola123")).andExpect(status().isOk()));
        assertNotEquals(cookie.getValue(), second.getValue());

        mvc.perform(post("/api/auth/logout").cookie(cookie).with(csrf())).andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("sis_session=;")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        me(cookie).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/courses").cookie(cookie)).andExpect(status().isUnauthorized());
        me(second).andExpect(status().isOk());
        // Logging out again (expired session) is harmless.
        mvc.perform(post("/api/auth/logout").cookie(cookie).with(csrf())).andExpect(status().isNoContent());
        // A forged cookie is not a session.
        me(new Cookie(sessions.cookieName(), "forged-value")).andExpect(status().isUnauthorized());

        // Sessions last 30 days.
        clock.advance(Duration.ofDays(29));
        me(second).andExpect(status().isOk());
        clock.advance(Duration.ofDays(2));
        me(second).andExpect(status().isUnauthorized());
    }

    // ---- course staff ---------------------------------------------------------------------------

    @Test
    void ownerManagesStaffAndAssistantsCannot() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, false, 2);
        String staffUrl = "/api/courses/" + course.id() + "/staff";
        String assistantMail = email();

        doPost(owner, staffUrl, Map.of("email", assistantMail, "fullName", "Asistan Kişi", "role", "ASSISTANT"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("ASSISTANT"))
                .andExpect(jsonPath("$.email").value(assistantMail))
                .andExpect(jsonPath("$.nickname").value(startsWith("Instructor")));
        Person assistantPerson = persons.findByEmail(assistantMail).orElseThrow();
        User assistant = userOf(assistantPerson);

        JsonNode staff = body(doGet(owner, staffUrl).andExpect(status().isOk()));
        assertEquals(2, staff.size());
        Set<String> roles = new HashSet<>();
        staff.forEach(s -> roles.add(s.get("role").asString()));
        assertEquals(Set.of("OWNER", "ASSISTANT"), roles);

        // The assistant is staff: reads staff and roster, changes course settings; but cannot manage staff or archive.
        doGet(assistant, staffUrl).andExpect(status().isOk());
        doGet(assistant, "/api/courses/" + course.id() + "/roster").andExpect(status().isOk());
        doPut(assistant, "/api/courses/" + course.id(), Map.of("name", "Yeni Ad")).andExpect(status().isOk());
        doPost(assistant, staffUrl, Map.of("email", email(), "fullName", "Biri", "role", "INSTRUCTOR"))
                .andExpect(status().isForbidden());
        doDelete(assistant, staffUrl + "/" + owner.id()).andExpect(status().isForbidden());
        doDelete(assistant, staffUrl + "/" + assistant.id()).andExpect(status().isForbidden());
        doPost(assistant, "/api/courses/" + course.id() + "/archive", null).andExpect(status().isForbidden());
        // Students and strangers see no staff list at all.
        User student = newStudent(course);
        doGet(student, staffUrl).andExpect(status().isForbidden());
        doPost(student, staffUrl, Map.of("email", email(), "fullName", "Biri")).andExpect(status().isForbidden());
        doGet(newStaff(), staffUrl).andExpect(status().isForbidden());

        // Validation.
        doPost(owner, staffUrl, Map.of("email", "bad", "fullName", "X")).andExpect(status().isBadRequest());
        doPost(owner, staffUrl, Map.of("email", email(), "fullName", "X", "role", "STUDENT"))
                .andExpect(status().isBadRequest());
        doPost(owner, staffUrl, Map.of("email", email(), "role", "INSTRUCTOR")).andExpect(status().isBadRequest());

        // The owner cannot remove himself; removing someone else works and ends their access.
        doDelete(owner, staffUrl + "/" + owner.id()).andExpect(status().isConflict());
        doDelete(owner, staffUrl + "/" + assistant.id()).andExpect(status().isNoContent());
        doGet(assistant, staffUrl).andExpect(status().isForbidden());
        doGet(assistant, "/api/courses/" + course.id()).andExpect(status().isForbidden());
        assertEquals(1, body(doGet(owner, staffUrl)).size());
        doDelete(owner, staffUrl + "/" + assistant.id()).andExpect(status().isNotFound());
        doDelete(owner, staffUrl + "/" + student.id()).andExpect(status().isNotFound());   // students are not staff

        // Adding again brings the person back (same enrollment row).
        doPost(owner, staffUrl, Map.of("email", assistantMail, "role", "INSTRUCTOR")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("INSTRUCTOR"));
        assertEquals(1, count("select count(*) from enrollment where course_id = ? and person_id = ?",
                course.id(), assistant.id()));
        doGet(assistant, staffUrl).andExpect(status().isOk());
        // An instructor is not an owner either.
        doPost(assistant, staffUrl, Map.of("email", email(), "fullName", "Biri")).andExpect(status().isForbidden());
    }

    @Test
    void aCourseKeepsAtLeastOneOwner() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, false, 2);
        String staffUrl = "/api/courses/" + course.id() + "/staff";
        String ownerMail = email();
        jdbc.sql("update person set email = ? where id = ?").param(ownerMail).param(owner.id()).update();

        // The only owner cannot demote himself.
        doPost(owner, staffUrl, Map.of("email", ownerMail, "role", "INSTRUCTOR")).andExpect(status().isConflict());
        assertEquals("OWNER", body(doGet(owner, staffUrl)).get(0).get("role").asString());

        // With a second owner the first may be removed by the second, and nobody can remove himself.
        String secondMail = email();
        doPost(owner, staffUrl, Map.of("email", secondMail, "fullName", "İkinci Sahip", "role", "OWNER"))
                .andExpect(status().isCreated());
        User second = userOf(persons.findByEmail(secondMail).orElseThrow());
        doDelete(second, staffUrl + "/" + second.id()).andExpect(status().isConflict());
        doDelete(second, staffUrl + "/" + owner.id()).andExpect(status().isNoContent());
        doGet(owner, "/api/courses/" + course.id()).andExpect(status().isForbidden());
        doGet(second, staffUrl).andExpect(status().isOk());

        // A system admin may manage the staff of any course without being enrolled.
        User admin = newAdmin();
        doPost(admin, staffUrl, Map.of("email", ownerMail, "role", "ASSISTANT")).andExpect(status().isCreated());
        doGet(admin, staffUrl).andExpect(status().isOk());
        doDelete(admin, staffUrl + "/" + owner.id()).andExpect(status().isNoContent());
    }

    @Test
    void promotingAStudentMovesThemOffTheRoster() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, false, 2);
        String address = email();
        User student = rosterStudent(owner, course, address);
        assertEquals(1, body(doGet(owner, "/api/courses/" + course.id() + "/roster")).size());
        doPost(owner, "/api/courses/" + course.id() + "/staff", Map.of("email", address, "role", "ASSISTANT"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.personId").value(student.id()));
        assertEquals(0, body(doGet(owner, "/api/courses/" + course.id() + "/roster")).size());
        assertEquals(1, count("select count(*) from enrollment where course_id = ? and person_id = ?",
                course.id(), student.id()));
    }

    // ---- roster import --------------------------------------------------------------------------

    @Test
    void rosterImportMergeAndReplaceWithBothLineFormats() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, false, 2);
        String url = "/api/courses/" + course.id() + "/roster";
        String n1 = "55" + next(), n2 = "55" + next(), n3 = "55" + next(), n4 = "55" + next(), n5 = "55" + next();
        String m1 = email(), m3 = email();

        // yoklama format (number first, header line) mixed with question-pool format (e-mail first).
        String first = "OgrenciNo,AdSoyad,Eposta\n"
                + n1 + ", Ali Veli, " + m1.toUpperCase(java.util.Locale.ROOT) + "\n"
                + n2 + ",Ayşe Yılmaz\n"
                + m3 + ", Can Kaya, " + n3 + "\n";
        doPost(owner, url + "/import", Map.of("text", first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(3)).andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.updated").value(0)).andExpect(jsonPath("$.removed").value(0))
                .andExpect(jsonPath("$.reactivated").value(0)).andExpect(jsonPath("$.errors.length()").value(0));
        JsonNode roster = body(doGet(owner, url).andExpect(status().isOk()));
        assertEquals(Set.of(n1, n2, n3), numbers(roster));
        assertEquals(m1, byNumber(roster, n1).get("email").asString(), "e-mail is normalised");
        assertEquals("Can Kaya", byNumber(roster, n3).get("fullName").asString());
        assertEquals(m3, byNumber(roster, n3).get("email").asString());
        assertTrue(byNumber(roster, n2).get("email").isNull());
        // Every imported person is a student with a nickname, not staff.
        assertEquals(0, count("select count(*) from person where student_no in (?, ?, ?) and nickname like 'Instructor%'",
                n1, n2, n3));

        // MERGE (the default when no mode is given) adds and updates, never removes.
        doPost(owner, url + "/import", Map.of("text", n4 + "; Deniz Aksoy\n" + n1 + "; Ali V. Veli\n"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.created").value(1)).andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.removed").value(0));
        roster = body(doGet(owner, url));
        assertEquals(Set.of(n1, n2, n3, n4), numbers(roster));
        assertEquals("Ali V. Veli", byNumber(roster, n1).get("fullName").asString());
        assertEquals(m1, byNumber(roster, n1).get("email").asString(), "e-mail kept when the line has none");
        // Explicit MERGE is the same.
        doPost(owner, url + "/import", Map.of("text", n2 + "\tAyşe Yılmaz\n", "mode", "merge")).andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(0));

        // Bad lines are reported with their line number; the good ones still go in; duplicates count as skipped.
        String messy = "No,Ad\n"
                + "abc, Not A Number\n"
                + n5 + "\n"
                + n5 + ", Gül Demir, not-an-email\n"
                + n5 + ", Gül Demir\n"
                + n5 + ", Gül Demir Kaya\n";
        JsonNode res = body(doPost(owner, url + "/import", Map.of("text", messy)).andExpect(status().isOk()));
        assertEquals(1, res.get("imported").asInt());
        assertEquals(1, res.get("skipped").asInt());
        assertEquals(3, res.get("errors").size(), res.toString());
        assertEquals(2, res.get("errors").get(0).get("line").asInt());
        assertEquals(3, res.get("errors").get(1).get("line").asInt());
        assertEquals(4, res.get("errors").get(2).get("line").asInt());
        assertEquals("Gül Demir Kaya", byNumber(body(doGet(owner, url)), n5).get("fullName").asString());
        // An e-mail that belongs to somebody else is a line error, not a failure of the whole import.
        String stolen = "55" + next();
        JsonNode conflict = body(doPost(owner, url + "/import", Map.of("text", stolen + ", Çakışan Kişi, " + m3 + "\n"))
                .andExpect(status().isOk()));
        assertEquals(0, conflict.get("imported").asInt());
        assertEquals(1, conflict.get("errors").size());
        assertFalse(numbers(body(doGet(owner, url))).contains(stolen));

        // REPLACE: whoever is not in the list is removed (soft), the rest stays.
        JsonNode replaced = body(doPost(owner, url + "/import", Map.of("text", n2 + ", Ayşe Yılmaz\n" + n5 + ", Gül Demir\n",
                "mode", "REPLACE")).andExpect(status().isOk()));
        assertEquals(2, replaced.get("imported").asInt());
        assertEquals(3, replaced.get("removed").asInt(), replaced.toString());
        assertEquals(Set.of(n2, n5), numbers(body(doGet(owner, url))));
        long removedPerson = persons.findByStudentNo(n1).orElseThrow().getId();
        assertEquals(1, count("select count(*) from enrollment where course_id = ? and person_id = ? "
                + "and deleted_at is not null", course.id(), removedPerson));
        assertEquals(1, count("select count(*) from person where id = ? and deleted_at is null and active", removedPerson));

        // A soft-removed student is re-activated by a later import: same enrollment row, counted as reactivated.
        long rowsBefore = count("select count(*) from enrollment where course_id = ? and person_id = ?",
                course.id(), removedPerson);
        JsonNode back = body(doPost(owner, url + "/import", Map.of("text", n1 + ", Ali Veli\n")).andExpect(status().isOk()));
        assertEquals(1, back.get("reactivated").asInt(), back.toString());
        assertEquals(1, back.get("imported").asInt());
        assertEquals(Set.of(n1, n2, n5), numbers(body(doGet(owner, url))));
        assertEquals(rowsBefore, count("select count(*) from enrollment where course_id = ? and person_id = ?",
                course.id(), removedPerson));
        assertEquals(1, count("select count(*) from enrollment where course_id = ? and person_id = ? "
                + "and deleted_at is null and role = 'STUDENT'", course.id(), removedPerson));

        // The same through the single add and DELETE endpoints.
        doDelete(owner, url + "/" + removedPerson).andExpect(status().isNoContent());
        assertFalse(numbers(body(doGet(owner, url))).contains(n1));
        doPost(owner, url, Map.of("studentNo", n1, "fullName", "Ali Veli")).andExpect(status().isCreated());
        assertTrue(numbers(body(doGet(owner, url))).contains(n1));
        doPost(owner, url, Map.of("studentNo", n1, "fullName", "Ali Veli")).andExpect(status().isConflict());
        doPost(owner, url, Map.of("studentNo", "12", "fullName", "Kısa")).andExpect(status().isBadRequest());

        // Bad mode.
        doPost(owner, url + "/import", Map.of("text", n1 + ", Ali Veli", "mode", "WIPE")).andExpect(status().isBadRequest());
    }

    @Test
    void rosterImportFromAFileAndAccessRules() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, false, 2);
        String url = "/api/courses/" + course.id() + "/roster/import";
        String n1 = "55" + next(), n2 = "55" + next(), n3 = "55" + next();
        byte[] withBom = ("﻿numara;ad;eposta\n" + n1 + ";\"Yılmaz, Ayşe\";" + email() + "\n" + n2 + ";Ömer Çelik\n")
                .getBytes(StandardCharsets.UTF_8);

        mvc.perform(multipart(url).file(new MockMultipartFile("file", "liste.csv", "text/csv", withBom))
                        .with(owner.as()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.created").value(2));
        JsonNode roster = body(doGet(owner, "/api/courses/" + course.id() + "/roster"));
        assertEquals("Yılmaz, Ayşe", byNumber(roster, n1).get("fullName").asString());
        assertEquals("Ömer Çelik", byNumber(roster, n2).get("fullName").asString());

        mvc.perform(multipart(url).file(new MockMultipartFile("file", "liste.csv", "text/csv",
                        (n3 + ", Yeni Kişi\n").getBytes(StandardCharsets.UTF_8)))
                        .param("mode", "REPLACE").with(owner.as()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.removed").value(2));
        assertEquals(Set.of(n3), numbers(body(doGet(owner, "/api/courses/" + course.id() + "/roster"))));

        // Only staff of the course may import or read the roster.
        User student = newStudent(course);
        doPost(student, url, Map.of("text", n1 + ", X")).andExpect(status().isForbidden());
        doGet(student, "/api/courses/" + course.id() + "/roster").andExpect(status().isForbidden());
        doPost(newStaff(), url, Map.of("text", n1 + ", X")).andExpect(status().isForbidden());
        doPost(null, url, Map.of("text", n1 + ", X")).andExpect(status().isUnauthorized());
        assertEquals(Set.of(n3, student.studentNo()), numbers(body(doGet(owner, "/api/courses/" + course.id() + "/roster"))));
    }

    static Set<String> numbers(JsonNode roster) {
        Set<String> s = new HashSet<>();
        roster.forEach(r -> s.add(r.get("studentNo").asString()));
        return s;
    }

    static JsonNode byNumber(JsonNode roster, String no) {
        for (JsonNode r : roster) if (no.equals(r.get("studentNo").asString())) return r;
        return fail("no roster entry " + no + " in " + roster);
    }

    // ---- people admin ---------------------------------------------------------------------------

    @Test
    void adminPersonsApiNeedsASystemAdmin() throws Exception {
        User admin = newAdmin();
        User staff = newStaff();
        CourseRef course = newCourse(staff, false, 2);
        User student = newStudent(course);
        String someone = "/api/admin/persons/" + student.id();

        for (User u : new User[]{staff, student}) {
            doGet(u, "/api/admin/persons").andExpect(status().isForbidden());
            doGet(u, someone).andExpect(status().isForbidden());
            doPost(u, "/api/admin/persons", Map.of("fullName", "Yeni")).andExpect(status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(someone).with(u.as())
                    .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Hacked\"}"))
                    .andExpect(status().isForbidden());
            doDelete(u, someone).andExpect(status().isForbidden());
        }
        doGet(null, "/api/admin/persons").andExpect(status().isUnauthorized());
        assertEquals(student.name(), persons.findById(student.id()).orElseThrow().getFullName());

        doGet(admin, "/api/admin/persons?q=" + student.studentNo()).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(student.id()))
                .andExpect(jsonPath("$[0].studentNo").value(student.studentNo()));
        doGet(admin, someone).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.hasPassword").value(false));
        doGet(admin, "/api/admin/persons/999999999").andExpect(status().isNotFound());
    }

    @Test
    void adminCreatesEditsDeactivatesAndDeletesPeople() throws Exception {
        User admin = newAdmin();
        String address = email();
        String username = "kullanici" + next();

        // Create: a student number makes a student (random nickname), none makes a staff-like person.
        JsonNode created = body(doPost(admin, "/api/admin/persons", Map.of("fullName", "  Zeynep Arslan ",
                "email", address.toUpperCase(java.util.Locale.ROOT), "studentNo", "99" + next(), "username", username,
                "password", "gizli-parola-1")).andExpect(status().isCreated()));
        long id = created.get("id").asLong();
        assertEquals("Zeynep Arslan", created.get("fullName").asString());
        assertEquals(address, created.get("email").asString());
        assertTrue(created.get("hasPassword").asBoolean());
        assertFalse(created.get("nickname").asString().startsWith("Instructor"));
        assertFalse(created.toString().contains("gizli-parola-1"));
        JsonNode staffLike = body(doPost(admin, "/api/admin/persons", Map.of("fullName", "Hoca Biri"))
                .andExpect(status().isCreated()));
        assertTrue(staffLike.get("nickname").asString().startsWith("Instructor"));

        // Validation and uniqueness.
        doPost(admin, "/api/admin/persons", Map.of("fullName", "Çift Posta", "email", address)).andExpect(status().isConflict());
        doPost(admin, "/api/admin/persons", Map.of("fullName", "Kötü Posta", "email", "kotu")).andExpect(status().isBadRequest());
        doPost(admin, "/api/admin/persons", Map.of("fullName", "Kötü No", "studentNo", "12")).andExpect(status().isBadRequest());
        doPost(admin, "/api/admin/persons", Map.of("fullName", "Kısa", "username", "u" + next(), "password", "kisa"))
                .andExpect(status().isBadRequest());
        doPost(admin, "/api/admin/persons", Map.of("fullName", " ")).andExpect(status().isBadRequest());
        doPost(admin, "/api/admin/persons", Map.of("fullName", "Aynı Kullanıcı", "username", username))
                .andExpect(status().isConflict());

        // The person can sign in with the password, and the session ends when the account is deactivated.
        Cookie cookie = sessionOf(doPost(null, "/api/auth/login", Map.of("username", username,
                "password", "gizli-parola-1")).andExpect(status().isOk()));
        me(cookie).andExpect(status().isOk());
        patch(admin, id, Map.of("fullName", "Zeynep A.")).andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Zeynep A."))
                .andExpect(jsonPath("$.email").value(address));       // null = unchanged
        patch(admin, id, Map.of("email", "")).andExpect(status().isOk()).andExpect(jsonPath("$.email").doesNotExist());
        patch(admin, id, Map.of("active", false)).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        me(cookie).andExpect(status().isUnauthorized());
        doPost(null, "/api/auth/login", Map.of("username", username, "password", "gizli-parola-1"))
                .andExpect(status().isUnauthorized());
        patch(admin, id, Map.of("active", true)).andExpect(status().isOk());
        doPost(null, "/api/auth/login", Map.of("username", username, "password", "gizli-parola-1"))
                .andExpect(status().isOk());
        // Clearing the password turns password sign-in off.
        patch(admin, id, Map.of("password", "")).andExpect(status().isOk()).andExpect(jsonPath("$.hasPassword").value(false));
        doPost(null, "/api/auth/login", Map.of("username", username, "password", "gizli-parola-1"))
                .andExpect(status().isUnauthorized());

        // Deleting is a soft delete: gone from rosters and sign-in, row stays.
        User owner = newStaff();
        CourseRef course = newCourse(owner, false, 2);
        User student = newStudent(course);
        Cookie studentCookie = student.cookie();
        me(studentCookie).andExpect(status().isOk());
        assertTrue(numbers(body(doGet(owner, "/api/courses/" + course.id() + "/roster"))).contains(student.studentNo()));
        doDelete(admin, "/api/admin/persons/" + student.id()).andExpect(status().isNoContent());
        doGet(admin, "/api/admin/persons/" + student.id()).andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.active").value(false));
        me(studentCookie).andExpect(status().isUnauthorized());
        assertFalse(numbers(body(doGet(owner, "/api/courses/" + course.id() + "/roster"))).contains(student.studentNo()));
        assertEquals(1, count("select count(*) from person where id = ?", student.id()));
        // Re-activating brings them back to the roster unchanged.
        patch(admin, student.id(), Map.of("active", true)).andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(false));
        assertTrue(numbers(body(doGet(owner, "/api/courses/" + course.id() + "/roster"))).contains(student.studentNo()));
    }

    @Test
    void adminsCannotLockThemselvesOut() throws Exception {
        User admin = newAdmin();
        patch(admin, admin.id(), Map.of("active", false)).andExpect(status().isConflict());
        patch(admin, admin.id(), Map.of("systemAdmin", false)).andExpect(status().isConflict());
        doDelete(admin, "/api/admin/persons/" + admin.id()).andExpect(status().isConflict());
        doGet(admin, "/api/admin/persons/" + admin.id()).andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.systemAdmin").value(true)).andExpect(jsonPath("$.deleted").value(false));
        // Making someone else an admin works, and takes effect on the next request.
        User staff = newStaff();
        doGet(staff, "/api/admin/persons").andExpect(status().isForbidden());
        patch(admin, staff.id(), Map.of("systemAdmin", true)).andExpect(status().isOk());
        doGet(staff, "/api/admin/persons").andExpect(status().isOk());   // read from the person row on every request
    }

    ResultActions patch(User u, long id, Map<String, Object> body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/admin/persons/" + id).with(u.as()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(body)));
    }

    // ---- student self-service -------------------------------------------------------------------

    JsonNode openSession(User owner, CourseRef course, String title) throws Exception {
        long id = body(doPost(owner, "/api/sessions", Map.of("courseId", course.id(), "title", title,
                "frameCapture", false)).andExpect(status().isCreated())).get("id").asLong();
        return body(doPost(owner, "/api/sessions/" + id + "/open", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN")));
    }

    @Test
    void myAttendanceShowsOnlyMyOwnPresenceWithoutAnyVerificationDetails() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, false, 2);
        User s1 = newStudent(course);
        User s2 = newStudent(course);
        JsonNode week1 = openSession(owner, course, "Hafta 1");
        clock.advance(Duration.ofMinutes(1));   // sessions are listed by the time they were opened
        JsonNode week2 = openSession(owner, course, "Hafta 2");
        // A session that was never opened does not show up.
        long draft = body(doPost(owner, "/api/sessions", Map.of("courseId", course.id(), "title", "Taslak",
                "frameCapture", false)).andExpect(status().isCreated())).get("id").asLong();

        doPost(owner, "/api/sessions/" + week1.get("id").asLong() + "/attendances/manual",
                Map.of("studentNo", s1.studentNo(), "note", "Elle eklendi")).andExpect(status().isCreated());
        long att2 = body(doPost(owner, "/api/sessions/" + week2.get("id").asLong() + "/attendances/manual",
                Map.of("studentNo", s1.studentNo())).andExpect(status().isCreated())).get("id").asLong();
        doPost(owner, "/api/sessions/" + week2.get("id").asLong() + "/attendances/manual",
                Map.of("studentNo", s2.studentNo())).andExpect(status().isCreated());
        // The teacher rejects s1 in week 2.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/sessions/" + week2.get("id").asLong() + "/attendances/" + att2).with(owner.as()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECTED\",\"note\":\"Orada değildi\"}"))
                .andExpect(status().isOk());

        String url = "/api/courses/" + course.id() + "/my-attendance";
        String raw1 = text(doGet(s1, url).andExpect(status().isOk()));
        JsonNode mine = json.readTree(raw1);
        assertEquals(2, mine.size(), raw1);
        assertEquals("Hafta 1", mine.get(0).get("title").asString());
        assertTrue(mine.get(0).get("present").asBoolean());
        assertEquals("Hafta 2", mine.get(1).get("title").asString());
        assertFalse(mine.get(1).get("present").asBoolean(), "a rejected record is not presence");
        assertTrue(mine.get(0).get("sessionId").asLong() == week1.get("id").asLong());
        assertNotEquals(draft, mine.get(0).get("sessionId").asLong());

        String raw2 = text(doGet(s2, url).andExpect(status().isOk()));
        JsonNode theirs = json.readTree(raw2);
        assertEquals(2, theirs.size());
        assertFalse(theirs.get(0).get("present").asBoolean());
        assertTrue(theirs.get(1).get("present").asBoolean());

        // Nothing but id, title, date and presence; no flags, latency, thresholds, decisions, devices, notes, names.
        for (JsonNode row : mine) {
            Set<String> keys = new HashSet<>();
            row.propertyNames().forEach(keys::add);
            assertEquals(Set.of("sessionId", "title", "date", "present"), keys);
        }
        for (String raw : List.of(raw1, raw2)) {
            String lower = raw.toLowerCase(java.util.Locale.ROOT);
            for (String forbidden : List.of("flag", "latency", "threshold", "decision", "devicehint", "note",
                    "ratio", "geo", "ble", "rtt", s1.studentNo(), s2.studentNo(), "elle eklendi")) {
                assertFalse(lower.contains(forbidden.toLowerCase(java.util.Locale.ROOT)), forbidden + " in " + raw);
            }
        }

        // Members only.
        doGet(newStudent(openCourseWithStudent()), url).andExpect(status().isForbidden());
        doGet(null, url).andExpect(status().isUnauthorized());
    }

    CourseRef openCourseWithStudent() throws Exception {
        return newCourse(newStaff(), false, 2);
    }

    @Test
    void staffStudentPageShowsProfileAndAttendanceOfOneRosterStudent() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, false, 2);
        User s1 = newStudent(course);
        User s2 = newStudent(course);
        JsonNode week1 = openSession(owner, course, "Hafta 1");
        clock.advance(Duration.ofMinutes(1));
        JsonNode week2 = openSession(owner, course, "Hafta 2");
        clock.advance(Duration.ofMinutes(1));
        openSession(owner, course, "Hafta 3");
        doPost(owner, "/api/sessions/" + week1.get("id").asLong() + "/attendances/manual",
                Map.of("studentNo", s1.studentNo(), "note", "Elle eklendi")).andExpect(status().isCreated());
        long att2 = body(doPost(owner, "/api/sessions/" + week2.get("id").asLong() + "/attendances/manual",
                Map.of("studentNo", s1.studentNo())).andExpect(status().isCreated())).get("id").asLong();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/sessions/" + week2.get("id").asLong() + "/attendances/" + att2).with(owner.as()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECTED\",\"note\":\"Yoktu\"}"))
                .andExpect(status().isOk());

        String url = "/api/courses/" + course.id() + "/students/" + s1.id();
        JsonNode s = body(doGet(owner, url).andExpect(status().isOk()));
        assertEquals(s1.id(), s.get("personId").asLong());
        assertEquals(s1.studentNo(), s.get("studentNo").asString());
        JsonNode att = s.get("attendance");
        assertEquals(3, att.size());
        assertEquals("Hafta 1", att.get(0).get("title").asString());
        assertEquals("PRESENT", att.get(0).get("state").asString());
        assertEquals("MANUAL", att.get(0).get("source").asString());
        assertEquals("REJECTED", att.get(1).get("state").asString());
        assertEquals("Yoktu", att.get(1).get("teacherNote").asString());
        assertEquals("ABSENT", att.get(2).get("state").asString());

        // Students (even of the same course) and outsiders cannot open it; non-students of the course are 404.
        doGet(s2, url).andExpect(status().isForbidden());
        doGet(s1, url).andExpect(status().isForbidden());
        doGet(newStaff(), url).andExpect(status().isForbidden());
        doGet(owner, "/api/courses/" + course.id() + "/students/" + owner.id()).andExpect(status().isNotFound());
        doGet(owner, "/api/courses/" + course.id() + "/students/" + newStudent(newCourse(newStaff(), false, 2)).id())
                .andExpect(status().isNotFound());
    }

    @Test
    void myResultsReturnOnlyMyOwnTotals() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, false, 2);
        User s1 = newStudent(course);
        User s2 = newStudent(course);
        User s3 = newStudent(course);

        Map<String, Object> q1 = Map.of("type", "CHOICE", "topic", "Konu A", "maxPoints", 4, "choiceCount", 4,
                "correctChoice", "B");
        Map<String, Object> q2 = Map.of("type", "FREE", "topic", "Konu B", "maxPoints", 6);
        JsonNode exam = body(doPost(owner, "/api/courses/" + course.id() + "/exams",
                Map.of("title", "Vize", "examDate", "2026-11-20", "questions", List.of(q1, q2)))
                .andExpect(status().isCreated()));
        long examId = exam.get("id").asLong();
        long e1 = exam.get("questions").get(0).get("id").asLong();
        long e2 = exam.get("questions").get(1).get("id").asLong();
        JsonNode second = body(doPost(owner, "/api/courses/" + course.id() + "/exams",
                Map.of("title", "Final", "questions", List.of(q1))).andExpect(status().isCreated()));

        putResult(owner, examId, s1.studentNo(), e1, "B", 4, e2, null, 3.5);
        putResult(owner, examId, s2.studentNo(), e1, "A", 0, e2, null, 6);
        long secondQ = second.get("questions").get(0).get("id").asLong();
        Map<String, Object> only = new LinkedHashMap<>();
        only.put("answers", List.of(Map.of("questionId", secondQ, "answer", "B", "points", 4)));
        doPut(owner, "/api/exams/" + second.get("id").asLong() + "/results/" + s1.studentNo(), only)
                .andExpect(status().isOk());

        String url = "/api/courses/" + course.id() + "/my-results";
        // Unpublished exams are not listed; staff publish them.
        assertEquals(0, body(doGet(s1, url).andExpect(status().isOk())).size());
        doPost(owner, "/api/exams/" + examId + "/publish", null).andExpect(status().isOk());
        doPost(owner, "/api/exams/" + second.get("id").asLong() + "/publish", null).andExpect(status().isOk());
        String raw = text(doGet(s1, url).andExpect(status().isOk()));
        JsonNode mine = json.readTree(raw);
        assertEquals(2, mine.size(), raw);
        JsonNode vize = null;
        for (JsonNode r : mine) if ("Vize".equals(r.get("title").asString())) vize = r;
        assertNotNull(vize);
        assertEquals(examId, vize.get("examId").asLong());
        assertEquals(7.5, vize.get("points").asDouble(), 1e-6);
        assertEquals(10, vize.get("maxPoints").asDouble(), 1e-6);
        assertEquals("2026-11-20", vize.get("examDate").asString());
        Set<String> keys = new HashSet<>();
        vize.propertyNames().forEach(keys::add);
        assertEquals(Set.of("examId", "title", "examDate", "points", "maxPoints"), keys);

        JsonNode theirs = body(doGet(s2, url).andExpect(status().isOk()));
        assertEquals(1, theirs.size());
        assertEquals(6, theirs.get(0).get("points").asDouble(), 1e-6);
        assertEquals(0, body(doGet(s3, url).andExpect(status().isOk())).size());
        // No answers, other students' numbers, names or per-question detail leak.
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : List.of(s1.studentNo(), s2.studentNo(), s2.name(), "answer", "correct", "topic",
                "questionid", "form", "note")) {
            assertFalse(lower.contains(forbidden.toLowerCase(java.util.Locale.ROOT)), forbidden + " in " + raw);
        }
        doGet(newStudent(openCourseWithStudent()), url).andExpect(status().isForbidden());
        doGet(null, url).andExpect(status().isUnauthorized());
    }

    void putResult(User owner, long examId, String no, long q1, String a1, Object p1, long q2, String a2, Object p2)
            throws Exception {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("questionId", q1);
        first.put("answer", a1);
        first.put("points", p1);
        Map<String, Object> secondAnswer = new LinkedHashMap<>();
        secondAnswer.put("questionId", q2);
        secondAnswer.put("answer", a2);
        secondAnswer.put("points", p2);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("answers", List.of(first, secondAnswer));
        doPut(owner, "/api/exams/" + examId + "/results/" + no, req).andExpect(status().isOk());
    }

    // ---- roster-only check-in -------------------------------------------------------------------

    ResultActions startCheckin(String code, String studentNo) throws Exception {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        Cookie device = new Cookie("YD", Base32.encode(b));
        return mvc.perform(post("/api/public/checkins").cookie(device).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("sessionCode", code.toLowerCase(java.util.Locale.ROOT), "studentNo", studentNo,
                        "client", Map.of("userAgent", "JUnit", "platform", "test")))));
    }

    @Test
    void checkInStartIsRosterOnly() throws Exception {
        User owner = newStaff();
        CourseRef course = newCourse(owner, false, 2);
        User onRoster = newStudent(course);
        User removed = newStudent(course);
        User elsewhere = newStudent(newCourse(newStaff(), false, 2));
        JsonNode session = openSession(owner, course, "Hafta 5");
        String code = session.get("code").asString();
        clock.setMillis(Math.max(clock.millis(), session.get("epochStartMs").asLong()));

        // On the roster: allowed, and the answer carries the roster name.
        startCheckin(code, onRoster.studentNo()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.studentName").value(onRoster.name()));

        // Not on this roster (but a student elsewhere, or an unknown number): refused.
        startCheckin(code, elsewhere.studentNo()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_IN_ROSTER"));
        startCheckin(code, "9" + next()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_IN_ROSTER"));

        // Soft-removed from the roster: refused; put back: allowed again.
        startCheckin(code, removed.studentNo()).andExpect(status().isCreated());
        doDelete(owner, "/api/courses/" + course.id() + "/roster/" + removed.id()).andExpect(status().isNoContent());
        startCheckin(code, removed.studentNo()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_IN_ROSTER"));
        doPost(owner, "/api/courses/" + course.id() + "/roster",
                Map.of("studentNo", removed.studentNo(), "fullName", removed.name())).andExpect(status().isCreated());
        startCheckin(code, removed.studentNo()).andExpect(status().isCreated());

        // Also refused once the person is deactivated by an admin.
        User admin = newAdmin();
        patch(admin, onRoster.id(), Map.of("active", false)).andExpect(status().isOk());
        startCheckin(code, onRoster.studentNo()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_IN_ROSTER"));

        // The format is checked first.
        startCheckin(code, "12").andExpect(status().isBadRequest());
    }
}
