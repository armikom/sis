package com.armikom.sis.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app")
@Validated
public record AppProperties(
        String masterSecret,
        String publicBaseUrl,
        @Valid Admin admin,
        @Valid Auth auth,
        @Valid Mail mail,
        String privacyNotice,
        @Valid DeviceCookie deviceCookie,
        @Valid Frames frames,
        @Valid Retention retention,
        @Valid RateLimit rateLimit,
        @Valid Ble ble,
        @Valid Checkin checkin
) {
    public record Admin(String username, String password) {}

    /** DB-backed sign-in sessions and mailed login links (core.auth). */
    public record Auth(
            @NotBlank String cookieName,
            boolean cookieSecure,
            @Positive int sessionDays,
            @Positive int linkMinutes,
            String linkHosts
    ) {}

    /** Sender address and optional Exchange Web Services endpoint; SMTP itself is spring.mail.*. */
    public record Mail(String from, String ewsUrl) {}

    public record DeviceCookie(
            @NotBlank String name,
            @Positive int maxAgeDays,
            boolean secure
    ) {}

    public record Frames(
            @NotBlank String dir,
            @Positive int maxBytes,
            @Positive int retentionDays
    ) {}

    public record Retention(@Positive int attemptDays) {}

    public record RateLimit(
            @Positive int perDevicePerMinute,
            @Positive int perIpPerMinute,
            @Positive int startsPerDevicePer10min,
            @Positive int loginPerIpPerMinute,
            @Positive int authRequestPerIpPerMinute
    ) {}

    public record Ble(
            String serviceUuid,
            String nonceCharUuid,
            String responseCharUuid,
            @Positive long nonceTtlMs
    ) {}

    public record Checkin(
            @Valid Defaults defaults,
            @Positive int maxClockRttMs,
            @Positive int maxGeoAccuracyM,
            boolean bleClearsLatency,
            @Positive int attemptTtlSeconds,
            @NotBlank String studentNoPattern,
            @Positive int maxScansPerAttempt
    ) {
        public record Defaults(
                @Positive int refreshMs,
                @Positive int requiredScans,
                @Positive int maxSpanSlots,
                @Positive int maxTokenAgeMs,
                @Positive int latencyMarginMs,
                @Positive int defaultLatencyThresholdMs,
                double screenAreaRatioThreshold,
                @Positive int durationMinutes,
                String geoMode,
                String bleMode,
                boolean frameCapture
        ) {}
    }
}
