package com.armikom.sis.core.course;

import com.armikom.sis.core.person.PersonService;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Parses a pasted or uploaded class list. One student per line, either {@code studentNo, fullName[, email]}
 * (yoklama) or {@code email, fullName, studentNo} (question-pool); the order is detected per line by the {@code @}.
 * Separator {@code ;}, {@code ,} or tab (detected on the first line), optional header line, optional UTF-8 BOM,
 * double-quoted fields. A student number repeated in the input keeps its last row.
 */
public class RosterParser {

    static final int MAX_NAME = 200;

    private final Pattern studentNoPattern;

    public RosterParser(Pattern studentNoPattern) {
        this.studentNoPattern = studentNoPattern;
    }

    public record Row(int line, String studentNo, String fullName, String email) {}
    public record ImportError(int line, String message) {}
    public record Parsed(List<Row> rows, int skipped, List<ImportError> errors) {}

    public Parsed parse(String text) {
        try {
            return parse(new ByteArrayInputStream((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Parsed parse(InputStream input) throws IOException {
        List<ImportError> errors = new ArrayList<>();
        Map<String, Row> rows = new LinkedHashMap<>();
        int skipped = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stripBom(input), StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            Character sep = null;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                if (sep == null) {
                    sep = detectSeparator(line);
                    if (isHeaderLine(parseLine(line, sep))) continue;
                }
                // Pasted lists may mix separators: a line without the detected one gets its own.
                char lineSep = line.indexOf(sep) >= 0 ? sep : detectSeparator(line);
                String[] fields = parseLine(line, lineSep);
                if (fields.length < 2) {
                    errors.add(new ImportError(lineNo, "En az 2 sütun gerekli."));
                    continue;
                }
                String first = fields[0].trim();
                String studentNo;
                String email;
                String fullName = fields[1].trim();
                if (first.contains("@")) {
                    email = first;
                    studentNo = fields.length > 2 ? fields[2].trim() : "";
                } else {
                    studentNo = first;
                    email = fields.length > 2 ? fields[2].trim() : "";
                }
                if (studentNo.isEmpty()) {
                    errors.add(new ImportError(lineNo, "Öğrenci numarası gerekli."));
                    continue;
                }
                if (!studentNoPattern.matcher(studentNo).matches()) {
                    errors.add(new ImportError(lineNo, "Geçersiz öğrenci numarası: " + clip(studentNo, 40)));
                    continue;
                }
                if (fullName.isEmpty()) {
                    errors.add(new ImportError(lineNo, "Ad boş olamaz."));
                    continue;
                }
                if (!email.isEmpty() && !PersonService.isEmail(email)) {
                    errors.add(new ImportError(lineNo, "Geçersiz e-posta: " + clip(email, 60)));
                    continue;
                }
                Row row = new Row(lineNo, studentNo, clip(fullName, MAX_NAME),
                        email.isEmpty() ? null : PersonService.normalizeEmail(email));
                if (rows.remove(studentNo) != null) skipped++;
                rows.put(studentNo, row);
            }
        }
        return new Parsed(new ArrayList<>(rows.values()), skipped, errors);
    }

    /** A first line whose first field is neither a number nor an e-mail address is a header. */
    private static boolean isHeaderLine(String[] fields) {
        if (fields.length < 2) return false;
        String first = fields[0].trim();
        if (first.contains("@")) return false;
        return first.isEmpty() || !first.chars().allMatch(Character::isDigit);
    }

    private static char detectSeparator(String line) {
        long tabs = line.chars().filter(c -> c == '\t').count();
        long semis = line.chars().filter(c -> c == ';').count();
        long commas = line.chars().filter(c -> c == ',').count();
        if (tabs > semis && tabs > commas) return '\t';
        return semis >= commas ? ';' : ',';
    }

    private static InputStream stripBom(InputStream in) throws IOException {
        PushbackInputStream pis = new PushbackInputStream(in, 3);
        byte[] bom = new byte[3];
        int read = pis.readNBytes(bom, 0, 3);
        boolean isBom = read == 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF;
        if (!isBom && read > 0) pis.unread(bom, 0, read);
        return pis;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    static String[] parseLine(String line, char sep) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == sep) {
                fields.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }
}
