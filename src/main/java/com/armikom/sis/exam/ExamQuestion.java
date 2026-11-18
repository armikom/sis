package com.armikom.sis.exam;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "exam_question")
public class ExamQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QuestionType type;

    @Column(columnDefinition = "text")
    private String text;

    /** Overall explanation of the answer. */
    @Column(columnDefinition = "text")
    private String explanation;

    @Column(length = 120)
    private String topic;

    @Column(name = "max_points", nullable = false, precision = 7, scale = 2)
    private BigDecimal maxPoints;

    @Column(name = "choice_count")
    private Integer choiceCount;

    @Column(name = "correct_choice", length = 1)
    private String correctChoice;

    /** The pool question this item was drawn from, if any. */
    @Column(name = "pool_question_id")
    private Long poolQuestionId;

    protected ExamQuestion() {}

    public ExamQuestion(Exam exam) {
        this.exam = exam;
    }

    public Long getId() { return id; }
    public Exam getExam() { return exam; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public QuestionType getType() { return type; }
    public void setType(QuestionType type) { this.type = type; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public BigDecimal getMaxPoints() { return maxPoints; }
    public void setMaxPoints(BigDecimal maxPoints) { this.maxPoints = maxPoints; }
    public Integer getChoiceCount() { return choiceCount; }
    public void setChoiceCount(Integer choiceCount) { this.choiceCount = choiceCount; }
    public String getCorrectChoice() { return correctChoice; }
    public void setCorrectChoice(String correctChoice) { this.correctChoice = correctChoice; }
    public Long getPoolQuestionId() { return poolQuestionId; }
    public void setPoolQuestionId(Long poolQuestionId) { this.poolQuestionId = poolQuestionId; }
}
