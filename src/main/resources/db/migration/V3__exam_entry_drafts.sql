-- Unsaved answer sheets of the staff answer-entry screen (11 §4.2). Not results: never shown to students, not used by
-- scoring, analysis or exports. The sheet is the client's draft object (cells as typed, active question, base signature).
create table exam_entry_draft (
    exam_id     bigint      not null references exam(id) on delete cascade,
    student_no  varchar(32) not null,
    person_id   bigint      not null references person(id),   -- staff member who wrote it last
    sheet       jsonb       not null,
    updated_at  timestamptz not null,
    primary key (exam_id, student_no)
);
