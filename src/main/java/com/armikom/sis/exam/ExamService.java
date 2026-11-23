package com.armikom.sis.exam;

import com.armikom.sis.common.ApiException;
import com.armikom.sis.common.ErrorCode;
import com.armikom.sis.common.Strings;
import com.armikom.sis.config.AppProperties;
import com.armikom.sis.core.auth.CourseAccess;
import com.armikom.sis.core.course.Course;
import com.armikom.sis.core.course.EnrollmentRepository;
import com.armikom.sis.core.course.RosterStudent;
import com.armikom.sis.exam.ExamDtos.*;
import com.armikom.sis.export.CsvExportService;
import com.armikom.sis.pool.Topics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Exam definitions, result entry and scoring (11 §3.3). */
@Service
public class ExamService {

    static final int MAX_QUESTIONS = 200;
    static final BigDecimal MAX_QUESTION_POINTS = new BigDecimal("1000");
    static final int MAX_TEXT = 20_000;
    static final int MAX_OPTION_TEXT = 5_000;

    private final ExamRepository examRepo;
    private final ExamQuestionRepository questionRepo;
    private final ExamResultRepository resultRepo;
    private final ExamAnswerRepository answerRepo;
    private final EnrollmentRepository enrollmentRepo;
    private final CourseAccess access;
    private final ExamQueries queries;
    private final Clock clock;
    private final Topics topics;
    private final ExamOptions options;
    private final ExamDraftService drafts;
    private final Pattern studentNoPattern;

    public ExamService(ExamRepository examRepo, ExamQuestionRepository questionRepo, ExamResultRepository resultRepo,
                       ExamAnswerRepository answerRepo, EnrollmentRepository enrollmentRepo, CourseAccess access,
                       ExamQueries queries, Clock clock, AppProperties props, Topics topics,
                       ExamOptions options, ExamDraftService drafts) {
        this.drafts = drafts;
        this.topics = topics;
        this.options = options;
        this.examRepo = examRepo;
        this.questionRepo = questionRepo;
        this.resultRepo = resultRepo;
        this.answerRepo = answerRepo;
        this.enrollmentRepo = enrollmentRepo;
        this.access = access;
        this.queries = queries;
        this.clock = clock;
        this.studentNoPattern = Pattern.compile(props.checkin().studentNoPattern());
    }

