package com.armikom.sis.core.course;

import jakarta.persistence.*;

import java.time.Instant;

/** One offering of a course in a term (e.g. SENG 403, 2026-FALL) with its per-course settings. */
@Entity
@Table(name = "course")
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 16)
    private String term;

    @Column(nullable = false, length = 8)
    private String language = "tr";

    @Column(name = "attendance_enabled", nullable = false)
    private boolean attendanceEnabled = true;

    @Column(name = "pool_enabled", nullable = false)
    private boolean poolEnabled;

    @Column(name = "questions_per_chapter", nullable = false)
    private int questionsPerChapter = 2;

    @Column(name = "ask_ai_per_day", nullable = false)
    private int askAiPerDay = 10;

    @Column(name = "ai_context", nullable = false, columnDefinition = "text")
    private String aiContext = "";

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Course() {}

    public Course(String code, String name, String term) {
        this.code = code;
        this.name = name;
        this.term = term;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTerm() { return term; }
    public void setTerm(String term) { this.term = term; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public boolean isAttendanceEnabled() { return attendanceEnabled; }
    public void setAttendanceEnabled(boolean attendanceEnabled) { this.attendanceEnabled = attendanceEnabled; }
    public boolean isPoolEnabled() { return poolEnabled; }
    public void setPoolEnabled(boolean poolEnabled) { this.poolEnabled = poolEnabled; }
    public int getQuestionsPerChapter() { return questionsPerChapter; }
    public void setQuestionsPerChapter(int questionsPerChapter) { this.questionsPerChapter = questionsPerChapter; }
    public int getAskAiPerDay() { return askAiPerDay; }
    public void setAskAiPerDay(int askAiPerDay) { this.askAiPerDay = askAiPerDay; }
    public String getAiContext() { return aiContext; }
    public void setAiContext(String aiContext) { this.aiContext = aiContext; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }
    public boolean isArchived() { return archivedAt != null; }
    public Instant getCreatedAt() { return createdAt; }
}
