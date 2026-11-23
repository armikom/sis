# MCP exam tools (`McpExamTools`)

Staff tools (`I`) for reading and editing exams, their results and the analysis through the MCP server
(`docs/MCP.md`). They live in `com.armikom.sis.agent.McpExamTools` (`@Order(3)`) and call `ExamService` /
`ExamAnalysisService`, the same code as the exam screens, so every rule applies in one place: roster-only results,
label sets, consecutive groups, rescoring on a key change, answers dropped on a type change.

All take `course?` (not repeated below); tools addressed by `exam_id` or `question_id` take the course from that row.
`question_id` is always the **exam question** id (from `get_exam`), not a pool question id.

## Tools

| Tool | Arguments | Notes |
|---|---|---|
| `list_exams` | — | `ExamSummaryDto` per exam: id, title, date, forms, question count, total points, results, average, published |
| `get_exam` | `exam_id` | Full `ExamDto`: groups and every question with id, position, printed `label`, type, text, html, topic, points, `choiceLabels`, key, options with rationales, explanation, `topicIds` + effective `topics` |
| `create_exam` | `title`, `exam_date?` (YYYY-MM-DD), `forms?` (1-4), `seed?`, `questions` (list of question objects), `groups?` (`{ref, text?, html?}`) | Exam from scratch; questions stored in the order sent; a group's questions must be consecutive. Ask first. |
| `update_exam` | `exam_id`, `title` + the fields of `create_exam`; questions carry `id` to be kept; groups `{id?, ref, text?, html?}` | **Replaces the whole exam** like `PUT /api/exams/{id}`: a question left out is deleted with every answer to it; a group not sent back (with `id` + `ref`, and its questions with that `group_ref`) is dropped. `exam_date`/`forms`/`seed` not sent keep their value. Prefer the per-question tools. |
| `add_exam_question` | `exam_id` + one question's fields, `position?` (1-based, default last) | Reads the exam, keeps everything, adds the question. `group_ref` `g<groupId>` (the groups of `get_exam`) puts it in an existing group. |
| `update_exam_question` | `question_id` + any question fields | Patch: only sent fields change. Notes for students: `explanation`, `html`, `options[].rationale`. Key/points/label change rescores the stored answers; a type change drops them. |
| `remove_exam_question` | `question_id` | `destructiveHint: true`. Deletes the question and all answers to it; ask the staff member first. |
| `set_exam_question_topics` | `question_id`, `topic_ids` | Replaces the explicit topics (`[]` clears); ids from `list_topics`. The pool question's topics are inherited in addition. |
| `exam_results` | `exam_id` | `ExamResultDto` per student: number, name, form, note, total, graded count, `answers[]` (`questionId`, `answer`, `points`) |
| `save_exam_result` | `exam_id`, `student_no`, `answers?` (`{question_id, answer?, points?}`), `note?`, `form?` | Upsert of one sheet. Roster students only (clear error otherwise). CHOICE scored from the key; OPEN/FREE need `points`. Answers not sent are removed from the sheet. |
| `exam_analysis` | `exam_id` | `ExamAnalysisDto`: average/median/min/max, per question success rate and choice counts, per topic, per student with weak topics |

### Question object

`type` (`CHOICE`, `OPEN`, `FREE`) and `max_points` are required for new questions; everything else optional:
`text`, `html`, `topic` (free-text label), `choice_labels` (`"ABCD"`, `"DY"`, `"TF"`; 2-10 distinct characters) or
`choice_count` (A.. of that many), `correct_choice`, `pool_question_id`, `topic_ids`, `explanation`,
`options` (`{letter, text?, rationale?}`), `group_ref`, `sub_label`; `id` only in `update_exam`.

## Notes

- **Argument names are snake_case** (MCP convention here); camelCase aliases (`maxPoints`, `questionId`, …) are
  accepted too. **Results are the exam module's camelCase DTOs** (`ExamDto`, `ExamResultDto`, `ExamAnalysisDto`)
  unchanged, as `publish_exam` and `my_exam_results` already return them — the web page and the agent see the same
  data.
- **Replace semantics.** `ExamService.create/update` take the whole exam. `update_exam` exposes that as is; the
  per-question tools read the exam, round-trip it through `toRequest(ExamDto)` (groups with their ids as ref
  `g<id>`, questions with ids, topics, options, explanation and html) and send it back with one change, so nothing
  else is touched. `explanation`/`html` stored as null are sent back as `""` (clears = stays empty).
- `create_exam_from_pool` (in `McpTeacherTools`) draws approved pool questions into a new exam; its result's `id`
  is an exam that `get_exam` shows and these tools can edit. The pool-based `list_exams` / `get_exam` were replaced
  by the ones above.
- Validation errors from the service (`ApiException` `VALIDATION_ERROR`) reach the agent with the field details
  (`questions[2].groupRef`, `answers[0].answer`, …); a student number that is not on the roster gets an English
  message pointing to `course_roster` / `add_student`.
- Not available by MCP on purpose: deleting an exam, mailing results (`notify`), drafts, CSV export.
