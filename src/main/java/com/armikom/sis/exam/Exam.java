package com.armikom.sis.exam;

import com.armikom.sis.core.course.Course;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "exam")
public class Exam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "exam_date")
    private LocalDate examDate;

    /** Number of paper forms (A, B, ...), 1-4. */
    @Column(nullable = false)
    private int forms = 1;

    /** Shuffle seed when the paper was drawn from the question pool. */
    @Column
    private Long seed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Students see their result of this exam only when it is published. */
    @Column(nullable = false)
    private boolean published;

    protected Exam() {}

    public Exam(Course course, String title, LocalDate examDate, Instant createdAt) {
        this.course = course;
        this.title = title;
        this.examDate = examDate;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Course getCourse() { return course; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public LocalDate getExamDate() { return examDate; }
    public void setExamDate(LocalDate examDate) { this.examDate = examDate; }
    public int getForms() { return forms; }
    public void setForms(int forms) { this.forms = forms; }
    public Long getSeed() { return seed; }
    public void setSeed(Long seed) { this.seed = seed; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }
}
