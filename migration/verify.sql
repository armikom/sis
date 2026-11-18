-- SIS migration verification: legacy_* schemas against the SIS tables.
-- Run after migrate.sql:   psql "$DB_URL" -v ON_ERROR_STOP=1 -f verify.sql
-- Prints one row per check (check_name | expected | actual | ok); raises an exception when any check fails.
-- Expected values are computed from the legacy schemas only (plus the skip list / staff map that
-- migrate.sql stored in legacy_report.config and legacy_report.staff_map).
-- Prints counts and hashes only, never personal data.

\set ON_ERROR_STOP on
\pset pager off

create temp table checks(n serial, check_name text, expected text, actual text);

create temp table kc as
select c.id from legacy_yoklama.course c, legacy_report.config cfg where c.code <> all (cfg.skip_course_codes);
create temp table qc as
select c.id from legacy_yoklama.course c, legacy_report.config cfg where c.code = cfg.qp_course_code;
create temp table skipu as
select u.id from legacy_qp.app_user u, legacy_report.config cfg
 where u.role = 'STUDENT' and lower(u.email) not like '%@' || cfg.student_email_domain
   and not exists (select 1 from legacy_qp.question q where q.author_id = u.id);
create temp table keptu as select id from legacy_qp.app_user where id not in (select id from skipu);
create temp table ksess as select id from legacy_yoklama.class_session where course_id in (select id from kc);
create temp table kexam as select id from legacy_yoklama.exam where course_id in (select id from kc);

-- student numbers in scope (roster / attendance / exam_result of kept courses, plus kept question-pool students)
create temp table scope_sn as
select student_no from legacy_yoklama.roster_entry where course_id in (select id from kc)
union select a.student_no from legacy_yoklama.attendance a where a.session_id in (select id from ksess)
union select r.student_no from legacy_yoklama.exam_result r where r.exam_id in (select id from kexam)
union select u.student_no from legacy_qp.app_user u where u.id in (select id from keptu) and u.student_no is not null;

-- ------------------------------------------------------------------ row counts (skip list applied)
insert into checks(check_name, expected, actual)
select 'count ' || t, e::text, a::text from (values
 ('course',          (select count(*) from kc),                                                 (select count(*) from course)),
 ('chapter',         (select count(*) from legacy_qp.chapter),                                  (select count(*) from chapter)),
 ('classroom',       (select count(*) from legacy_yoklama.classroom),                           (select count(*) from classroom)),
 ('class_session',   (select count(*) from ksess),                                              (select count(*) from class_session)),
 ('calibration_run', (select count(*) from legacy_yoklama.calibration_run where session_id in (select id from ksess)), (select count(*) from calibration_run)),
 ('checkin_attempt', (select count(*) from legacy_yoklama.checkin_attempt where session_id in (select id from ksess)), (select count(*) from checkin_attempt)),
 ('scan_record',     (select count(*) from legacy_yoklama.scan_record s join legacy_yoklama.checkin_attempt a on a.id = s.attempt_id where a.session_id in (select id from ksess)), (select count(*) from scan_record)),
 ('attendance',      (select count(*) from legacy_yoklama.attendance where session_id in (select id from ksess)), (select count(*) from attendance)),
 ('exam',            (select count(*) from kexam),                                              (select count(*) from exam)),
 ('exam_question',   (select count(*) from legacy_yoklama.exam_question where exam_id in (select id from kexam)), (select count(*) from exam_question)),
 ('exam_result',     (select count(*) from legacy_yoklama.exam_result where exam_id in (select id from kexam)), (select count(*) from exam_result)),
 ('exam_answer',     (select count(*) from legacy_yoklama.exam_answer a join legacy_yoklama.exam_result r on r.id = a.result_id where r.exam_id in (select id from kexam)), (select count(*) from exam_answer)),
 ('question',        (select count(*) from legacy_qp.question),                                 (select count(*) from question)),
 ('question_attempt',(select count(*) from legacy_qp.attempt where user_id in (select id from keptu)), (select count(*) from question_attempt)),
 ('question_comment',(select count(*) from legacy_qp.comment where author_id is null or author_id in (select id from keptu)), (select count(*) from question_comment)),
 ('question_flag',   (select count(*) from legacy_qp.flag where user_id in (select id from keptu)), (select count(*) from question_flag)),
 ('login_token (not migrated)', 0::bigint,                                                     (select count(*) from login_token where person_id in (select id from person where username is null))),
 ('user_session (not migrated)', 0::bigint,                                                    (select count(*) from user_session where person_id in (select id from person where username is null)))
) v(t, e, a);

