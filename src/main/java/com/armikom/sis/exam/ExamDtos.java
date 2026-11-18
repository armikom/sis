package com.armikom.sis.exam;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Request and response bodies of the exam API (11 §3.1, §3.4). */
public final class ExamDtos {

    private ExamDtos() {}

    // ---- definitions ---------------------------------------------------------------------------

    public record QuestionDto(long id, int position, QuestionType type, String text, String topic,
                              BigDecimal maxPoints, Integer choiceCount, String correctChoice, Long poolQuestionId) {}

    public record ExamDto(long id, long courseId, String courseCode, String courseName, String title,
                          LocalDate examDate, int forms, Long seed, BigDecimal totalPoints, long resultCount,
                          String createdAt, List<QuestionDto> questions) {}

    public record ExamSummaryDto(long id, long courseId, String title, LocalDate examDate, int forms,
                                 int questionCount, BigDecimal totalPoints, long resultCount,
                                 BigDecimal averagePoints, String createdAt) {}

    public record QuestionRequest(Long id, QuestionType type, String text, String topic, BigDecimal maxPoints,
                                  Integer choiceCount, String correctChoice, Long poolQuestionId) {}

    /** {@code forms} (1-4) and {@code seed} are optional; null keeps the current value (1 / none on create). */
    public record ExamRequest(String title, LocalDate examDate, List<QuestionRequest> questions, Integer forms,
                              Long seed) {}

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

    // ---- exam analysis -------------------------------------------------------------------------

    public record QuestionAnalysisDto(long questionId, int position, QuestionType type, String topic,
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

    public record MissedQuestionDto(long examId, String examTitle, int position, String topic, String text,
                                    String answer, String correctChoice, BigDecimal points, BigDecimal maxPoints) {}

    public record StudentLearningDto(long courseId, String studentNo, String fullName, List<StudentExamDto> exams,
                                     List<StudentTopicDto> topics, List<MissedQuestionDto> missedQuestions) {}
}
