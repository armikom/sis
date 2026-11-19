-- SIS one-off data migration: legacy_yoklama + legacy_qp  ->  SIS schema (public).
--
-- Run once, after V1__init.sql has been applied (the app started once) and load-legacy.sh has loaded the dumps:
--   psql "$DB_URL" -v ON_ERROR_STOP=1 -f migrate.sql
-- Everything runs in ONE transaction; any error (or any RAISE EXCEPTION guard) rolls it all back.
-- The result summary stays in schema legacy_report (migration_report, name_diff, auto_enrolled, ...).
-- legacy_report contains personal data (student numbers, names): drop it together with the legacy schemas.

\set ON_ERROR_STOP on
\timing off
begin;

-- =====================================================================================================
-- CONFIGURATION  (edit here)
-- =====================================================================================================
create temp table cfg as
select
    -- yoklama course code that the whole question-pool data belongs to
    'SENG 403'::text                              as qp_course_code,
    -- yoklama course codes that are NOT migrated (with roster, sessions, attendance, attempts, scans, exams)
    array['TEST101']::text[]                      as skip_course_codes,
    -- question-pool STUDENT accounts whose e-mail domain differs are test accounts and are NOT migrated
    -- (unless they authored questions: those are kept and listed in legacy_report.kept_test_accounts)
    'stu.thk.edu.tr'::text                        as student_email_domain,
    '2026-FALL'::text                             as term,
    'en'::text                                    as qp_course_language,
    2                                             as qp_questions_per_chapter,
    50                                            as qp_ask_ai_per_day,
    'SENG 403 Software Quality Assurance and Testing (4th year). The students are not native English speakers, so always write short sentences in basic English.'::text
                                                  as qp_ai_context;

-- Explicit staff mapping for people whose yoklama username and question-pool e-mail differ.
-- (Pairs whose username equals the e-mail, case-insensitive, are matched automatically.)
-- The question-pool e-mail of yoklama user 'gokcer' is passed in so that it stays out of this public repository:
--   psql ... -v owner_qp_email=<e-mail> -f migrate.sql        (rehearse.sh: OWNER_QP_EMAIL=<e-mail>)
\if :{?owner_qp_email}
\else
\echo 'migrate.sql: pass -v owner_qp_email=<question-pool e-mail of yoklama user gokcer>'
do $$ begin raise exception 'psql variable owner_qp_email is not set'; end $$;
\endif
create temp table staff_map(yoklama_username text, qp_email text);
insert into staff_map values
    ('gokcer', :'owner_qp_email')
    -- , ('another.username', 'another@example.edu')
    ;

-- =====================================================================================================
-- PRE-FLIGHT GUARDS
-- =====================================================================================================
do $$
declare
    t text;
    n bigint;
begin
    if to_regclass('legacy_yoklama.teacher_user') is null or to_regclass('legacy_qp.app_user') is null then
        raise exception 'legacy schemas not loaded: run load-legacy.sh first';
    end if;

    -- target tables must be empty (login_token / user_session may hold the bootstrap admin session)
    foreach t in array array['course','enrollment','chapter','classroom','class_session','calibration_run',
        'checkin_attempt','scan_record','attendance','question','question_attempt','question_comment',
        'question_flag','exam','exam_question','exam_result','exam_answer','mcp_token','study_state',
        'practice_answer','study_log']
    loop
        execute format('select count(*) from %I', t) into n;
        if n <> 0 then
            raise exception 'target table % is not empty (% rows): refusing to migrate', t, n;
        end if;
    end loop;

    -- only a bootstrap person whose username belongs to a yoklama teacher account may pre-exist
    select count(*) into n from person p
     where not exists (select 1 from legacy_yoklama.teacher_user t where t.username = p.username);
    if n <> 0 then
        raise exception 'person table already holds % row(s) that are not a bootstrap staff account: refusing to migrate', n;
    end if;

    select count(*) into n from legacy_qp.exam;
    if n <> 0 then raise exception 'legacy question-pool exam is not empty (% rows): not supported', n; end if;
    select count(*) into n from legacy_qp.exam_item;
    if n <> 0 then raise exception 'legacy question-pool exam_item is not empty (% rows): not supported', n; end if;

    if (select count(*) from legacy_yoklama.course c, cfg where c.code = cfg.qp_course_code) <> 1 then
        raise exception 'question-pool course code not found (or ambiguous) in yoklama courses';
    end if;
    if exists (select 1 from legacy_yoklama.course c, cfg
                where c.code = cfg.qp_course_code and c.code = any (cfg.skip_course_codes)) then
        raise exception 'the question-pool course is on the skip list';
    end if;
