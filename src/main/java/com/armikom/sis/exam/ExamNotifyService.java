package com.armikom.sis.exam;

import com.armikom.sis.common.ApiException;
import com.armikom.sis.common.ErrorCode;
import com.armikom.sis.common.PublicBaseUrl;
import com.armikom.sis.core.course.EnrollmentRepository;
import com.armikom.sis.core.course.RosterStudent;
import com.armikom.sis.core.mail.MailService;
import com.armikom.sis.exam.ExamDtos.NotifyRequest;
import com.armikom.sis.exam.ExamDtos.NotifyResultDto;
import com.armikom.sis.exam.ExamDtos.OptionDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * "Your results are published" mail to the students of an exam (11 §4.6): a note from the instructor, the score,
 * a link to the review page and, on request, the per-student report (every question with the given answer, the
 * key, right/wrong and points) in the mail body. Staff only; the exam must be published.
 */
@Service
public class ExamNotifyService {

    static final int MAX_MESSAGE = 4_000;
    static final int MAX_MAIL_TEXT = 600;

    private final ExamQueries queries;
    private final ExamQuestionGroupRepository groupRepo;
    private final EnrollmentRepository enrollmentRepo;
    private final ExamOptions options;
    private final MailService mail;
    private final PublicBaseUrl baseUrl;
    private final Clock clock;

    public ExamNotifyService(ExamQueries queries, ExamQuestionGroupRepository groupRepo,
                             EnrollmentRepository enrollmentRepo, ExamOptions options, MailService mail,
                             PublicBaseUrl baseUrl, Clock clock) {
        this.queries = queries;
        this.groupRepo = groupRepo;
        this.enrollmentRepo = enrollmentRepo;
        this.options = options;
        this.mail = mail;
        this.baseUrl = baseUrl;
        this.clock = clock;
    }

    @Transactional
    public NotifyResultDto notify(long examId, NotifyRequest req) {
        Exam exam = queries.requireExam(examId);
        if (!exam.isPublished()) throw new ApiException(ErrorCode.CONFLICT, Map.of("published", "Sınav yayınlanmadı"));
        String message = req.message() == null ? "" : req.message().strip();
        if (message.length() > MAX_MESSAGE) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("message", "En fazla " + MAX_MESSAGE + " karakter"));
        }
        boolean report = Boolean.TRUE.equals(req.includeReport());

        ExamQueries.Snapshot data = queries.load(List.of(exam));
        List<ExamQuestion> questions = data.questions(exam);
        Map<Long, String> labels = ExamLabels.labels(questions);
        Map<Long, ExamQuestionGroup> groups = groupRepo.findByExamId(examId).stream()
                .collect(Collectors.toMap(ExamQuestionGroup::getId, Function.identity()));
        Map<Long, List<OptionDto>> opts = options.of(questions.stream().map(ExamQuestion::getId).toList());
        Map<String, RosterStudent> roster = enrollmentRepo.roster(exam.getCourse().getId()).stream()
                .collect(Collectors.toMap(RosterStudent::studentNo, Function.identity(), (a, b) -> a));
        BigDecimal max = ExamQueries.examTotal(questions);
        String link = baseUrl.forMailLinks() + "/courses/" + exam.getCourse().getId() + "/results/" + exam.getId();
        String course = exam.getCourse().getCode() + " " + exam.getCourse().getName();
        String subject = exam.getCourse().getCode() + " " + exam.getTitle() + " — sonuçlarınız / your results";

        int sent = 0, withoutEmail = 0;
        for (ExamResult r : data.results(exam)) {
            RosterStudent s = roster.get(r.getStudentNo());
            String email = s == null ? null : s.email();
            if (email == null || email.isBlank()) {
                withoutEmail++;
                continue;
            }
            List<ExamAnswer> answers = data.answers(r);
            BigDecimal total = ExamQueries.resultTotal(answers);
            String name = s.fullName() != null && !s.fullName().isBlank() ? s.fullName() : r.getFullName();
            StringBuilder text = new StringBuilder();
            StringBuilder html = new StringBuilder();
            head(text, html, name, course, exam, total, max, message, link);
            if (report) ExamReportMail.append(text, html, questions, labels, groups, opts, answers);
            foot(text, html);
            mail.sendHtmlAsync(email, subject, text.toString(), html.toString());
            sent++;
        }
        Instant now = clock.instant();
        exam.setNotifiedAt(now);
        exam.setNotifiedCount(sent);
        return new NotifyResultDto(sent, withoutEmail, data.results(exam).size(), now.toString());
    }

    private static void head(StringBuilder text, StringBuilder html, String name, String course, Exam exam,
                             BigDecimal total, BigDecimal max, String message, String link) {
        String score = ExamMath.normalize(total).toPlainString() + " / " + ExamMath.normalize(max).toPlainString();
        text.append("Sayın ").append(name).append(",\n\n")
            .append(course).append(" — ").append(exam.getTitle())
            .append(exam.getExamDate() != null ? " (" + exam.getExamDate() + ")" : "").append(" sonuçlarınız açıklandı.\n")
            .append("Puanınız: ").append(score).append("\n\n");
        if (!message.isEmpty()) text.append(message).append("\n\n");
        text.append("Ayrıntılı değerlendirme (cevaplarınız, doğru cevaplar, açıklamalar): ").append(link).append("\n");
        html.append("<!doctype html><html><body style=\"font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#222\">")
            .append("<p>Sayın ").append(esc(name)).append(",</p>")
            .append("<p><b>").append(esc(course)).append(" — ").append(esc(exam.getTitle())).append("</b>")
            .append(exam.getExamDate() != null ? " (" + exam.getExamDate() + ")" : "").append(" sonuçlarınız açıklandı.<br>")
            .append("Puanınız: <b>").append(esc(score)).append("</b></p>");
        if (!message.isEmpty()) html.append("<p>").append(esc(message).replace("\n", "<br>")).append("</p>");
        html.append("<p><a href=\"").append(esc(link)).append("\">Ayrıntılı değerlendirme</a> (cevaplarınız, doğru cevaplar, açıklamalar)</p>");
    }

    private static void foot(StringBuilder text, StringBuilder html) {
        text.append("\nBu ileti SIS tarafından gönderildi; yanıtlamayın.\n");
        html.append("<p style=\"color:#777;font-size:12px\">Bu ileti SIS tarafından gönderildi; yanıtlamayın.</p></body></html>");
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
