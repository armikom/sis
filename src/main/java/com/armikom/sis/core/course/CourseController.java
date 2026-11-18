package com.armikom.sis.core.course;

import com.armikom.sis.core.auth.CourseAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** Courses, their roster (students) and their staff. */
@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseService courses;
    private final RosterService roster;
    private final StaffService staff;
    private final CourseAccess access;

    public CourseController(CourseService courses, RosterService roster, StaffService staff, CourseAccess access) {
        this.courses = courses;
        this.roster = roster;
        this.staff = staff;
        this.access = access;
    }

    /**
     * {@code myRole} is null for a system admin who is not enrolled. {@code staff}: the caller may manage the course.
     * Settings that only matter to staff ({@code aiContext}) are null for students.
     */
    public record CourseDto(long id, String code, String name, String term, String language,
                            boolean attendanceEnabled, boolean poolEnabled, int questionsPerChapter, int askAiPerDay,
                            String aiContext, boolean archived, String archivedAt, String createdAt,
                            long rosterCount, String myRole, boolean staff, boolean owner) {}

    public record CourseRequest(@Size(max = 32) String code, @Size(max = 200) String name, @Size(max = 16) String term,
                                String language, Boolean attendanceEnabled, Boolean poolEnabled,
                                Integer questionsPerChapter, Integer askAiPerDay, String aiContext) {
        CourseService.CourseData toData() {
            return new CourseService.CourseData(code, name, term, language, attendanceEnabled, poolEnabled,
                    questionsPerChapter, askAiPerDay, aiContext);
        }
    }

    public record RosterEntryDto(long personId, String studentNo, String fullName, String email) {
        static RosterEntryDto from(RosterStudent s) {
            return new RosterEntryDto(s.personId(), s.studentNo(), s.fullName(), s.email());
        }
    }

    public record RosterEntryRequest(String studentNo, String fullName, String email) {}
    public record RosterImportRequest(String text, String mode) {}
    public record StaffRequest(String email, String fullName, String role) {}

    @GetMapping
    public List<CourseDto> list() {
        boolean admin = access.isSystemAdmin();
        return courses.listMine().stream().map(v -> dto(v, admin)).toList();
    }

    @GetMapping("/{id}")
    public CourseDto get(@PathVariable long id) {
        return dto(courses.get(id), access.isSystemAdmin());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CourseDto create(@Valid @RequestBody CourseRequest req) {
        return dto(courses.create(req.toData()), access.isSystemAdmin());
    }

    @PutMapping("/{id}")
    public CourseDto update(@PathVariable long id, @Valid @RequestBody CourseRequest req) {
        return dto(courses.update(id, req.toData()), access.isSystemAdmin());
    }

    @PostMapping("/{id}/archive")
    public CourseDto archive(@PathVariable long id) {
        return dto(courses.setArchived(id, true), access.isSystemAdmin());
    }

    @PostMapping("/{id}/unarchive")
    public CourseDto unarchive(@PathVariable long id) {
        return dto(courses.setArchived(id, false), access.isSystemAdmin());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        courses.delete(id);
    }

    // ---- roster -------------------------------------------------------------------------------

    @GetMapping("/{id}/roster")
    public List<RosterEntryDto> roster(@PathVariable long id) {
        return roster.list(id).stream().map(RosterEntryDto::from).toList();
    }

    @PostMapping("/{id}/roster")
    @ResponseStatus(HttpStatus.CREATED)
    public RosterEntryDto addRoster(@PathVariable long id, @RequestBody RosterEntryRequest req) {
        return RosterEntryDto.from(roster.add(id, req.studentNo(), req.fullName(), req.email()));
    }

    /** Pasted text: {@code {"text": "...", "mode": "MERGE|REPLACE"}}. */
    @PostMapping(value = "/{id}/roster/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RosterService.ImportResult importRosterText(@PathVariable long id, @RequestBody RosterImportRequest req) {
        return roster.importText(id, req.text(), req.mode());
    }

    /** Uploaded CSV file (multipart field {@code file}, query/form parameter {@code mode}). */
    @PostMapping(value = "/{id}/roster/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RosterService.ImportResult importRosterFile(@PathVariable long id,
                                                       @RequestParam(defaultValue = "MERGE") String mode,
                                                       @RequestParam("file") MultipartFile file) throws IOException {
        return roster.importStream(id, file.getInputStream(), mode);
    }

    @DeleteMapping("/{id}/roster/{personId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeRoster(@PathVariable long id, @PathVariable long personId) {
        roster.remove(id, personId);
    }

    // ---- staff --------------------------------------------------------------------------------

    @GetMapping("/{id}/staff")
    public List<StaffService.StaffMember> staff(@PathVariable long id) {
        return staff.list(id);
    }

    @PostMapping("/{id}/staff")
    @ResponseStatus(HttpStatus.CREATED)
    public StaffService.StaffMember addStaff(@PathVariable long id, @RequestBody StaffRequest req) {
        return staff.add(id, req.email(), req.fullName(), req.role());
    }

    @DeleteMapping("/{id}/staff/{personId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeStaff(@PathVariable long id, @PathVariable long personId) {
        staff.remove(id, personId);
    }

    private static CourseDto dto(CourseService.CourseView v, boolean admin) {
        Course c = v.course();
        boolean isStaff = admin || (v.myRole() != null && v.myRole().isStaff());
        boolean isOwner = admin || v.myRole() == EnrollmentRole.OWNER;
        return new CourseDto(c.getId(), c.getCode(), c.getName(), c.getTerm(), c.getLanguage(),
                c.isAttendanceEnabled(), c.isPoolEnabled(), c.getQuestionsPerChapter(), c.getAskAiPerDay(),
                isStaff ? c.getAiContext() : null, c.isArchived(),
                c.getArchivedAt() != null ? c.getArchivedAt().toString() : null, c.getCreatedAt().toString(),
                isStaff ? v.rosterCount() : 0, v.myRole() != null ? v.myRole().name() : null, isStaff, isOwner);
    }
}