end $$;

drop schema if exists legacy_report cascade;
create schema legacy_report;

-- persist the configuration so that verify.sql can read the same skip list
create table legacy_report.config as select * from cfg;
create table legacy_report.staff_map as select * from staff_map;

-- =====================================================================================================
-- SCOPE: kept courses, skipped question-pool users
-- =====================================================================================================
create temp table kept_course as
select c.id from legacy_yoklama.course c, cfg where c.code <> all (cfg.skip_course_codes);

create temp table qp_course as
select c.id from legacy_yoklama.course c, cfg where c.code = cfg.qp_course_code;

-- test students: other e-mail domain AND not an author of questions
create temp table qp_skip_user as
select u.id
  from legacy_qp.app_user u, cfg
 where u.role = 'STUDENT'
   and lower(u.email) not like '%@' || cfg.student_email_domain
   and not exists (select 1 from legacy_qp.question q where q.author_id = u.id);

create table legacy_report.kept_test_accounts as
select u.id as qp_user_id,
       (select count(*) from legacy_qp.question q where q.author_id = u.id) as questions,
       (select count(*) from legacy_qp.attempt a where a.user_id = u.id)    as attempts,
       (select count(*) from legacy_qp.comment c where c.author_id = u.id)  as comments
  from legacy_qp.app_user u, cfg
 where u.role = 'STUDENT'
   and lower(u.email) not like '%@' || cfg.student_email_domain
   and u.id not in (select id from qp_skip_user);

-- =====================================================================================================
-- NICKNAME GENERATOR (question-pool style: AdjectiveAnimalNN)
-- =====================================================================================================
select setseed(0.4242);
create function pg_temp.gen_nick() returns text language plpgsql as $$
declare
    adj text[] := array['Brave','Calm','Clever','Cosmic','Eager','Fuzzy','Gentle','Happy','Jolly','Kind','Lucky',
                        'Mighty','Nimble','Proud','Quick','Quiet','Rapid','Silent','Sunny','Swift','Witty','Zesty',
                        'Bold','Bright'];
    ani text[] := array['Otter','Falcon','Panda','Tiger','Koala','Lynx','Heron','Badger','Dolphin','Fox','Gecko',
                        'Hawk','Ibex','Jaguar','Lemur','Marmot','Narwhal','Owl','Puffin','Quokka','Raven','Seal',
                        'Walrus','Yak'];
    cand text;
begin
    loop
        cand := adj[1 + floor(random() * array_length(adj, 1))::int]
             || ani[1 + floor(random() * array_length(ani, 1))::int]
             || (10 + floor(random() * 90))::int;
        exit when not exists (select 1 from person where lower(nickname) = lower(cand));
    end loop;
    return cand;
end $$;

-- =====================================================================================================
-- PEOPLE 1: staff (yoklama teacher_user  +  question-pool INSTRUCTOR)
-- =====================================================================================================
create temp table staff_pair as
select t.id as teacher_id, u.id as qp_user_id
  from legacy_yoklama.teacher_user t
  join legacy_qp.app_user u
    on u.role = 'INSTRUCTOR'
   and (lower(t.username) = lower(u.email)
        or exists (select 1 from staff_map m
                    where lower(m.yoklama_username) = lower(t.username)
                      and lower(m.qp_email) = lower(u.email)));

