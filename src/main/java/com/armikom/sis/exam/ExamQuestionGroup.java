package com.armikom.sis.exam;

import jakarta.persistence.*;

/** A group of sub-questions (5a, 5b, …) with a shared stem; scoring stays on the member {@link ExamQuestion}s. */
@Entity
@Table(name = "exam_question_group")
public class ExamQuestionGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    /** Number printed on the paper (shared numbering with ungrouped questions). */
    @Column(nullable = false)
    private int position;

    @Column(columnDefinition = "text")
    private String text;

    @Column(columnDefinition = "text")
    private String html;

    protected ExamQuestionGroup() {}

    public ExamQuestionGroup(Exam exam) {
        this.exam = exam;
    }

    public Long getId() { return id; }
    public Exam getExam() { return exam; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getHtml() { return html; }
    public void setHtml(String html) { this.html = html; }
}
