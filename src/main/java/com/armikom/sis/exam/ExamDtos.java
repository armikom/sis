package com.armikom.sis.exam;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Request and response bodies of the exam API (11 §3.1, §3.4). */
public final class ExamDtos {

    private ExamDtos() {}

    // ---- definitions ---------------------------------------------------------------------------

    /** A topic (numbered section of a chapter text) in camelCase. */
    public record TopicDto(long id, long chapterId, int chapterNumber, String chapterTitle, int number,
                           String titleEn, String titleTr) {}

    /** One option of an exam question as printed ({@code letter} A..F) with why it is right or wrong. */
    public record OptionDto(String letter, String text, String rationale) {}

    /**
     * {@code topicIds}: explicit links; {@code topics}: effective = explicit ∪ the linked pool question's topics.
     * {@code label}: number printed on the paper ("4", or "3a" inside a group); {@code choiceLabels}: one character
     * per option ("ABCD", "DY"), {@code choiceCount} = its length; {@code html}: rich content shown instead of
     * {@code text} when present.
     */
    public record QuestionDto(long id, int position, String label, QuestionType type, String text, String html,
                              String topic, BigDecimal maxPoints, Integer choiceCount, String choiceLabels,
                              String correctChoice, Long poolQuestionId, List<Long> topicIds, List<TopicDto> topics,
                              String explanation, List<OptionDto> options, Long groupId, String subLabel) {}

    /** A group of sub-questions: {@code position} is the number printed, {@code questionIds} in position order. */
    public record GroupDto(long id, int position, String label, String text, String html, List<Long> questionIds) {}

    public record ExamDto(long id, long courseId, String courseCode, String courseName, String title,
                          LocalDate examDate, int forms, Long seed, BigDecimal totalPoints, long resultCount,
                          String createdAt, boolean published, List<QuestionDto> questions, List<GroupDto> groups) {}

    public record ExamSummaryDto(long id, long courseId, String title, LocalDate examDate, int forms,
                                 int questionCount, BigDecimal totalPoints, long resultCount,
                                 BigDecimal averagePoints, String createdAt, boolean published) {}

    /**
     * {@code topicIds}, {@code explanation}, {@code options}, {@code html}: null keeps the current value on update
     * (none for a new question); a list replaces the set; an empty or blank explanation/html clears it.
     * {@code choiceLabels} (preferred) or {@code choiceCount} (A.. of that many) define the options of a CHOICE
     * question. {@code groupRef} names a group of the same request ({@link GroupRequest#ref}); {@code subLabel} is
     * the label inside the group (blank = a, b, … by order).
     */
    public record QuestionRequest(Long id, QuestionType type, String text, String topic, BigDecimal maxPoints,
                                  Integer choiceCount, String correctChoice, Long poolQuestionId,
                                  List<Long> topicIds, String explanation, List<OptionDto> options,
                                  String choiceLabels, String groupRef, String subLabel, String html) {}

    /**
     * A group in an exam request. {@code id} updates an existing group of the exam (null creates one); {@code ref} is
     * the key questions use in {@code groupRef} (unique in the request). {@code text}/{@code html} are stored as given
     * (blank = none). Groups no questions refer to are removed.
     */
    public record GroupRequest(Long id, String ref, String text, String html) {}

    /**
     * {@code forms} (1-4) and {@code seed} are optional; null keeps the current value (1 / none on create).
     * {@code groups}: null = the exam has no groups after this save (same as an empty list).
     */
    public record ExamRequest(String title, LocalDate examDate, List<QuestionRequest> questions, Integer forms,
                              Long seed, List<GroupRequest> groups) {}

    // ---- results -------------------------------------------------------------------------------

    public record AnswerDto(long questionId, String answer, BigDecimal points) {}

    public record ExamResultDto(long id, long personId, String studentNo, String fullName, String form, String note,
                                BigDecimal totalPoints, int gradedCount, boolean complete, String updatedAt,
                                List<AnswerDto> answers) {}

    public record AnswerRequest(Long questionId, String answer, BigDecimal points) {}

    /** {@code fullName} is ignored (the roster name is used); kept for request compatibility. */
    public record ExamResultRequest(String fullName, String note, List<AnswerRequest> answers, String form) {}