    // ---- definitions ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ExamSummaryDto> list(long courseId) {
        access.requireStaff(courseId);
        List<Exam> exams = queries.examsOfCourse(courseId);
        ExamQueries.Snapshot data = queries.load(exams);
        return exams.stream().map(e -> ExamQueries.summary(e, data)).toList();
    }

    @Transactional(readOnly = true)
    public ExamDto get(long id) {
        return toDto(queries.requireExam(id));
    }

    @Transactional
    public ExamDto create(long courseId, ExamRequest req) {
        Course course = access.requireStaff(courseId);
        ValidExam valid = validate(req, Set.of(), courseId);
        Exam exam = new Exam(course, valid.title(), valid.examDate(), clock.instant());
        if (valid.forms() != null) exam.setForms(valid.forms());
        exam.setSeed(req.seed());
        exam = examRepo.save(exam);
        int position = 1;
        for (QuestionSpec spec : valid.questions()) {
            ExamQuestion q = new ExamQuestion(exam);
            spec.applyTo(q, position++);
            q = questionRepo.save(q);
            if (spec.topicIds() != null) topics.setExamQuestionTopics(q.getId(), spec.topicIds());
            if (spec.options() != null) options.replace(q.getId(), spec.options());
        }
        return toDto(exam);
    }

    @Transactional
    public ExamDto update(long id, ExamRequest req) {
        Exam exam = queries.requireExam(id);
        Map<Long, ExamQuestion> existing = questionRepo.findByExamId(id).stream()
                .collect(Collectors.toMap(ExamQuestion::getId, Function.identity()));
        ValidExam valid = validate(req, existing.keySet(), exam.getCourse().getId());

        Set<Long> kept = valid.questions().stream().map(QuestionSpec::id).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<ExamQuestion> removed = existing.values().stream().filter(q -> !kept.contains(q.getId())).toList();

        // Classify changes before touching any entity: the bulk delete below flushes the context.
        Set<Long> dropAnswers = new HashSet<>();
        Set<Long> rescoreChoice = new HashSet<>();
        Set<Long> clipPoints = new HashSet<>();
        for (ExamQuestion q : removed) dropAnswers.add(q.getId());
        for (QuestionSpec spec : valid.questions()) {
            if (spec.id() == null) continue;
            ExamQuestion q = existing.get(spec.id());
            if (q.getType() != spec.type()) {
                dropAnswers.add(q.getId());
            } else if (spec.type() == QuestionType.CHOICE) {
                if (!Objects.equals(q.getCorrectChoice(), spec.correctChoice())
                        || q.getMaxPoints().compareTo(spec.maxPoints()) != 0
                        || !Objects.equals(q.getChoiceCount(), spec.choiceCount())) {
                    rescoreChoice.add(q.getId());
                }
            } else if (spec.maxPoints().compareTo(q.getMaxPoints()) < 0) {
                clipPoints.add(q.getId());
            }
        }
        if (!dropAnswers.isEmpty()) answerRepo.deleteByQuestionIds(dropAnswers);
        questionRepo.deleteAll(removed);

        exam.setTitle(valid.title());
        exam.setExamDate(valid.examDate());
        if (valid.forms() != null) exam.setForms(valid.forms());
        if (req.seed() != null) exam.setSeed(req.seed());
        int position = 1;
        for (QuestionSpec spec : valid.questions()) {
            ExamQuestion q = spec.id() != null ? existing.get(spec.id()) : new ExamQuestion(exam);
            spec.applyTo(q, position++);
            if (spec.id() == null) q = questionRepo.save(q);
            if (spec.topicIds() != null) topics.setExamQuestionTopics(q.getId(), spec.topicIds());
            if (spec.options() != null) options.replace(q.getId(), spec.options());
        }

        Set<Long> touched = new HashSet<>(rescoreChoice);
        touched.addAll(clipPoints);
        if (!touched.isEmpty()) {
            for (ExamAnswer a : answerRepo.findByQuestionIds(touched)) {
                ExamQuestion q = existing.get(a.getQuestion().getId());
                if (rescoreChoice.contains(q.getId())) {
                    a.setPoints(ExamMath.choicePoints(a.getAnswerText(), q.getCorrectChoice(), q.getMaxPoints()));
                } else if (a.getPoints() != null && a.getPoints().compareTo(q.getMaxPoints()) > 0) {
                    a.setPoints(q.getMaxPoints());
                }
            }
        }
        return toDto(exam);
    }

    @Transactional
    public void delete(long id) {
        // Questions, results and answers go with it through ON DELETE CASCADE.
        examRepo.delete(queries.requireExam(id));
    }

    private ExamDto toDto(Exam exam) {
        List<ExamQuestion> questions = questionRepo.findByExamId(exam.getId());
        List<Long> ids = questions.stream().map(ExamQuestion::getId).toList();
        Map<Long, List<Long>> explicit = topics.explicitOfExamQuestions(ids);
        Map<Long, List<Topics.Ref>> effective = topics.effectiveOfExamQuestions(ids);
        Map<Long, List<OptionDto>> opts = options.of(ids);
        Course c = exam.getCourse();
        return new ExamDto(exam.getId(), c.getId(), c.getCode(), c.getName(), exam.getTitle(), exam.getExamDate(),
                exam.getForms(), exam.getSeed(), ExamQueries.examTotal(questions), resultRepo.countByExam_Id(exam.getId()),
                exam.getCreatedAt().toString(), exam.isPublished(),
                questions.stream().map(q -> new QuestionDto(q.getId(), q.getPosition(), q.getType(), q.getText(),
                        q.getTopic(), ExamMath.normalize(q.getMaxPoints()), q.getChoiceCount(), q.getCorrectChoice(),
                        q.getPoolQuestionId(), explicit.getOrDefault(q.getId(), List.of()),
                        topicDtos(effective.get(q.getId())), q.getExplanation(),
                        opts.getOrDefault(q.getId(), List.of()))).toList());
    }

    static TopicDto topicDto(Topics.Ref t) {
        return new TopicDto(t.id(), t.chapterId(), t.chapterNumber(), t.chapterTitle(), t.number(), t.titleEn(),
                t.titleTr());
    }

    static List<TopicDto> topicDtos(List<Topics.Ref> refs) {
        return refs == null ? List.of() : refs.stream().map(ExamService::topicDto).toList();
    }

    /** Publishes or unpublishes the results of an exam to its students (course staff). */
    @Transactional
    public ExamDto setPublished(long id, boolean published) {
        Exam exam = queries.requireExam(id);
        exam.setPublished(published);
        return toDto(exam);
    }

    // ---- definition validation -----------------------------------------------------------------

    private record ValidExam(String title, LocalDate examDate, List<QuestionSpec> questions, Integer forms) {}

    private record QuestionSpec(Long id, QuestionType type, String text, String topic, BigDecimal maxPoints,
                                Integer choiceCount, String correctChoice, Long poolQuestionId, List<Long> topicIds,
                                String explanation, List<OptionDto> options) {
        void applyTo(ExamQuestion q, int position) {
            q.setPosition(position);
            if (explanation != null) q.setExplanation(explanation.isEmpty() ? null : explanation);
            q.setType(type);
            q.setText(text);
            q.setTopic(topic);
            q.setMaxPoints(maxPoints);
            q.setChoiceCount(choiceCount);
            q.setCorrectChoice(correctChoice);
            q.setPoolQuestionId(poolQuestionId);
        }
    }

    private ValidExam validate(ExamRequest req, Set<Long> ownQuestionIds, long courseId) {
        if (req == null) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        Map<String, Object> errors = new LinkedHashMap<>();
        String title = req.title() == null ? "" : req.title().trim();
        if (title.isEmpty()) errors.put("title", "Başlık zorunlu");
        else if (title.length() > 200) errors.put("title", "En fazla 200 karakter");
        if (req.forms() != null && (req.forms() < 1 || req.forms() > 4)) errors.put("forms", "1 ile 4 arasında olmalı");

        List<QuestionRequest> items = req.questions() == null ? List.of() : req.questions();
        if (items.size() > MAX_QUESTIONS) {
            errors.put("questions", "En fazla " + MAX_QUESTIONS + " soru eklenebilir");
            throw new ApiException(ErrorCode.VALIDATION_ERROR, errors);
        }
        List<QuestionSpec> specs = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            String k = "questions[" + i + "].";
            QuestionRequest q = items.get(i);
            if (q == null) {
                errors.put("questions[" + i + "]", "Soru boş olamaz");
                continue;
            }
            if (q.id() != null && (!ownQuestionIds.contains(q.id()) || !seen.add(q.id()))) {
                errors.put(k + "id", "Soru bu sınava ait değil");
            }
            if (q.type() == null) errors.put(k + "type", "Soru türü zorunlu");
            BigDecimal max = ExamMath.toColumnScale(q.maxPoints());
            if (max == null || max.signum() <= 0 || max.compareTo(MAX_QUESTION_POINTS) > 0) {
                errors.put(k + "maxPoints", "Puan 0'dan büyük ve en fazla 1000 olmalı");
            }
            String text = q.text() == null || q.text().isBlank() ? null : q.text();
            if (text != null && text.length() > MAX_TEXT) errors.put(k + "text", "En fazla " + MAX_TEXT + " karakter");
            String explanation = q.explanation() == null ? null : q.explanation().strip();
            if (explanation != null && explanation.length() > MAX_TEXT) {
                errors.put(k + "explanation", "En fazla " + MAX_TEXT + " karakter");
            }
            String topic = q.topic() == null || q.topic().isBlank() ? null : q.topic().trim();
            if (topic != null && topic.length() > 120) errors.put(k + "topic", "En fazla 120 karakter");

            Integer choiceCount = null;
            String correct = null;
            if (q.type() == QuestionType.CHOICE) {
                choiceCount = q.choiceCount();
                if (choiceCount == null || choiceCount < 2 || choiceCount > 6) {
                    errors.put(k + "choiceCount", "Seçenek sayısı 2 ile 6 arasında olmalı");
                } else if (q.correctChoice() != null && !q.correctChoice().isBlank()) {
                    correct = choiceLetter(q.correctChoice(), choiceCount);
                    if (correct == null) errors.put(k + "correctChoice", "Doğru cevap A-" + ExamMath.letter(choiceCount) + " aralığında olmalı");
                }
            }
            List<Long> topicIds = null;
            if (q.topicIds() != null) {
                if (q.topicIds().contains(null) || !topics.allInCourse(courseId, q.topicIds())) {
                    errors.put(k + "topicIds", "Konu bu derse ait değil");
                } else {
                    topicIds = List.copyOf(new LinkedHashSet<>(q.topicIds()));
                }
            }
            List<OptionDto> opts = validOptions(q, choiceCount, k, errors);
            specs.add(new QuestionSpec(q.id(), q.type(), text, topic, max, choiceCount, correct, q.poolQuestionId(),
                    topicIds, explanation, opts));
        }
        if (!errors.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR, errors);
        return new ValidExam(title, req.examDate(), specs, req.forms());
    }

    /**
     * Options of one question request: null = unchanged; for CHOICE letters within A..choiceCount, each once, text up
     * to {@link #MAX_OPTION_TEXT}; OPEN/FREE accept only an empty list. Sorted by letter.
     */
    private static List<OptionDto> validOptions(QuestionRequest q, Integer choiceCount, String k,
                                                Map<String, Object> errors) {
        if (q.options() == null) return null;
        if (q.type() != QuestionType.CHOICE) {
            if (!q.options().isEmpty()) errors.put(k + "options", "Seçenekler yalnız çoktan seçmeli sorularda olur");
            return List.of();
        }
        List<OptionDto> out = new ArrayList<>();
        Set<String> letters = new HashSet<>();
        for (int j = 0; j < q.options().size(); j++) {
            OptionDto o = q.options().get(j);
            String key = k + "options[" + j + "].";
            String letter = o == null || o.letter() == null || choiceCount == null ? null
                    : choiceLetter(o.letter(), choiceCount);
            if (letter == null || !letters.add(letter)) {
                errors.put(key + "letter", "Harf seçenek sayısı içinde olmalı ve tekrar etmemeli");
                continue;
            }
            String text = o.text() == null ? "" : o.text().strip();
            String rationale = o.rationale() == null || o.rationale().isBlank() ? null : o.rationale().strip();
            if (text.length() > MAX_OPTION_TEXT) errors.put(key + "text", "En fazla " + MAX_OPTION_TEXT + " karakter");
            if (rationale != null && rationale.length() > MAX_OPTION_TEXT) {
                errors.put(key + "rationale", "En fazla " + MAX_OPTION_TEXT + " karakter");
            }
            out.add(new OptionDto(letter, text, rationale));
        }
        out.sort(Comparator.comparing(OptionDto::letter));
        return out;
    }

    /** Uppercased single letter within A..(choiceCount-th letter), or null when invalid. */
    private static String choiceLetter(String raw, int choiceCount) {
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.length() != 1) return null;
        char c = s.charAt(0);
        return c >= 'A' && c < 'A' + choiceCount ? s : null;
    }

    // ---- results -------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ExamResultDto> results(long examId) {
        Exam exam = queries.requireExam(examId);
        ExamQueries.Snapshot data = queries.load(List.of(exam));
        List<ExamQuestion> questions = data.questions(exam);
        return data.results(exam).stream().map(r -> toDto(r, data.answers(r), questions)).toList();
    }

    @Transactional
    public ExamResultDto upsertResult(long examId, String rawStudentNo, ExamResultRequest req) {
        Exam exam = queries.requireExam(examId);
        String studentNo = rawStudentNo == null ? "" : rawStudentNo.trim();
        if (!studentNoPattern.matcher(studentNo).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("studentNo", "Geçersiz format"));
        }
        if (req == null) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        // Results are roster-only: the number must have a live STUDENT enrollment in the course.
        RosterStudent student = enrollmentRepo.rosterStudent(exam.getCourse().getId(), studentNo)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_IN_ROSTER));
        String form = validForm(req.form(), exam.getForms());
        List<ExamQuestion> questions = questionRepo.findByExamId(examId);
        Map<Long, AnswerValue> wanted = validateAnswers(req.answers(), questions);

        Instant now = clock.instant();
        ExamResult result = resultRepo.findByExam_IdAndStudentNo(examId, studentNo)
                .orElseGet(() -> resultRepo.save(new ExamResult(exam, student.personId(), studentNo, now)));
        result.setFullName(student.fullName() != null ? student.fullName() : "");
        result.setForm(form);
        result.setNote(Strings.clip(req.note(), 500));
        result.setUpdatedAt(now);

        // Update rows in place and delete only the ones that go away: inserting a replacement row
        // before Hibernate flushes the delete of the old one would violate uq_exam_answer.
        Map<Long, ExamQuestion> byId = questions.stream()
                .collect(Collectors.toMap(ExamQuestion::getId, Function.identity()));
        List<ExamAnswer> answers = new ArrayList<>();
        for (ExamAnswer a : result.getId() == null ? List.<ExamAnswer>of() : answerRepo.findByResultId(result.getId())) {
            AnswerValue v = wanted.remove(a.getQuestion().getId());
            if (v == null) {
                answerRepo.delete(a);
            } else {
                a.setAnswerText(v.answer());
                a.setPoints(v.points());
                answers.add(a);
            }
        }
        for (Map.Entry<Long, AnswerValue> e : wanted.entrySet()) {
            ExamAnswer a = new ExamAnswer(result, byId.get(e.getKey()));
            a.setAnswerText(e.getValue().answer());
            a.setPoints(e.getValue().points());
            answers.add(answerRepo.save(a));
        }
        drafts.remove(examId, studentNo);   // the sheet is saved: its draft is done
        return toDto(result, answers, questions);
    }

    @Transactional
    public void deleteResult(long examId, String studentNo) {
        queries.requireExam(examId);
        String no = studentNo == null ? "" : studentNo.trim();
        resultRepo.findByExam_IdAndStudentNo(examId, no).ifPresent(resultRepo::delete);
        drafts.remove(examId, no);
    }

    /** Upper-case form letter within A..(forms-th letter), null for none; 400 otherwise. */
    private static String validForm(String raw, int forms) {
        if (raw == null || raw.isBlank()) return null;
        String f = raw.trim().toUpperCase(Locale.ROOT);
        if (f.length() != 1 || f.charAt(0) < 'A' || f.charAt(0) >= 'A' + forms) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("form", "Form A-" + ExamMath.letter(forms) + " aralığında olmalı"));
        }
        return f;
    }

    private record AnswerValue(String answer, BigDecimal points) {}

    /** Normalised rows to store, keyed by question id; rows that are not stored are left out. */
    private Map<Long, AnswerValue> validateAnswers(List<AnswerRequest> items, List<ExamQuestion> questions) {
        Map<Long, ExamQuestion> byId = questions.stream()
                .collect(Collectors.toMap(ExamQuestion::getId, Function.identity()));
        Map<String, Object> errors = new LinkedHashMap<>();
        Map<Long, AnswerValue> out = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();
        List<AnswerRequest> list = items == null ? List.of() : items;
        for (int i = 0; i < list.size(); i++) {
            String k = "answers[" + i + "].";
            AnswerRequest a = list.get(i);
            ExamQuestion q = a == null || a.questionId() == null ? null : byId.get(a.questionId());
            if (q == null || !seen.add(q.getId())) {
                errors.put(k + "questionId", "Soru bu sınava ait değil ya da tekrar ediyor");
                continue;
            }
            switch (q.getType()) {
                case CHOICE -> {
                    String raw = a.answer() == null ? "" : a.answer().trim();
                    String letter = raw.isEmpty() ? null : choiceLetter(raw, q.getChoiceCount());
                    if (!raw.isEmpty() && letter == null) {
                        errors.put(k + "answer", "Cevap A-" + ExamMath.letter(q.getChoiceCount()) + " aralığında olmalı");
                    } else {
                        out.put(q.getId(), new AnswerValue(letter,
                                ExamMath.choicePoints(letter, q.getCorrectChoice(), q.getMaxPoints())));
                    }
                }
                case OPEN -> {
                    String text = a.answer() == null || a.answer().isBlank() ? null : a.answer().trim();
                    BigDecimal points = ExamMath.toColumnScale(a.points());
                    boolean ok = true;
                    if (text != null && text.length() > 2000) {
                        errors.put(k + "answer", "En fazla 2000 karakter");
                        ok = false;
                    }
                    if (!pointsInRange(points, q)) {
                        errors.put(k + "points", pointsMessage(q));
                        ok = false;
                    }
                    if (ok && (text != null || points != null)) out.put(q.getId(), new AnswerValue(text, points));
                }
                case FREE -> {
                    BigDecimal points = ExamMath.toColumnScale(a.points());
                    if (!pointsInRange(points, q)) errors.put(k + "points", pointsMessage(q));
                    else if (points != null) out.put(q.getId(), new AnswerValue(null, points));
                }
            }
        }
        if (!errors.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR, errors);
        return out;
    }

    private static boolean pointsInRange(BigDecimal points, ExamQuestion q) {
        return points == null || (points.signum() >= 0 && points.compareTo(q.getMaxPoints()) <= 0);
    }

    private static String pointsMessage(ExamQuestion q) {
        return "Puan 0 ile " + ExamMath.normalize(q.getMaxPoints()).toPlainString() + " arasında olmalı";
    }

    static ExamResultDto toDto(ExamResult r, List<ExamAnswer> answers, List<ExamQuestion> questions) {
        Map<Long, Integer> order = new HashMap<>();
        for (ExamQuestion q : questions) order.put(q.getId(), q.getPosition());
        List<AnswerDto> dtos = answers.stream()
                .sorted(Comparator.comparing(a -> order.getOrDefault(a.getQuestion().getId(), Integer.MAX_VALUE)))
                .map(a -> new AnswerDto(a.getQuestion().getId(), a.getAnswerText(), ExamMath.normalize(a.getPoints())))
                .toList();
        int graded = ExamQueries.gradedCount(answers);
        return new ExamResultDto(r.getId(), r.getPersonId(), r.getStudentNo(), r.getFullName(), r.getForm(), r.getNote(),
                ExamQueries.resultTotal(answers), graded, graded == questions.size(),
                r.getUpdatedAt().toString(), dtos);
    }

    // ---- student self-service ------------------------------------------------------------------

    /** The signed-in person's totals for every exam of the course that has a result for them. */
    @Transactional(readOnly = true)
    public List<MyResultDto> myResults(long courseId) {
        access.requireMember(courseId);
        long me = access.currentPerson().personId();
        List<Exam> exams = queries.examsOfCourse(courseId);
        ExamQueries.Snapshot data = queries.load(exams);
        List<MyResultDto> out = new ArrayList<>();
        for (Exam exam : exams) {
            if (!exam.isPublished()) continue;
            data.results(exam).stream().filter(r -> r.getPersonId() == me).findFirst().ifPresent(r ->
                    out.add(new MyResultDto(exam.getId(), exam.getTitle(), exam.getExamDate(),
                            ExamQueries.resultTotal(data.answers(r)), ExamQueries.examTotal(data.questions(exam)))));
        }
        return out;
    }

    /**
     * One published exam of the signed-in person with every question, their answer and the topics to review.
     * 404 unless the exam belongs to the course, is published and has a result for them.
     */
    @Transactional(readOnly = true)
    public MyResultDetailDto myResult(long courseId, long examId) {
        access.requireMember(courseId);
        long me = access.currentPerson().personId();
        Exam exam = examRepo.findById(examId).filter(e -> e.getCourse().getId() == courseId && e.isPublished())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        ExamQueries.Snapshot data = queries.load(List.of(exam));
        ExamResult result = data.results(exam).stream().filter(r -> r.getPersonId() == me).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        List<ExamQuestion> questions = data.questions(exam).stream()
                .sorted(Comparator.comparingInt(ExamQuestion::getPosition)).toList();
        Map<Long, ExamAnswer> answers = data.answers(result).stream()
                .collect(Collectors.toMap(a -> a.getQuestion().getId(), Function.identity()));
        Map<Long, List<Topics.Ref>> effective =
                topics.effectiveOfExamQuestions(questions.stream().map(ExamQuestion::getId).toList());
        Map<Long, List<OptionDto>> opts = options.of(questions.stream().map(ExamQuestion::getId).toList());

        List<MyQuestionDto> out = new ArrayList<>();
        ReviewTally tally = new ReviewTally();
        for (ExamQuestion q : questions) {
            ExamAnswer a = answers.get(q.getId());
            BigDecimal points = a == null ? null : a.getPoints();
            Boolean correct = q.getType() == QuestionType.CHOICE && q.getCorrectChoice() != null && a != null
                    ? q.getCorrectChoice().equals(a.getAnswerText()) : null;
            String myAnswer = a == null || q.getType() == QuestionType.FREE ? null : a.getAnswerText();
            List<Topics.Ref> refs = effective.getOrDefault(q.getId(), List.of());
            out.add(new MyQuestionDto(q.getPosition(), q.getText(), q.getType(), myAnswer, q.getCorrectChoice(),
                    correct, ExamMath.normalize(points), ExamMath.normalize(q.getMaxPoints()), topicDtos(refs),
                    opts.getOrDefault(q.getId(), List.of()), q.getExplanation(), questionStats(q, data, exam)));
            tally.add(refs, isMissed(points, q.getMaxPoints()));
        }
        List<BigDecimal> totals = data.results(exam).stream().map(r -> ExamQueries.resultTotal(data.answers(r)))
                .toList();
        ClassStatsDto stats = new ClassStatsDto(totals.size(), ExamMath.average(totals), ExamMath.median(totals),
                ExamMath.max(totals));
        return new MyResultDetailDto(exam.getId(), exam.getTitle(), exam.getExamDate(),
                ExamQueries.resultTotal(data.answers(result)), ExamQueries.examTotal(questions), stats, out,
                tally.result());
    }

    /** Aggregates of one question over every result of the exam (no per-student data). */
    private static QuestionStatsDto questionStats(ExamQuestion q, ExamQueries.Snapshot data, Exam exam) {
        int answered = 0;
        int scored = 0;
        int full = 0;
        BigDecimal sum = BigDecimal.ZERO;
        Map<String, Integer> choices = new LinkedHashMap<>();
        boolean choice = q.getType() == QuestionType.CHOICE && q.getChoiceCount() != null;
        if (choice) {
            for (int i = 1; i <= q.getChoiceCount(); i++) choices.put(ExamMath.letter(i), 0);
            choices.put("blank", 0);
        }
        for (ExamResult r : data.results(exam)) {
            for (ExamAnswer a : data.answers(r)) {
                if (!a.getQuestion().getId().equals(q.getId())) continue;
                answered++;
                if (choice) {
                    String key = a.getAnswerText() == null || a.getAnswerText().isBlank() ? "blank" : a.getAnswerText();
                    choices.computeIfPresent(key, (k, v) -> v + 1);
                }
                if (a.getPoints() != null) {
                    scored++;
                    sum = sum.add(a.getPoints());
                    if (a.getPoints().compareTo(q.getMaxPoints()) >= 0) full++;
                }
            }
        }
        BigDecimal share = scored == 0 ? null : ExamMath.normalize(
                BigDecimal.valueOf(full).divide(BigDecimal.valueOf(scored), 4, java.math.RoundingMode.HALF_UP));
        return new QuestionStatsDto(answered, share, ExamMath.mean(sum, scored), choices);
    }

    /**
     * Review topics of the signed-in person over every published exam of the course with a result for them
     * (same rules as {@link #myResult}); used by the MCP study tools.
     */
    @Transactional(readOnly = true)
    public List<ReviewTopicDto> myReviewTopics(long courseId) {
        access.requireMember(courseId);
        long me = access.currentPerson().personId();
        List<Exam> exams = queries.examsOfCourse(courseId).stream().filter(Exam::isPublished).toList();
        ExamQueries.Snapshot data = queries.load(exams);
        ReviewTally tally = new ReviewTally();
        for (Exam exam : exams) {
            ExamResult result = data.results(exam).stream().filter(r -> r.getPersonId() == me).findFirst()
                    .orElse(null);
            if (result == null) continue;
            List<ExamQuestion> questions = data.questions(exam);
            Map<Long, ExamAnswer> answers = data.answers(result).stream()
                    .collect(Collectors.toMap(a -> a.getQuestion().getId(), Function.identity()));
            Map<Long, List<Topics.Ref>> effective =
                    topics.effectiveOfExamQuestions(questions.stream().map(ExamQuestion::getId).toList());
            for (ExamQuestion q : questions) {
                ExamAnswer a = answers.get(q.getId());
                tally.add(effective.getOrDefault(q.getId(), List.of()),
                        isMissed(a == null ? null : a.getPoints(), q.getMaxPoints()));
            }
        }
        return tally.result();
    }

    /** Missed = graded and below the maximum; ungraded questions are not missed. */
    static boolean isMissed(BigDecimal points, BigDecimal maxPoints) {
        return points != null && points.compareTo(maxPoints) < 0;
    }

    /** Counts per effective topic; the result keeps topics with missed > 0, most missed first. */
    private static final class ReviewTally {
        private final Map<Long, Topics.Ref> refs = new LinkedHashMap<>();
        private final Map<Long, int[]> counts = new LinkedHashMap<>();

        void add(List<Topics.Ref> topics, boolean missed) {
            for (Topics.Ref t : topics) {
                refs.putIfAbsent(t.id(), t);
                int[] c = counts.computeIfAbsent(t.id(), k -> new int[2]);
                c[1]++;
                if (missed) c[0]++;
            }
        }

        List<ReviewTopicDto> result() {
            return counts.entrySet().stream().filter(e -> e.getValue()[0] > 0)
                    .map(e -> new ReviewTopicDto(topicDto(refs.get(e.getKey())), e.getValue()[0], e.getValue()[1]))
                    .sorted(Comparator.comparingInt(ReviewTopicDto::missed).reversed()
                            .thenComparingInt(r -> r.topic().chapterNumber())
                            .thenComparingInt(r -> r.topic().number()))
                    .toList();
        }
    }

    // ---- CSV (11 §3.5) -------------------------------------------------------------------------

    public record CsvExport(String filename, String content) {}

    @Transactional(readOnly = true)
    public CsvExport csv(long examId) {
        Exam exam = queries.requireExam(examId);
        ExamQueries.Snapshot data = queries.load(List.of(exam));
        List<ExamQuestion> questions = data.questions(exam);

        List<String> header = new ArrayList<>(List.of("OgrenciNo", "AdSoyad"));
        for (ExamQuestion q : questions) {
            header.add("S" + q.getPosition());
            header.add("S" + q.getPosition() + " Puan");
        }
        header.add("Toplam");
        StringBuilder sb = new StringBuilder(CsvExportService.BOM);
        row(sb, header);
        for (ExamResult r : data.results(exam)) {
            Map<Long, ExamAnswer> byQuestion = data.answers(r).stream()
                    .collect(Collectors.toMap(a -> a.getQuestion().getId(), Function.identity()));
            List<String> cells = new ArrayList<>(List.of(r.getStudentNo(), r.getFullName()));
            for (ExamQuestion q : questions) {
                ExamAnswer a = byQuestion.get(q.getId());
                cells.add(a == null || q.getType() == QuestionType.FREE ? "" : a.getAnswerText());
                cells.add(a == null ? "" : decimal(a.getPoints()));
            }
            cells.add(decimal(ExamQueries.resultTotal(data.answers(r))));
            row(sb, cells);
        }
        return new CsvExport("sinav_" + exam.getCourse().getCode() + "_" + exam.getId() + ".csv", sb.toString());
    }

    private static void row(StringBuilder sb, List<String> cells) {
        sb.append(cells.stream().map(CsvExportService::esc).collect(Collectors.joining(CsvExportService.SEP)))
                .append(CsvExportService.NL);
    }

    /** Decimal comma, no trailing zeros; empty for null. */
    static String decimal(BigDecimal v) {
        return v == null ? "" : ExamMath.normalize(v).toPlainString().replace('.', ',');
    }
}