-- person: kept question-pool users + teachers that are not merged + yoklama-only student numbers
insert into checks(check_name, expected, actual)
with pair as (
    select t.id as tid, u.id as uid
      from legacy_yoklama.teacher_user t
      join legacy_qp.app_user u on u.role = 'INSTRUCTOR'
       and (lower(t.username) = lower(u.email)
            or exists (select 1 from legacy_report.staff_map m
                        where lower(m.yoklama_username) = lower(t.username) and lower(m.qp_email) = lower(u.email)))
)
select 'count person',
       ((select count(*) from keptu)
        + (select count(*) from legacy_yoklama.teacher_user where id not in (select tid from pair))
        + (select count(*) from scope_sn s where not exists
              (select 1 from legacy_qp.app_user u where u.id in (select id from keptu) and u.student_no = s.student_no)))::text,
       (select count(*) from person)::text;

-- enrollments by role
insert into checks(check_name, expected, actual)
select 'count enrollment OWNER', (select count(*) from kc)::text,
       (select count(*) from enrollment where role = 'OWNER')::text;

insert into checks(check_name, expected, actual)
select 'count enrollment STUDENT',
       (select count(*) from (
            select r.course_id, r.student_no from legacy_yoklama.roster_entry r where r.course_id in (select id from kc)
            union select (select id from qc), u.student_no from legacy_qp.app_user u where u.role = 'STUDENT' and u.id in (select id from keptu)
            union select s.course_id, a.student_no from legacy_yoklama.attendance a join legacy_yoklama.class_session s on s.id = a.session_id where s.id in (select id from ksess)
            union select e.course_id, r.student_no from legacy_yoklama.exam_result r join legacy_yoklama.exam e on e.id = r.exam_id where e.id in (select id from kexam)
        ) x)::text,
       (select count(*) from enrollment where role = 'STUDENT')::text;

-- INSTRUCTOR: every question-pool instructor, except one that is also the (yoklama) owner of the question-pool course
insert into checks(check_name, expected, actual)
select 'count enrollment INSTRUCTOR',
       (select count(*) from legacy_qp.app_user u
         where u.role = 'INSTRUCTOR'
           and not exists (select 1 from legacy_yoklama.course c
                             join legacy_yoklama.teacher_user t on t.id = c.owner_id
                            where c.id = (select id from qc)
                              and (lower(t.username) = lower(u.email)
                                   or exists (select 1 from legacy_report.staff_map m
                                               where lower(m.yoklama_username) = lower(t.username)
                                                 and lower(m.qp_email) = lower(u.email)))))::text,
       (select count(*) from enrollment where role = 'INSTRUCTOR')::text;

insert into checks(check_name, expected, actual)
select 'count enrollment ASSISTANT', '0', (select count(*) from enrollment where role = 'ASSISTANT')::text;

-- ------------------------------------------------------------------ checksums that survive the remapping
insert into checks(check_name, expected, actual) values
('hash attendance per session',
 (select md5(coalesce(string_agg(session_id || ':' || c, ',' order by session_id), '')) from
    (select session_id, count(*) c from legacy_yoklama.attendance where session_id in (select id from ksess) group by 1) x),
 (select md5(coalesce(string_agg(session_id || ':' || c, ',' order by session_id), '')) from
    (select session_id, count(*) c from attendance group by 1) x)),
('hash attendance status/decision per session',
 (select md5(coalesce(string_agg(k, ',' order by k), '')) from
    (select session_id || ':' || status || ':' || teacher_decision || ':' || count(*) k from legacy_yoklama.attendance where session_id in (select id from ksess) group by session_id, status, teacher_decision) x),
 (select md5(coalesce(string_agg(k, ',' order by k), '')) from
    (select session_id || ':' || status || ':' || teacher_decision || ':' || count(*) k from attendance group by session_id, status, teacher_decision) x)),
('hash attendance rows (id, session, student_no, status, created_at)',
 (select md5(coalesce(string_agg(id || '|' || session_id || '|' || student_no || '|' || status || '|' || created_at::text, ',' order by id), '')) from legacy_yoklama.attendance where session_id in (select id from ksess)),
 (select md5(coalesce(string_agg(id || '|' || session_id || '|' || student_no || '|' || status || '|' || created_at::text, ',' order by id), '')) from attendance)),
