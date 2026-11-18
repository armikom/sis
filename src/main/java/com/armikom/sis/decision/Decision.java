package com.armikom.sis.decision;

import com.armikom.sis.attendance.AttendanceStatus;
import com.armikom.sis.attendance.GeoState;

import java.util.Set;

public record Decision(
        AttendanceStatus status,
        Set<Flag> flags,
        Integer minLatencyMs,
        int latencyThresholdMs,
        String thresholdSource,
        Double areaRatioMax,
        double areaThreshold,
        GeoState geoState,
        Double geoDistanceM,
        Double geoAccuracyM,
        Integer clockOffsetMs,
        Integer clockRttMs
) {}
