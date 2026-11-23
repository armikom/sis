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

    /** One character per option as printed ("ABCD", "DY", "10"); null on rows older than V4 (then A.. of choiceCount). */
    @Column(name = "choice_labels", length = 16)
    private String choiceLabels;

    @Column(name = "correct_choice", length = 1)
    private String correctChoice;

    /** The pool question this item was drawn from, if any. */
    @Column(name = "pool_question_id")
    private Long poolQuestionId;

    /** Group of sub-questions this question belongs to ({@link ExamQuestionGroup}), if any. */
    @Column(name = "group_id")
    private Long groupId;

    /** Label inside the group ("a", "b", …); blank = assigned from the order in the group. */
    @Column(name = "sub_label", length = 8)
    private String subLabel;

    /** Rich content (HTML, sanitized when displayed); shown instead of {@code text} when present. */
    @Column(columnDefinition = "text")
    private String html;

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
    public String getChoiceLabels() { return choiceLabels; }
    public void setChoiceLabels(String choiceLabels) { this.choiceLabels = choiceLabels; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public String getSubLabel() { return subLabel; }
    public void setSubLabel(String subLabel) { this.subLabel = subLabel; }
    public String getHtml() { return html; }
    public void setHtml(String html) { this.html = html; }

    /** Effective option labels of a CHOICE question: stored labels, else A.. of {@code choiceCount}; null otherwise. */
    public String labels() {
        if (type != QuestionType.CHOICE) return null;
        if (choiceLabels != null && !choiceLabels.isEmpty()) return choiceLabels;
        return choiceCount == null ? null : ExamLabels.defaultLabels(choiceCount);
    }
}