('hash scans per attempt (valid count)',
 (select md5(coalesce(string_agg(attempt_id || ':' || c || ':' || v, ',' order by attempt_id), '')) from
    (select s.attempt_id, count(*) c, count(*) filter (where s.valid) v from legacy_yoklama.scan_record s
       join legacy_yoklama.checkin_attempt a on a.id = s.attempt_id where a.session_id in (select id from ksess) group by 1) x),
 (select md5(coalesce(string_agg(attempt_id || ':' || c || ':' || v, ',' order by attempt_id), '')) from
    (select attempt_id, count(*) c, count(*) filter (where valid) v from scan_record group by 1) x)),
('hash attempts per session/state',
 (select md5(coalesce(string_agg(k, ',' order by k), '')) from
    (select session_id || ':' || state || ':' || count(*) k from legacy_yoklama.checkin_attempt where session_id in (select id from ksess) group by session_id, state) x),
 (select md5(coalesce(string_agg(k, ',' order by k), '')) from
    (select session_id || ':' || state || ':' || count(*) k from checkin_attempt group by session_id, state) x)),
('hash question attempts per question (count, correct count)',
 (select md5(coalesce(string_agg(question_id || ':' || c || ':' || ok, ',' order by question_id), '')) from
    (select question_id, count(*) c, count(*) filter (where correct) ok from legacy_qp.attempt where user_id in (select id from keptu) group by 1) x),
 (select md5(coalesce(string_agg(question_id || ':' || c || ':' || ok, ',' order by question_id), '')) from
    (select question_id, count(*) c, count(*) filter (where correct) ok from question_attempt group by 1) x)),
('hash question attempts per student (via e-mail, count + correct)',
 (select md5(coalesce(string_agg(em || ':' || c || ':' || ok, ',' order by em), '')) from
    (select lower(u.email) em, count(*) c, count(*) filter (where a.correct) ok from legacy_qp.attempt a join legacy_qp.app_user u on u.id = a.user_id where u.id in (select id from keptu) group by 1) x),
 (select md5(coalesce(string_agg(em || ':' || c || ':' || ok, ',' order by em), '')) from
    (select p.email em, count(*) c, count(*) filter (where a.correct) ok from question_attempt a join person p on p.id = a.person_id group by 1) x)),
('hash questions per author (via e-mail)',
 (select md5(coalesce(string_agg(em || ':' || c, ',' order by em), '')) from
    (select lower(u.email) em, count(*) c from legacy_qp.question q join legacy_qp.app_user u on u.id = q.author_id group by 1) x),
 (select md5(coalesce(string_agg(em || ':' || c, ',' order by em), '')) from
    (select p.email em, count(*) c from question q join person p on p.id = q.author_id group by 1) x)),
('hash question content (id, chapter, correct, status)',
 (select md5(coalesce(string_agg(id || '|' || chapter_id || '|' || correct || '|' || status || '|' || md5(stem), ',' order by id), '')) from legacy_qp.question),
 (select md5(coalesce(string_agg(id || '|' || chapter_id || '|' || correct || '|' || status || '|' || md5(stem), ',' order by id), '')) from question)),
('hash comments (id, parent, question, body)',
 (select md5(coalesce(string_agg(id || '|' || coalesce(parent_id::text, '') || '|' || question_id || '|' || md5(body), ',' order by id), '')) from legacy_qp.comment where author_id is null or author_id in (select id from keptu)),
 (select md5(coalesce(string_agg(id || '|' || coalesce(parent_id::text, '') || '|' || question_id || '|' || md5(body), ',' order by id), '')) from question_comment)),
('hash comment authors (via e-mail)',
 (select md5(coalesce(string_agg(id || ':' || em, ',' order by id), '')) from
    (select c.id, coalesce(lower(u.email), '-') em from legacy_qp.comment c left join legacy_qp.app_user u on u.id = c.author_id where c.author_id is null or c.author_id in (select id from keptu)) x),
 (select md5(coalesce(string_agg(id || ':' || em, ',' order by id), '')) from
    (select c.id, coalesce(p.email, '-') em from question_comment c left join person p on p.id = c.author_id) x)),
('exam total points per exam',
 (select md5(coalesce(string_agg(exam_id || ':' || pts, ',' order by exam_id), '')) from
    (select r.exam_id, sum(a.points) pts from legacy_yoklama.exam_answer a join legacy_yoklama.exam_result r on r.id = a.result_id where r.exam_id in (select id from kexam) group by 1) x),
 (select md5(coalesce(string_agg(exam_id || ':' || pts, ',' order by exam_id), '')) from
    (select r.exam_id, sum(a.points) pts from exam_answer a join exam_result r on r.id = a.result_id group by 1) x)),
