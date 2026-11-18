package com.armikom.sis.exam;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "exam_result")
public class ExamResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    /** The enrolled student (results are roster-only). */
    @Column(name = "person_id", nullable = false)
    private Long personId;

    /** Snapshot of the student number. */
    @Column(name = "student_no", nullable = false, length = 32)
    private String studentNo;

    /** Paper form (A, B, ...) the student wrote, if the exam has several. */
    @Column(length = 1)
    private String form;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExamResult() {}

    public ExamResult(Exam exam, Long personId, String studentNo, Instant createdAt) {
        this.exam = exam;
        this.personId = personId;
        this.studentNo = studentNo;
        this.fullName = "";
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public Long getId() { return id; }
    public Exam getExam() { return exam; }
    public Long getPersonId() { return personId; }
    public String getStudentNo() { return studentNo; }
    public String getForm() { return form; }
    public void setForm(String form) { this.form = form; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
