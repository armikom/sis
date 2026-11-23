# MCP tools — attendance (yoklama)

Staff tools of the MCP server for attendance sessions and records (`McpAttendanceTools`, `@Order(4)`, audience
INSTRUCTOR). They call `SessionService`, `AttendanceService`, `CourseStudentController` and `CsvExportService`, so
the web rules (staff only, roster-only manual attendance, state checks) stay in one place. Every schema has the
optional `course` argument; a `session_id` / `attendance_id` finds its course itself and checks that the caller is
staff there ("Only the staff of … can use …").

### Staff (I) — attendance

| Tool | Arguments | Notes |
|---|---|---|
| `list_sessions` | `status?` (`DRAFT`/`OPEN`/`CLOSED`) | Session summaries of the course, newest first: status, `closesAt`, `attendanceCount`, `flaggedPendingCount` |
| `get_session` | `session_id` | Session detail as `GET /api/sessions/{id}` (settings, thresholds, times, `joinUrl` when not DRAFT). Never the session key or display config |
| `create_session` | `title`, `duration_minutes?`, `classroom?` (id or exact name), `required_scans?`, `geo_mode?`, `ble_mode?`, `frame_capture?` | Creates a DRAFT session (not open). Ask first. Unset settings take the site defaults; `classroom` is looked up among classrooms owned by the caller or any staff of the course |
| `open_session` | `session_id` | DRAFT → OPEN. The QR display stays on the web site |
| `extend_session` | `session_id`, `minutes` | OPEN only (1–240) |
| `close_session` | `session_id` | OPEN → CLOSED |
| `reopen_session` | `session_id`, `minutes?` (default 15) | CLOSED → OPEN with a new code |
| `session_attendances` | `session_id`, `only?` (`PENDING`/`PRESENT`/`REJECTED`) | `AttendanceDto` list, newest first; staff see flags, latency, thresholds, geo, BLE, device hint, note |
| `decide_attendance` | `attendance_id`, `decision` (`APPROVED`/`REJECTED`/`NONE`; `APPROVE`/`REJECT` accepted), `note?` | Teacher decision; ask before rejecting |
| `add_attendance` | `session_id`, `student_numbers` (list), `note?` | Bulk manual attendance (MANUAL, approved). Result `{added, skipped, errors, notInRoster}`; only roster students are recorded. Ask first |
| `remove_attendance` | `attendance_id` | Deletes the record and its frame. `destructiveHint: true`; ask first |
| `rejected_attempts` | `session_id`, `state?` (`SCANNING`/`COMPLETED`/`REJECTED`/`EXPIRED`, default `REJECTED`) | Check-in attempts: `studentNo`, `resultCode`, `deviceHint`, `conflictStudentNo`, `createdAt` |
| `attendance_report` | — | `sessions` (opened sessions: id, title, opened_at, status) and `students`: one row per roster student with `student_no`, `full_name`, `sessions`, `present`, `pending`, `rejected`, `absent`, `present_pct` (null without sessions) |
| `student_attendance` | `student` (number, e-mail, id or part of the name) | `GET /api/courses/{id}/students/{personId}` result: profile + one row per opened session (PRESENT/PENDING/REJECTED/ABSENT). Several matches → `{message, matches}` |
| `session_export` | `session_id` | `{filename, csv}`: the same CSV as `GET /api/sessions/{id}/export.csv` (BOM, `;`, Turkish headers, roster students who did not attend) |

Notes

- State rules: PRESENT = teacher approved, or accepted without a decision; PENDING = flagged without a decision;
  REJECTED = teacher rejected; ABSENT = no record in an opened session (same as the staff student page).
- `joinUrl` uses the configured public base URL (`app.public-base-url`), not the request host, since an MCP client
  may talk to the server through any hostname.
- Errors come back as tool errors (`isError: true`): state conflicts of the session service (`CONFLICT: …`),
  unknown ids ("Session not found."), bad enum values, and the audience check. A student token does not see these
  tools (`-32602 Unknown tool`).
- Not available by MCP on purpose: the session key / display config (`display-config`), frame images, deleting
  sessions, calibration runs, re-evaluation of thresholds.
