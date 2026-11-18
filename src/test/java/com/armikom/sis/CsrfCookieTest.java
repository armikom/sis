package com.armikom.sis;

import com.armikom.sis.support.PostgresTestcontainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Real CSRF cookie round-trip as the SPA does it, with the DB-backed sis_session cookie. Runs in its own
 * context: Spring Security's {@code csrf()} test post-processor (used elsewhere) replaces the token repository.
 */
@SpringBootTest(properties = "spring.application.name=sis-csrf-test")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestcontainer.class)
class CsrfCookieTest {

    @Autowired MockMvc mvc;

    @Test
    void spaCsrfFlowWithSessionCookie() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andReturn();
        Cookie token = csrf.getResponse().getCookie("XSRF-TOKEN");

        String login = "{\"username\":\"admin\",\"password\":\"admin123\"}";
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").cookie(token).header("X-XSRF-TOKEN", token.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        MvcResult ok = mvc.perform(post("/api/auth/login").cookie(token).header("X-XSRF-TOKEN", token.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemAdmin").value(true))
                .andExpect(jsonPath("$.staff").value(true))
                .andExpect(cookie().httpOnly("sis_session", true))
                .andReturn();
        Cookie session = ok.getResponse().getCookie("sis_session");

        mvc.perform(get("/api/admin/persons").cookie(session)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").cookie(session, token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownMailGetsNoLinkAndNoSession() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/auth/csrf")).andReturn();
        Cookie token = csrf.getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/api/auth/request").cookie(token).header("X-XSRF-TOKEN", token.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nobody@example.edu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.onRoster").value(false));
        mvc.perform(post("/api/auth/verify").cookie(token).header("X-XSRF-TOKEN", token.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"bogus\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINK_INVALID"));
    }
}