('exam total points per student (via student_no)',
 (select md5(coalesce(string_agg(k, ',' order by k), '')) from
    (select r.exam_id || ':' || r.student_no || ':' || coalesce(sum(a.points), 0) k from legacy_yoklama.exam_result r left join legacy_yoklama.exam_answer a on a.result_id = r.id where r.exam_id in (select id from kexam) group by r.exam_id, r.student_no) x),
 (select md5(coalesce(string_agg(k, ',' order by k), '')) from
    (select r.exam_id || ':' || p.student_no || ':' || coalesce(sum(a.points), 0) k from exam_result r join person p on p.id = r.person_id left join exam_answer a on a.result_id = r.id group by r.exam_id, p.student_no) x)),
('exam max points per exam',
 (select md5(coalesce(string_agg(exam_id || ':' || pts, ',' order by exam_id), '')) from
    (select exam_id, sum(max_points) pts from legacy_yoklama.exam_question where exam_id in (select id from kexam) group by 1) x),
 (select md5(coalesce(string_agg(exam_id || ':' || pts, ',' order by exam_id), '')) from
    (select exam_id, sum(max_points) pts from exam_question group by 1) x)),
('hash staff credentials (username, password_hash copied unchanged)',
 (select md5(coalesce(string_agg(username || ':' || password_hash, ',' order by username), '')) from legacy_yoklama.teacher_user),
 (select md5(coalesce(string_agg(username || ':' || password_hash, ',' order by username), '')) from person where username is not null)),
('staff system_admin flags',
 (select md5(coalesce(string_agg(username || ':' || (role = 'ADMIN'), ',' order by username), '')) from legacy_yoklama.teacher_user),
 (select md5(coalesce(string_agg(username || ':' || system_admin, ',' order by username), '')) from person where username is not null)),
('staff disabled accounts (enabled=false -> active=false)',
 (select md5(coalesce(string_agg(username, ',' order by username), '')) from legacy_yoklama.teacher_user where not enabled),
 (select md5(coalesce(string_agg(username, ',' order by username), '')) from person where username is not null and not active)),
('question-pool people keep e-mail / name / nickname / deleted_at',
 (select md5(coalesce(string_agg(lower(email) || '|' || full_name || '|' || nickname || '|' || coalesce(deleted_at::text, ''), ',' order by lower(email)), '')) from legacy_qp.app_user where id in (select id from keptu)),
 (select md5(coalesce(string_agg(email || '|' || full_name || '|' || nickname || '|' || coalesce(deleted_at::text, ''), ',' order by email), '')) from person where email is not null)),
('roster students per course (distinct student_no)',
 (select md5(coalesce(string_agg(course_id || ':' || c, ',' order by course_id), '')) from
    (select course_id, count(distinct student_no) c from legacy_yoklama.roster_entry where course_id in (select id from kc) group by 1) x),
 (select md5(coalesce(string_agg(course_id || ':' || c, ',' order by course_id), '')) from
    (select e.course_id, count(*) c from enrollment e join person p on p.id = e.person_id
      where e.role = 'STUDENT' and exists (select 1 from legacy_yoklama.roster_entry r where r.course_id = e.course_id and r.student_no = p.student_no)
      group by 1) x)),
('chapter -> course (all in question-pool course)',
 (select count(*)::text from legacy_qp.chapter),
 (select count(*)::text from chapter where course_id = (select id from qc))),
('question-pool course settings',
 'true|en|2|50|true',
 (select pool_enabled || '|' || language || '|' || questions_per_chapter || '|' || ask_ai_per_day || '|' || (ai_context like 'SENG 403 Software Quality%') from course where id = (select id from qc))),
('other courses: pool disabled, attendance enabled, term, language tr',
 (select count(*)::text from kc where id <> (select id from qc)),
 (select count(*)::text from course where id <> (select id from qc) and not pool_enabled and attendance_enabled and language = 'tr' and term = '2026-FALL'));

