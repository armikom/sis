package com.armikom.sis.exam;

import com.armikom.sis.exam.ExamDtos.*;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ExamController {

    private final ExamService service;
    private final ExamAnalysisService analysis;
    private final ExamDraftService drafts;

    public ExamController(ExamService service, ExamAnalysisService analysis, ExamDraftService drafts) {
        this.service = service;
        this.drafts = drafts;
        this.analysis = analysis;
    }

    @GetMapping("/courses/{courseId}/exams")
    public List<ExamSummaryDto> list(@PathVariable long courseId) {
        return service.list(courseId);
    }

    @PostMapping("/courses/{courseId}/exams")
    @ResponseStatus(HttpStatus.CREATED)
    public ExamDto create(@PathVariable long courseId, @RequestBody ExamRequest req) {
        return service.create(courseId, req);
    }

    @GetMapping("/exams/{id}")
    public ExamDto get(@PathVariable long id) {
        return service.get(id);
    }

    @PutMapping("/exams/{id}")
    public ExamDto update(@PathVariable long id, @RequestBody ExamRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/exams/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    @PostMapping("/exams/{id}/publish")
    public ExamDto publish(@PathVariable long id) {
        return service.setPublished(id, true);
    }

    @PostMapping("/exams/{id}/unpublish")
    public ExamDto unpublish(@PathVariable long id) {
        return service.setPublished(id, false);
    }

    @GetMapping("/exams/{id}/results")
    public List<ExamResultDto> results(@PathVariable long id) {
        return service.results(id);
    }

    @PutMapping("/exams/{id}/results/{studentNo}")
    public ExamResultDto upsertResult(@PathVariable long id, @PathVariable String studentNo,
                                      @RequestBody ExamResultRequest req) {
        return service.upsertResult(id, studentNo, req);
    }

    @DeleteMapping("/exams/{id}/results/{studentNo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteResult(@PathVariable long id, @PathVariable String studentNo) {
        service.deleteResult(id, studentNo);
    }

    /** Unsaved answer-entry sheets (staff only; never results). */
    @GetMapping("/exams/{id}/drafts")
    public List<ExamDraftService.DraftDto> drafts(@PathVariable long id) {
        return drafts.list(id);
    }

    @PutMapping("/exams/{id}/drafts/{studentNo}")
    public ExamDraftService.DraftSavedDto saveDraft(@PathVariable long id, @PathVariable String studentNo,
                                                    @RequestBody JsonNode sheet) {
        return drafts.save(id, studentNo, sheet);
    }

    @DeleteMapping("/exams/{id}/drafts/{studentNo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDraft(@PathVariable long id, @PathVariable String studentNo) {
        drafts.delete(id, studentNo);
    }

    @GetMapping("/exams/{id}/analysis")
    public ExamAnalysisDto analysis(@PathVariable long id) {
        return analysis.exam(id);
    }

    @GetMapping("/exams/{id}/results.csv")
    public ResponseEntity<byte[]> resultsCsv(@PathVariable long id) {
        ExamService.CsvExport export = service.csv(id);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(export.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(export.content().getBytes(StandardCharsets.UTF_8));
    }

    /** Signed-in student: own exam totals in the course. */
    @GetMapping("/courses/{courseId}/my-results")
    public List<MyResultDto> myResults(@PathVariable long courseId) {
        return service.myResults(courseId);
    }

    /** Signed-in student: one published exam in detail, with the topics to review. */
    @GetMapping("/courses/{courseId}/my-results/{examId}")
    public MyResultDetailDto myResult(@PathVariable long courseId, @PathVariable long examId) {
        return service.myResult(courseId, examId);
    }

    @GetMapping("/courses/{courseId}/learning")
    public CourseLearningDto courseLearning(@PathVariable long courseId) {
        return analysis.course(courseId);
    }

    @GetMapping("/courses/{courseId}/learning/students/{studentNo}")
    public StudentLearningDto studentLearning(@PathVariable long courseId, @PathVariable String studentNo) {
        return analysis.student(courseId, studentNo);
    }
}
