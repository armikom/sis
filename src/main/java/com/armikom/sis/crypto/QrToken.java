package com.armikom.sis.crypto;

public record QrToken(String code, int seq, long ts, String raw) {}