do $$
begin
    if (select count(*) - count(distinct teacher_id) from staff_pair) <> 0
       or (select count(*) - count(distinct qp_user_id) from staff_pair) <> 0 then
        raise exception 'ambiguous staff matching: a teacher or an instructor matched more than once';
    end if;
end $$;

create temp table staff_person(teacher_id bigint primary key, qp_user_id bigint, person_id bigint);

do $$
declare
    r record;
    pid bigint;
begin
    for r in
        select t.id as teacher_id, t.username, t.password_hash, t.display_name, t.role, t.enabled,
               t.created_at as t_created,
               u.id as qp_user_id, u.email, u.full_name, u.student_no, u.nickname, u.active as qp_active,
               u.deleted_at, u.created_at as u_created, u.last_login_at
          from legacy_yoklama.teacher_user t
          left join staff_pair sp on sp.teacher_id = t.id
          left join legacy_qp.app_user u on u.id = sp.qp_user_id
         order by t.id
    loop
        select id into pid from person where username = r.username;
        if pid is not null then
            -- bootstrap admin created by the app on first start: take it over
            update person set
                full_name     = coalesce(r.full_name, r.display_name),
                student_no    = r.student_no,
                email         = lower(r.email),
                password_hash = r.password_hash,
                -- the app gives its bootstrap admin the nickname 'Instructor', which question-pool already uses
                nickname      = coalesce(r.nickname, pg_temp.gen_nick()),
                system_admin  = (r.role = 'ADMIN'),
                active        = r.enabled and coalesce(r.qp_active, true),
                deleted_at    = r.deleted_at,
                created_at    = least(r.t_created, coalesce(r.u_created, r.t_created)),
                last_login_at = r.last_login_at
             where id = pid;
        else
            insert into person(full_name, student_no, email, username, password_hash, nickname,
                               system_admin, active, deleted_at, created_at, last_login_at)
            values (coalesce(r.full_name, r.display_name), r.student_no, lower(r.email), r.username,
                    r.password_hash, coalesce(r.nickname, pg_temp.gen_nick()),
                    r.role = 'ADMIN', r.enabled and coalesce(r.qp_active, true), r.deleted_at,
                    least(r.t_created, coalesce(r.u_created, r.t_created)), r.last_login_at)
            returning id into pid;
        end if;
        insert into staff_person values (r.teacher_id, r.qp_user_id, pid);
    end loop;
end $$;

-- question-pool instructors without a yoklama account (e.g. the soft-deleted one)
insert into person(full_name, student_no, email, nickname, active, deleted_at, created_at, last_login_at)
select u.full_name, u.student_no, lower(u.email), u.nickname, u.active, u.deleted_at, u.created_at, u.last_login_at
  from legacy_qp.app_user u
 where u.role = 'INSTRUCTOR'
   and u.id not in (select qp_user_id from staff_person where qp_user_id is not null)
 order by u.id;

-- =====================================================================================================
-- PEOPLE 2: question-pool students (minus skipped test accounts)
-- =====================================================================================================
insert into person(full_name, student_no, email, nickname, active, deleted_at, created_at, last_login_at)
select u.full_name, u.student_no, lower(u.email), u.nickname, u.active, u.deleted_at, u.created_at, u.last_login_at
  from legacy_qp.app_user u
 where u.role = 'STUDENT'
   and u.id not in (select id from qp_skip_user)
 order by u.id;

-- map: question-pool user -> person (e-mail is unique on both sides)
create temp table qp_person as
select u.id as qp_user_id, p.id as person_id
  from legacy_qp.app_user u
  join person p on p.email = lower(u.email)
 where u.id not in (select id from qp_skip_user);