-- ------------------------------------------------------------------ integrity
insert into checks(check_name, expected, actual) values
('orphan person: attendance.person_id', '0', (select count(*)::text from attendance a left join person p on p.id = a.person_id where p.id is null)),
('attendance person student_no = snapshot', '0', (select count(*)::text from attendance a join person p on p.id = a.person_id where p.student_no is distinct from a.student_no)),
('exam_result person student_no = snapshot', '0', (select count(*)::text from exam_result a join person p on p.id = a.person_id where p.student_no is distinct from a.student_no)),
('orphan person: exam_result.person_id', '0', (select count(*)::text from exam_result a left join person p on p.id = a.person_id where p.id is null)),
('orphan person: question.author_id', '0', (select count(*)::text from question a left join person p on p.id = a.author_id where p.id is null)),
('orphan person: question_attempt.person_id', '0', (select count(*)::text from question_attempt a left join person p on p.id = a.person_id where p.id is null)),
('orphan person: question_comment.author_id', '0', (select count(*)::text from question_comment a where a.author_id is not null and not exists (select 1 from person p where p.id = a.author_id))),
('orphan person: question_flag.person_id', '0', (select count(*)::text from question_flag a left join person p on p.id = a.person_id where p.id is null)),
('orphan person: classroom.owner_id', '0', (select count(*)::text from classroom a left join person p on p.id = a.owner_id where p.id is null)),
('orphan person: enrollment.person_id', '0', (select count(*)::text from enrollment a left join person p on p.id = a.person_id where p.id is null)),
('orphan question -> chapter', '0', (select count(*)::text from question a left join chapter c on c.id = a.chapter_id where c.id is null)),
('orphan attendance.attempt_id -> checkin_attempt (where set)', '0',
   (select count(*)::text from attendance a where a.attempt_id is not null and not exists (select 1 from checkin_attempt c where c.id = a.attempt_id))),
('attendance without enrollment in its course', '0',
   (select count(*)::text from attendance a join class_session s on s.id = a.session_id
     where not exists (select 1 from enrollment e where e.course_id = s.course_id and e.person_id = a.person_id))),
('exam_result without enrollment in its course', '0',
   (select count(*)::text from exam_result r join exam x on x.id = r.exam_id
     where not exists (select 1 from enrollment e where e.course_id = x.course_id and e.person_id = r.person_id))),
('question author without enrollment in the pool course', '0',
   (select count(*)::text from question q
     where not exists (select 1 from enrollment e where e.course_id = (select id from qc) and e.person_id = q.author_id))),
('question_attempt person without enrollment in the pool course', '0',
   (select count(*)::text from question_attempt a
     where not exists (select 1 from enrollment e where e.course_id = (select id from qc) and e.person_id = a.person_id))),
('duplicate student_no', '0', (select (count(student_no) - count(distinct student_no))::text from person)),
('duplicate e-mail (case-insensitive)', '0', (select (count(email) - count(distinct lower(email)))::text from person)),
('e-mail not lower-case', '0', (select count(*)::text from person where email <> lower(email))),
('duplicate nickname (case-insensitive)', '0', (select (count(*) - count(distinct lower(nickname)))::text from person)),
('duplicate username', '0', (select (count(username) - count(distinct username))::text from person)),
('every person has a nickname', '0', (select count(*)::text from person where nickname is null or nickname = '')),
('people without enrollment (staff-only accounts allowed)', '0',
   (select count(*)::text from person p where p.student_no is not null
      and not exists (select 1 from enrollment e where e.person_id = p.id))),
('pool/exam FK: exam_question.pool_question_id all null', (select count(*)::text from exam_question), (select count(*)::text from exam_question where pool_question_id is null)),
('exam forms=1, seed null, result form null', (select count(*)::text from exam), (select count(*)::text from exam where forms = 1 and seed is null)),
('legacy question-pool exam / exam_item empty', '0', ((select count(*) from legacy_qp.exam) + (select count(*) from legacy_qp.exam_item))::text);

-- ------------------------------------------------------------------ sequences ahead of max ids
do $$
declare
    r record;
    m bigint;
    nxt bigint;
    seq text;
begin
    for r in select table_name, column_name from information_schema.columns
              where table_schema = 'public' and is_identity = 'YES' order by table_name
    loop
        seq := pg_get_serial_sequence(format('%I', r.table_name), r.column_name);
        execute format('select coalesce(max(%I), 0) from %I', r.column_name, r.table_name) into m;
        execute format('select case when is_called then last_value + 1 else last_value end from %s', seq) into nxt;
        insert into checks(check_name, expected, actual)
        values ('sequence ahead of max(id): ' || r.table_name, '>' || m, case when nxt > m then '>' || m else 'next=' || nxt end);
    end loop;
end $$;

-- ------------------------------------------------------------------ result
select check_name, expected, actual, (expected is not distinct from actual) as ok from checks order by n;

select count(*) as checks_total, count(*) filter (where expected is not distinct from actual) as checks_ok,
       count(*) filter (where expected is distinct from actual) as checks_failed from checks;

do $$
declare
    bad int;
begin
    select count(*) into bad from checks where expected is distinct from actual;
    if bad > 0 then
        raise exception 'verify.sql: % check(s) FAILED', bad;
    end if;
    raise notice 'verify.sql: all checks passed';
end $$;
