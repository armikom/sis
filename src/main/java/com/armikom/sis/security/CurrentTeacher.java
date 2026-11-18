package com.armikom.sis.security;

import com.armikom.sis.common.ApiException;
import com.armikom.sis.common.ErrorCode;
import com.armikom.sis.teacher.TeacherRole;
import com.armikom.sis.teacher.TeacherUser;
import com.armikom.sis.teacher.TeacherUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentTeacher {

    private final TeacherUserRepository repo;

    public CurrentTeacher(TeacherUserRepository repo) {
        this.repo = repo;
    }

    public TeacherUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return repo.findByUsername(auth.getName())
                .filter(TeacherUser::isEnabled)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    public void requireOwner(Long ownerId) {
        TeacherUser current = get();
        if (current.getRole() == TeacherRole.ADMIN) return;
        if (!current.getId().equals(ownerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }
}
