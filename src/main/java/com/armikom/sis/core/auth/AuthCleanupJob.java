package com.armikom.sis.core.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AuthCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AuthCleanupJob.class);

    private final UserSessionService sessions;

    public AuthCleanupJob(UserSessionService sessions) {
        this.sessions = sessions;
    }

    @Scheduled(cron = "0 17 3 * * *")
    public void run() {
        int removed = sessions.purgeExpired();
        if (removed > 0) log.info("Removed {} expired sessions/login links", removed);
    }
}
