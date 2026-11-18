package com.armikom.sis.checkin;

import com.armikom.sis.common.ApiException;
import com.armikom.sis.common.ErrorCode;
import com.armikom.sis.config.AppProperties;
import com.armikom.sis.session.ClassSession;
import com.armikom.sis.session.ClassSessionRepository;
import com.armikom.sis.session.SessionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@RestController
@RequestMapping("/api/public/sessions")
public class PublicSessionController {

    private final ClassSessionRepository sessionRepo;
    private final AppProperties props;

    public PublicSessionController(ClassSessionRepository sessionRepo, AppProperties props) {
        this.sessionRepo = sessionRepo;
        this.props = props;
    }

    public record PublicSessionDto(String code, String courseCode, String courseName,
                                   String title, String status, String closesAt,
                                   int refreshMs, int requiredScans,
                                   String geoMode, String bleMode, boolean frameCapture,
                                   String privacyNotice, String studentNoPattern) {}

    @GetMapping("/{code}")
    @Transactional(readOnly = true)
    public PublicSessionDto get(@PathVariable String code) {
        String upper = code.trim().toUpperCase(Locale.ROOT);
        // DRAFT sessions are not public; several CLOSED sessions may share an old code.
        ClassSession session = sessionRepo.findByCodeAndStatus(upper, SessionStatus.OPEN)
                .or(() -> sessionRepo.findFirstByCodeAndStatusOrderByClosedAtDesc(upper, SessionStatus.CLOSED))
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));

        return new PublicSessionDto(
                session.getCode(),
                session.getCourse().getCode(),
                session.getCourse().getName(),
                session.getTitle(),
                session.getStatus().name(),
                session.getClosesAt() != null ? session.getClosesAt().toString() : null,
                session.getRefreshMs(),
                session.getRequiredScans(),
                session.getGeoMode().name(),
                session.getBleMode().name(),
                session.isFrameCapture(),
                session.isFrameCapture() ? props.privacyNotice() : null,
                props.checkin().studentNoPattern()
        );
    }
}