    // ---- student self-service -----------------------------------------------------------------

    /** One exam total of the signed-in student ({@code GET /api/courses/{id}/my-results}). */
    public record MyResultDto(long examId, String title, LocalDate examDate, BigDecimal points, BigDecimal maxPoints) {}

    /** One question of a published exam as the student sees it. {@code correct}: CHOICE with key and answer only. */
    public record MyQuestionDto(int position, String label, String text, String html, QuestionType type,
                                String choiceLabels, String myAnswer, String correctChoice, Boolean correct,
                                BigDecimal points, BigDecimal maxPoints, List<TopicDto> topics,
                                List<OptionDto> options, String explanation, QuestionStatsDto stats, Long groupId) {}

    /**
     * Class aggregates of one question: answer rows, share (0..1) of scored answers with full points, average points
     * of scored answers, and for CHOICE the count per letter A..choiceCount plus "blank" ({} otherwise).
     */
    public record QuestionStatsDto(int answered, BigDecimal correctShare, BigDecimal averagePoints,
                                   Map<String, Integer> choiceCounts) {}

    /** Class totals of one exam: results, average and median total, highest total (2 decimals). */
    public record ClassStatsDto(int participants, BigDecimal average, BigDecimal median, BigDecimal maxPoints) {}

    /** {@code missed} of {@code total} questions with this topic were missed (points < maxPoints). */
    public record ReviewTopicDto(TopicDto topic, int missed, int total) {}

    /** {@code GET /api/courses/{id}/my-results/{examId}}. */
    public record MyResultDetailDto(long examId, String title, LocalDate examDate, BigDecimal points,
                                    BigDecimal maxPoints, ClassStatsDto stats, List<MyQuestionDto> questions,
                                    List<ReviewTopicDto> reviewTopics, List<GroupDto> groups) {}

    // ---- exam analysis -------------------------------------------------------------------------

    public record QuestionAnalysisDto(long questionId, int position, String label, QuestionType type, String topic,
                                      BigDecimal maxPoints, int gradedCount, BigDecimal averagePoints,
                                      Double successRate, Map<String, Integer> choiceCounts) {}

    public record TopicAnalysisDto(String topic, int questionCount, BigDecimal maxPoints, Double successRate,
                                   int weakStudentCount) {}

    public record StudentAnalysisDto(String studentNo, String fullName, BigDecimal totalPoints, Double percent,
                                     boolean complete, List<String> weakTopics) {}

    public record ExamAnalysisDto(long examId, BigDecimal totalPoints, int resultCount, BigDecimal average,
                                  BigDecimal median, BigDecimal min, BigDecimal max,
                                  List<QuestionAnalysisDto> questions, List<TopicAnalysisDto> topics,
                                  List<StudentAnalysisDto> students) {}

    // ---- course learning -----------------------------------------------------------------------

    public record WeakStudentDto(String studentNo, String fullName, Double successRate) {}

    public record CourseTopicDto(String topic, int questionCount, Double successRate,
                                 List<WeakStudentDto> weakStudents) {}

    public record CourseStudentDto(String studentNo, String fullName, int examCount, Double percent,
                                   List<String> weakTopics) {}

    public record CourseLearningDto(long courseId, List<ExamSummaryDto> exams, List<CourseTopicDto> topics,
                                    List<CourseStudentDto> students) {}

    // ---- student learning ----------------------------------------------------------------------

    public record StudentExamDto(long examId, String title, LocalDate examDate, BigDecimal totalPoints,
                                 BigDecimal maxPoints, Double percent, boolean complete) {}

    public record StudentTopicDto(String topic, int questionCount, BigDecimal earnedPoints, BigDecimal maxPoints,
                                  Double successRate) {}

    public record MissedQuestionDto(long examId, String examTitle, int position, String label, String topic,
                                    String text, String answer, String correctChoice, BigDecimal points,
                                    BigDecimal maxPoints) {}

    public record StudentLearningDto(long courseId, String studentNo, String fullName, List<StudentExamDto> exams,
                                     List<StudentTopicDto> topics, List<MissedQuestionDto> missedQuestions) {}
}
