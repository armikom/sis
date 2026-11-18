package com.armikom.sis.attendance;

import com.armikom.sis.checkin.AttemptState;
import com.armikom.sis.checkin.CheckinAttempt;
import com.armikom.sis.checkin.CheckinAttemptRepository;
import com.armikom.sis.common.ApiException;
import com.armikom.sis.common.ErrorCode;
import com.armikom.sis.common.Strings;
import com.armikom.sis.config.AppProperties;
import com.armikom.sis.core.course.EnrollmentRepository;
import com.armikom.sis.core.course.RosterStudent;
import com.armikom.sis.decision.Flag;
import com.armikom.sis.frames.FrameStorage;
import com.armikom.sis.live.LiveEvents;
import com.armikom.sis.session.ClassSession;
import com.armikom.sis.session.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AttendanceService {

    private final AttendanceRepository repo;
    private final EnrollmentRepository enrollmentRepo;
    private final CheckinAttemptRepository attemptRepo;
    private final SessionService sessionService;
    private final FrameStorage frameStorage;
    private final LiveEvents liveEvents;
    private final AppProperties props;
    private final Clock clock;
    private final Pattern studentNoPattern;

    /** A rejected/blocked attempt together with the record that collided with it, if any. */
    public record AttemptView(CheckinAttempt attempt, String conflictStudentNo) {}

    public AttendanceService(AttendanceRepository repo, EnrollmentRepository enrollmentRepo,
                             CheckinAttemptRepository attemptRepo, SessionService sessionService,
                             FrameStorage frameStorage, LiveEvents liveEvents, AppProperties props, Clock clock) {
        this.repo = repo;
        this.enrollmentRepo = enrollmentRepo;
        this.attemptRepo = attemptRepo;
        this.sessionService = sessionService;
        this.frameStorage = frameStorage;
        this.liveEvents = liveEvents;
        this.props = props;
        this.clock = clock;
        this.studentNoPattern = Pattern.compile(props.checkin().studentNoPattern());
    }

    @Transactional(readOnly = true)
    public List<Attendance> list(long sessionId) {
        sessionService.get(sessionId);
        return repo.findBySession_IdOrderByCreatedAtDesc(sessionId);
    }

    @Transactional(readOnly = true)
    public List<AttemptView> attempts(long sessionId, AttemptState state) {
        sessionService.get(sessionId);
        List<CheckinAttempt> attempts = attemptRepo.findBySession_IdAndStateOrderByCreatedAtDesc(sessionId, state);
        if (attempts.isEmpty()) return List.of();
        // One pass over the records answers "whose attendance sits on this device?" (04 §4.6).
        Map<String, String> ownerByDevice = repo.findBySession_IdOrderByCreatedAtDesc(sessionId).stream()
                .filter(a -> a.getDeviceId() != null)
                .collect(Collectors.toMap(Attendance::getDeviceId, Attendance::getStudentNo, (first, dup) -> first));
        return attempts.stream().map(a -> new AttemptView(a, conflictOf(a, ownerByDevice))).toList();
    }

    /** The student number that already holds an attendance on the device of a rejected attempt. */
    private static String conflictOf(CheckinAttempt attempt, Map<String, String> ownerByDevice) {
        if (!ErrorCode.DEVICE_ALREADY_USED.name().equals(attempt.getResultCode())) return null;
        String owner = ownerByDevice.get(attempt.getDeviceId());
        return owner != null && !owner.equals(attempt.getStudentNo()) ? owner : null;
    }

    @Transactional
    public Attendance decide(long sessionId, long attId, TeacherDecision decision, String note) {
        Attendance att = find(sessionId, attId);
        att.setTeacherDecision(decision);
        att.setTeacherNote(Strings.clip(note, 500));
        att.setDecidedAt(decision == TeacherDecision.NONE ? null : Instant.now(clock));
        liveEvents.attendanceChanged(att);
        return att;
    }

    @Transactional
    public Attendance manualAdd(long sessionId, String studentNo, String note) {
        ClassSession session = sessionService.get(sessionId);
        String no = studentNo != null ? studentNo.trim() : "";
        if (!studentNoPattern.matcher(no).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("studentNo", "Geçersiz format"));
        }
        RosterStudent student = enrollmentRepo.rosterStudent(session.getCourse().getId(), no)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_IN_ROSTER));
        if (repo.existsBySession_IdAndStudentNo(sessionId, no) || repo.existsBySession_IdAndPersonId(sessionId, student.personId())) {
            throw new ApiException(ErrorCode.ALREADY_CHECKED_IN);
        }
        Instant now = Instant.now(clock);
        Attendance att = new Attendance(session, student.personId(), no, AttendanceSource.MANUAL,
                AttendanceStatus.ACCEPTED, now);
        att.setFullName(student.fullName());
        att.setTeacherDecision(TeacherDecision.APPROVED);
        att.setTeacherNote(Strings.clip(note, 500));
        att.setDecidedAt(now);
        repo.saveAndFlush(att);
        liveEvents.attendanceChanged(att);
        return att;
    }

    /** {@code notInRoster}: numbers that are not on the course roster (not recorded). */
    public record BulkResult(int added, int skipped, List<String> errors, List<String> notInRoster) {}

    @Transactional
    public BulkResult bulkAdd(long sessionId, List<String> studentNumbers, String note) {
        ClassSession session = sessionService.get(sessionId);
        int added = 0;
        int skipped = 0;
        List<String> errors = new java.util.ArrayList<>();
        List<String> notInRoster = new java.util.ArrayList<>();
        Instant now = Instant.now(clock);

        for (String raw : studentNumbers) {
            String no = raw.trim();
            if (no.isEmpty() || no.equals("-")) continue;
            if (!studentNoPattern.matcher(no).matches()) {
                errors.add(no + ": Geçersiz format");
                continue;
            }
            var student = enrollmentRepo.rosterStudent(session.getCourse().getId(), no);
            if (student.isEmpty()) {
                if (!notInRoster.contains(no)) notInRoster.add(no);
                continue;
            }
            if (repo.existsBySession_IdAndStudentNo(sessionId, no)
                    || repo.existsBySession_IdAndPersonId(sessionId, student.get().personId())) {
                skipped++;
                continue;
            }
            Attendance att = new Attendance(session, student.get().personId(), no, AttendanceSource.MANUAL,
                    AttendanceStatus.ACCEPTED, now);
            att.setFullName(student.get().fullName());
            att.setTeacherDecision(TeacherDecision.APPROVED);
            att.setTeacherNote(Strings.clip(note, 500));
            att.setDecidedAt(now);
            repo.saveAndFlush(att);
            liveEvents.attendanceChanged(att);
            added++;
        }
        return new BulkResult(added, skipped, errors, notInRoster);
    }

    @Transactional
    public void delete(long sessionId, long attId) {
        Attendance att = find(sessionId, attId);
        String framePath = att.getFramePath();
        repo.delete(att);
        repo.flush();
        frameStorage.delete(framePath);
        liveEvents.attendanceDeleted(att.getSession(), attId);
    }

    @Transactional(readOnly = true)
    public byte[] loadFrame(long sessionId, long attId) {
        Attendance att = find(sessionId, attId);
        if (att.getFramePath() == null) throw new ApiException(ErrorCode.NOT_FOUND);
        try {
            return frameStorage.load(att.getFramePath());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }

    /**
     * Re-applies the current (e.g. freshly calibrated) latency and area thresholds to check-in records.
     * Only LATENCY_HIGH / SCREEN_SUSPECT are recomputed; teacher decisions are untouched (07 §5).
     */
    @Transactional
    public int reevaluate(long sessionId) {
        ClassSession session = sessionService.get(sessionId);
        int latencyThreshold = sessionService.effectiveLatencyThreshold(session);
        String source = sessionService.thresholdSource(session);
        double areaThreshold = sessionService.effectiveAreaThreshold(session);
        boolean bleClears = props.checkin().bleClearsLatency();

        int changed = 0;
        for (Attendance a : repo.findBySession_IdOrderByCreatedAtDesc(sessionId)) {
            if (a.getSource() != AttendanceSource.CHECKIN) continue;
            Set<Flag> flags = a.getFlags().isEmpty() ? EnumSet.noneOf(Flag.class) : EnumSet.copyOf(a.getFlags());
            flags.remove(Flag.LATENCY_HIGH);
            flags.remove(Flag.SCREEN_SUSPECT);
            if (!(a.isBleVerified() && bleClears)) {
                if (a.getMinLatencyMs() != null && a.getMinLatencyMs() > latencyThreshold) flags.add(Flag.LATENCY_HIGH);
                if (a.getAreaRatioMax() != null && a.getAreaRatioMax() > areaThreshold) flags.add(Flag.SCREEN_SUSPECT);
            }
            AttendanceStatus status = flags.isEmpty() ? AttendanceStatus.ACCEPTED : AttendanceStatus.FLAGGED;
            boolean differs = !flags.equals(a.getFlags()) || status != a.getStatus()
                    || !Objects.equals(a.getLatencyThresholdMs(), latencyThreshold)
                    || !Objects.equals(a.getThresholdSource(), source)
                    || !Objects.equals(a.getAreaThreshold(), areaThreshold);
            if (!differs) continue;
            a.setFlags(flags);
            a.setStatus(status);
            a.setLatencyThresholdMs(latencyThreshold);
            a.setThresholdSource(source);
            a.setAreaThreshold(areaThreshold);
            liveEvents.attendanceChanged(a);
            changed++;
        }
        return changed;
    }

    private Attendance find(long sessionId, long attId) {
        sessionService.get(sessionId);
        return repo.findByIdAndSession_Id(attId, sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
}
