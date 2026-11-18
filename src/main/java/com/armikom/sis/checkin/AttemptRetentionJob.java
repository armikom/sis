package com.armikom.sis.checkin;

import com.armikom.sis.config.AppProperties;
import com.armikom.sis.session.ClassSession;
import com.armikom.sis.session.ClassSessionRepository;
import com.armikom.sis.session.SessionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Deletes check-in attempts (and their scans) of sessions closed more than {@code attempt-days} ago (05 §9.6). */
@Component
public class AttemptRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AttemptRetentionJob.class);
    private final CheckinAttemptRepository attemptRepo;
    private final ClassSessionRepository sessionRepo;
    private final AppProperties props;
    private final Clock clock;

    public AttemptRetentionJob(CheckinAttemptRepository attemptRepo, ClassSessionRepository sessionRepo,
                               AppProperties props, Clock clock) {
        this.attemptRepo = attemptRepo;
        this.sessionRepo = sessionRepo;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public int cleanupAttempts() {
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(props.retention().attemptDays()));
        var ids = sessionRepo.findByStatusAndClosedAtBefore(SessionStatus.CLOSED, cutoff).stream()
                .map(ClassSession::getId).toList();
        if (ids.isEmpty()) return 0;
        int deleted = attemptRepo.deleteBySessionIds(ids);
        if (deleted > 0) log.info("Deleted {} old check-in attempts", deleted);
        return deleted;
    }
}