do $$
begin
    if (select count(*) from qp_person) <> (select count(*) from legacy_qp.app_user) - (select count(*) from qp_skip_user) then
        raise exception 'question-pool user -> person mapping is incomplete';
    end if;
end $$;

-- =====================================================================================================
-- PEOPLE 3: yoklama students (roster, attendance, exam results of kept courses) not yet known
-- =====================================================================================================
create temp table student_src as
select distinct on (student_no) student_no, full_name
  from (
        select r.student_no, r.full_name, 1 as pri
          from legacy_yoklama.roster_entry r
         where r.course_id in (select id from kept_course)
        union all
        select a.student_no, coalesce(nullif(trim(a.full_name), ''), a.student_no), 2
          from legacy_yoklama.attendance a
          join legacy_yoklama.class_session s on s.id = a.session_id
         where s.course_id in (select id from kept_course)
        union all
        select er.student_no, coalesce(nullif(trim(er.full_name), ''), er.student_no), 3
          from legacy_yoklama.exam_result er
          join legacy_yoklama.exam e on e.id = er.exam_id
         where e.course_id in (select id from kept_course)
       ) x
 order by student_no, pri;

-- names that differ between the two systems (question-pool's name wins)
create table legacy_report.name_diff as
select distinct p.student_no, p.full_name as kept_name, r.full_name as yoklama_name
  from legacy_yoklama.roster_entry r
  join person p on p.student_no = r.student_no
 where r.course_id in (select id from kept_course)
   and trim(p.full_name) <> trim(r.full_name);

do $$
declare
    r record;
begin
    for r in select s.student_no, s.full_name from student_src s
              where not exists (select 1 from person p where p.student_no = s.student_no)
              order by s.student_no
    loop
        insert into person(full_name, student_no, nickname)
        values (r.full_name, r.student_no, pg_temp.gen_nick());
    end loop;
end $$;

create temp table roster_only_count as
select count(*) as n from student_src s
 where not exists (select 1 from legacy_qp.app_user u where u.student_no = s.student_no
                   and u.id not in (select id from qp_skip_user));

-- =====================================================================================================
-- COURSES
-- =====================================================================================================
insert into course(id, code, name, term, language, attendance_enabled, pool_enabled, questions_per_chapter,
                   ask_ai_per_day, ai_context, created_at)
select c.id, c.code, c.name, cfg.term, 'tr', true, false, 2, 10, '', c.created_at
  from legacy_yoklama.course c, cfg
 where c.id in (select id from kept_course)
 order by c.id;

update course set pool_enabled = true,
                  language = cfg.qp_course_language,
                  questions_per_chapter = cfg.qp_questions_per_chapter,
                  ask_ai_per_day = cfg.qp_ask_ai_per_day,
                  ai_context = cfg.qp_ai_context
  from cfg
 where course.id = (select id from qp_course);

-- =====================================================================================================
-- ENROLLMENTS
-- =====================================================================================================
-- owners
insert into enrollment(course_id, person_id, role, created_at)
select c.id, sp.person_id, 'OWNER', c.created_at
  from legacy_yoklama.course c
  join staff_person sp on sp.teacher_id = c.owner_id
 where c.id in (select id from kept_course)
 order by c.id;

-- question-pool instructors in the question-pool course
insert into enrollment(course_id, person_id, role, deleted_at, created_at)
select (select id from qp_course), qpp.person_id, 'INSTRUCTOR', u.deleted_at, u.created_at
  from legacy_qp.app_user u
  join qp_person qpp on qpp.qp_user_id = u.id
 where u.role = 'INSTRUCTOR'
 order by u.id
on conflict (course_id, person_id) do nothing;

-- yoklama roster
insert into enrollment(course_id, person_id, role, created_at)
select r.course_id, p.id, 'STUDENT', c.created_at
  from legacy_yoklama.roster_entry r
  join legacy_yoklama.course c on c.id = r.course_id
  join person p on p.student_no = r.student_no
 where r.course_id in (select id from kept_course)
 order by r.id
on conflict (course_id, person_id) do nothing;

-- question-pool students in the question-pool course
insert into enrollment(course_id, person_id, role, deleted_at, created_at)
select (select id from qp_course), qpp.person_id, 'STUDENT', u.deleted_at, u.created_at
  from legacy_qp.app_user u
  join qp_person qpp on qpp.qp_user_id = u.id
 where u.role = 'STUDENT'
 order by u.id
on conflict (course_id, person_id) do nothing;

-- a soft-deleted question-pool student stays soft-removed even when also on the roster
update enrollment e set deleted_at = u.deleted_at
  from legacy_qp.app_user u
  join qp_person qpp on qpp.qp_user_id = u.id
 where u.role = 'STUDENT' and u.deleted_at is not null
   and e.person_id = qpp.person_id and e.course_id = (select id from qp_course);

-- attendance / exam results of students that are not on the course (roster, question-pool): enroll them
create table legacy_report.auto_enrolled as
select distinct x.course_id, p.id as person_id, p.student_no, x.via
  from (
        select s.course_id, a.student_no, 'attendance'::text as via
          from legacy_yoklama.attendance a
          join legacy_yoklama.class_session s on s.id = a.session_id
        union
        select e.course_id, er.student_no, 'exam_result'
          from legacy_yoklama.exam_result er
          join legacy_yoklama.exam e on e.id = er.exam_id
       ) x
  join person p on p.student_no = x.student_no
 where x.course_id in (select id from kept_course)
   and not exists (select 1 from enrollment en where en.course_id = x.course_id and en.person_id = p.id);

insert into enrollment(course_id, person_id, role, created_at)
select course_id, person_id, 'STUDENT', now()
  from (select distinct course_id, person_id from legacy_report.auto_enrolled) a
 order by course_id, person_id;

-- =====================================================================================================
-- ATTENDANCE
-- =====================================================================================================
insert into classroom(id, owner_id, name, latitude, longitude, radius_m, ble_beacon_id, ble_key_hex, created_at)
select c.id, sp.person_id, c.name, c.latitude, c.longitude, c.radius_m, c.ble_beacon_id, c.ble_key_hex, c.created_at
  from legacy_yoklama.classroom c
  join staff_person sp on sp.teacher_id = c.owner_id
 order by c.id;

insert into class_session(id, course_id, classroom_id, title, code, status, duration_minutes, refresh_ms,
        required_scans, max_span_slots, max_token_age_ms, latency_margin_ms, default_latency_threshold_ms,
        screen_area_ratio_threshold, geo_mode, ble_mode, frame_capture, epoch_start_ms, opened_at, closes_at,
        closed_at, baseline_latency_ms, baseline_area_ratio_max, created_at)
select s.id, s.course_id, s.classroom_id, s.title, s.code, s.status, s.duration_minutes, s.refresh_ms,
       s.required_scans, s.max_span_slots, s.max_token_age_ms, s.latency_margin_ms, s.default_latency_threshold_ms,
       s.screen_area_ratio_threshold, s.geo_mode, s.ble_mode, s.frame_capture, s.epoch_start_ms, s.opened_at,
       s.closes_at, s.closed_at, s.baseline_latency_ms, s.baseline_area_ratio_max, s.created_at
  from legacy_yoklama.class_session s
 where s.course_id in (select id from kept_course)
 order by s.id;

insert into calibration_run(id, session_id, label, min_latency_ms, max_area_ratio, valid_scan_count, clock_rtt_ms, created_at)
select c.id, c.session_id, c.label, c.min_latency_ms, c.max_area_ratio, c.valid_scan_count, c.clock_rtt_ms, c.created_at
  from legacy_yoklama.calibration_run c
 where c.session_id in (select id from class_session)
 order by c.id;

insert into checkin_attempt(id, session_id, student_no, device_id, ip, user_agent, state, result_code, frame_path,
        frame_seq, ble_nonce, ble_nonce_expires_ms, ble_verified, sync1_offset_ms, sync1_rtt_ms, created_at, expires_at)
select a.id, a.session_id, a.student_no, a.device_id, a.ip, a.user_agent, a.state, a.result_code, a.frame_path,
       a.frame_seq, a.ble_nonce, a.ble_nonce_expires_ms, a.ble_verified, a.sync1_offset_ms, a.sync1_rtt_ms,
       a.created_at, a.expires_at
  from legacy_yoklama.checkin_attempt a
 where a.session_id in (select id from class_session)
 order by a.created_at, a.id;

insert into scan_record(id, attempt_id, raw_token, seq, ts_ms, first_seen_server_ms, arrival_ms, frame_time_source,
        frame_w, frame_h, corners, area_ratio, valid, reason, clock_suspect, duplicate)
select s.id, s.attempt_id, s.raw_token, s.seq, s.ts_ms, s.first_seen_server_ms, s.arrival_ms, s.frame_time_source,
       s.frame_w, s.frame_h, s.corners, s.area_ratio, s.valid, s.reason, s.clock_suspect, s.duplicate
  from legacy_yoklama.scan_record s
 where s.attempt_id in (select id from checkin_attempt)
 order by s.id;

insert into attendance(id, session_id, person_id, student_no, full_name, source, attempt_id, device_id, status, flags,
        min_latency_ms, latency_threshold_ms, threshold_source, area_ratio_max, area_threshold, geo_state, geo_lat,
        geo_lng, geo_accuracy_m, geo_distance_m, clock_offset_ms, clock_rtt_ms, camera_id_hash, camera_label,
        user_agent, ble_verified, frame_path, teacher_decision, teacher_note, decided_at, created_at)
select a.id, a.session_id, p.id, a.student_no, a.full_name, a.source, a.attempt_id, a.device_id, a.status, a.flags,
       a.min_latency_ms, a.latency_threshold_ms, a.threshold_source, a.area_ratio_max, a.area_threshold, a.geo_state,
       a.geo_lat, a.geo_lng, a.geo_accuracy_m, a.geo_distance_m, a.clock_offset_ms, a.clock_rtt_ms, a.camera_id_hash,
       a.camera_label, a.user_agent, a.ble_verified, a.frame_path, a.teacher_decision, a.teacher_note, a.decided_at,
       a.created_at
  from legacy_yoklama.attendance a
  join person p on p.student_no = a.student_no
 where a.session_id in (select id from class_session)
 order by a.id;

-- =====================================================================================================
-- EXAMS (yoklama)
-- =====================================================================================================
insert into exam(id, course_id, title, exam_date, forms, seed, created_at)
select e.id, e.course_id, e.title, e.exam_date, 1, null, e.created_at
  from legacy_yoklama.exam e
 where e.course_id in (select id from kept_course)
 order by e.id;

insert into exam_question(id, exam_id, position, type, text, topic, max_points, choice_count, correct_choice, pool_question_id)
select q.id, q.exam_id, q.position, q.type, q.text, q.topic, q.max_points, q.choice_count, q.correct_choice, null
  from legacy_yoklama.exam_question q
 where q.exam_id in (select id from exam)
 order by q.id;

insert into exam_result(id, exam_id, person_id, student_no, full_name, form, note, created_at, updated_at)
select r.id, r.exam_id, p.id, r.student_no, r.full_name, null, r.note, r.created_at, r.updated_at
  from legacy_yoklama.exam_result r
  join person p on p.student_no = r.student_no
 where r.exam_id in (select id from exam)
 order by r.id;

insert into exam_answer(id, result_id, question_id, answer_text, points)
select a.id, a.result_id, a.question_id, a.answer_text, a.points
  from legacy_yoklama.exam_answer a
 where a.result_id in (select id from exam_result)
 order by a.id;

-- =====================================================================================================
-- QUESTION POOL
-- =====================================================================================================
insert into chapter(id, course_id, number, title, doc_en, doc_tr, open)
select c.id, (select id from qp_course), c.number, c.title, c.doc_en, c.doc_tr, c.open
  from legacy_qp.chapter c
 order by c.id;

insert into question(id, chapter_id, author_id, stem, option_a, option_b, option_c, option_d, correct,
        rationale_a, rationale_b, rationale_c, rationale_d, reference, status, ai_status, ai_review,
        instructor_note, explanation, created_at, updated_at, approved_at)
select q.id, q.chapter_id, qpp.person_id, q.stem, q.option_a, q.option_b, q.option_c, q.option_d, q.correct,
       q.rationale_a, q.rationale_b, q.rationale_c, q.rationale_d, q.reference, q.status, q.ai_status, q.ai_review,
       q.instructor_note, q.explanation, q.created_at, q.updated_at, q.approved_at
  from legacy_qp.question q
  join qp_person qpp on qpp.qp_user_id = q.author_id
 order by q.id;

insert into question_attempt(id, question_id, person_id, selected, correct, created_at)
select a.id, a.question_id, qpp.person_id, a.selected, a.correct, a.created_at
  from legacy_qp.attempt a
  join qp_person qpp on qpp.qp_user_id = a.user_id
 order by a.id;

-- comments: drop those of skipped authors and, transitively, their replies
create temp table comment_keep as
with recursive k as (
    select c.id from legacy_qp.comment c
     where c.parent_id is null
       and (c.author_id is null or c.author_id not in (select id from qp_skip_user))
    union all
    select c.id from legacy_qp.comment c
      join k on c.parent_id = k.id
     where c.author_id is null or c.author_id not in (select id from qp_skip_user)
)
select id from k;

insert into question_comment(id, question_id, author_id, is_ai, parent_id, body, deleted, created_at)
select c.id, c.question_id, qpp.person_id, c.is_ai, c.parent_id, c.body, c.deleted, c.created_at
  from legacy_qp.comment c
  left join qp_person qpp on qpp.qp_user_id = c.author_id
 where c.id in (select id from comment_keep)
 order by c.id;

insert into question_flag(id, question_id, person_id, reason, resolved, created_at)
select f.id, f.question_id, qpp.person_id, f.reason, f.resolved, f.created_at
  from legacy_qp.flag f
  join qp_person qpp on qpp.qp_user_id = f.user_id
 order by f.id;

-- =====================================================================================================
-- SEQUENCES: every identity column continues at max(id)+1
-- =====================================================================================================
do $$
declare
    r record;
    m bigint;
begin
    for r in select table_name, column_name from information_schema.columns
              where table_schema = 'public' and is_identity = 'YES' order by table_name
    loop
        execute format('select coalesce(max(%I), 0) from %I', r.column_name, r.table_name) into m;
        perform setval(pg_get_serial_sequence(format('%I', r.table_name), r.column_name), m + 1, false);
    end loop;
end $$;

-- =====================================================================================================
-- REPORT
-- =====================================================================================================
create table legacy_report.migration_report(section text, metric text, value bigint);

insert into legacy_report.migration_report
select 'target', t, n from (
    select 'person' t, count(*) n from person union all
    select 'course', count(*) from course union all
    select 'enrollment', count(*) from enrollment union all
    select 'enrollment OWNER', count(*) from enrollment where role = 'OWNER' union all
    select 'enrollment INSTRUCTOR', count(*) from enrollment where role = 'INSTRUCTOR' union all
    select 'enrollment STUDENT', count(*) from enrollment where role = 'STUDENT' union all
    select 'chapter', count(*) from chapter union all
    select 'classroom', count(*) from classroom union all
    select 'class_session', count(*) from class_session union all
    select 'calibration_run', count(*) from calibration_run union all
    select 'checkin_attempt', count(*) from checkin_attempt union all
    select 'scan_record', count(*) from scan_record union all
    select 'attendance', count(*) from attendance union all
    select 'question', count(*) from question union all
    select 'question_attempt', count(*) from question_attempt union all
    select 'question_comment', count(*) from question_comment union all
    select 'question_flag', count(*) from question_flag union all
    select 'exam', count(*) from exam union all
    select 'exam_question', count(*) from exam_question union all
    select 'exam_result', count(*) from exam_result union all
    select 'exam_answer', count(*) from exam_answer
) x;

insert into legacy_report.migration_report values
    ('people', 'created from yoklama roster/attendance only (no e-mail)', (select n from roster_only_count)),
    ('people', 'staff accounts (yoklama teacher_user)', (select count(*) from staff_person)),
    ('people', 'staff merged yoklama<->question-pool', (select count(*) from staff_pair)),
    ('people', 'name mismatches (question-pool name kept)', (select count(distinct student_no) from legacy_report.name_diff)),
    ('people', 'question-pool test accounts skipped', (select count(*) from qp_skip_user)),
    ('people', 'question-pool test accounts KEPT (authored questions)', (select count(*) from legacy_report.kept_test_accounts)),
    ('enrollment', 'auto-enrolled (attendance/exam row, not on roster)', (select count(*) from (select distinct course_id, person_id from legacy_report.auto_enrolled) z)),
    ('enrollment', 'auto-enrolled rows: attendance', (select count(*) from legacy_report.auto_enrolled where via = 'attendance')),
    ('enrollment', 'auto-enrolled rows: exam_result', (select count(*) from legacy_report.auto_enrolled where via = 'exam_result'));

-- skipped rows = legacy rows minus migrated rows
insert into legacy_report.migration_report
select 'skipped', t, l - n from (
    select 'course' t, (select count(*) from legacy_yoklama.course) l, (select count(*) from course) n union all
    select 'roster_entry', (select count(*) from legacy_yoklama.roster_entry),
           (select count(*) from legacy_yoklama.roster_entry where course_id in (select id from kept_course)) union all
    select 'class_session', (select count(*) from legacy_yoklama.class_session), (select count(*) from class_session) union all
    select 'calibration_run', (select count(*) from legacy_yoklama.calibration_run), (select count(*) from calibration_run) union all
    select 'checkin_attempt', (select count(*) from legacy_yoklama.checkin_attempt), (select count(*) from checkin_attempt) union all
    select 'scan_record', (select count(*) from legacy_yoklama.scan_record), (select count(*) from scan_record) union all
    select 'attendance', (select count(*) from legacy_yoklama.attendance), (select count(*) from attendance) union all
    select 'exam', (select count(*) from legacy_yoklama.exam), (select count(*) from exam) union all
    select 'exam_question', (select count(*) from legacy_yoklama.exam_question), (select count(*) from exam_question) union all
    select 'exam_result', (select count(*) from legacy_yoklama.exam_result), (select count(*) from exam_result) union all
    select 'exam_answer', (select count(*) from legacy_yoklama.exam_answer), (select count(*) from exam_answer) union all
    select 'qp app_user', (select count(*) from legacy_qp.app_user), (select count(*) from qp_person) union all
    select 'qp attempt', (select count(*) from legacy_qp.attempt), (select count(*) from question_attempt) union all
    select 'qp comment', (select count(*) from legacy_qp.comment), (select count(*) from question_comment) union all
    select 'qp flag', (select count(*) from legacy_qp.flag), (select count(*) from question_flag)
) x;

\echo
\echo ==== migration report (counts only) ====
select section, metric, value from legacy_report.migration_report order by
    case section when 'target' then 1 when 'people' then 2 when 'enrollment' then 3 else 4 end, metric;

commit;
\echo migrate.sql committed. Now run verify.sql.
