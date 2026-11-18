package com.armikom.sis.core.auth;

import java.security.Principal;

/**
 * The signed-in person, as stored in Spring Security's {@code Authentication#getPrincipal()}.
 * Obtain it with {@link CourseAccess#currentPerson()} (or {@code @AuthenticationPrincipal SisPrincipal p}).
 */
public record SisPrincipal(long personId, String fullName, String email, String studentNo, String nickname,
                           boolean systemAdmin) implements Principal {

    @Override
    public String getName() {
        return String.valueOf(personId);
    }
}
