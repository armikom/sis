-- Links every SENG 403 pool question to the topic(s) it tests.
-- A topic is a numbered level-2 heading "## N. Title" of a chapter text (doc_en; doc_tr has the same numbers).
-- Each question was read (stem, options, rationales, the author's reference) together with the chapter sections;
-- a question is linked to every section a student would need to re-read to answer it. Some questions also need a
-- section of another chapter (e.g. the RIPR model of Week 2); those links are listed under the question's own chapter.
--
-- Run AFTER migrate.sql, verify.sql and link-midterm1.sql, AND after the SIS application has been started once on
-- the migrated database: the application creates the topic rows (table topic) from the chapter headings itself.
--   psql "$URL" -v ON_ERROR_STOP=1 -f link-topics.sql
-- Re-runnable: existing links are kept (on conflict do nothing); nothing is deleted.
begin;

create temp table topic_link(question_id bigint, chapter_number int, topic_number int,
                             primary key (question_id, chapter_number, topic_number)) on commit drop;
insert into topic_link values
    -- Week 1 - Introduction to Software Testing
    (1, 1, 4),                  -- V&V
    (2, 1, 8),                  -- Test levels (unit test with a stub)
    (3, 1, 2),                  -- Fault, error, failure
    (4, 1, 2),                  -- Fault, error, failure
    (13, 1, 2), (13, 2, 3),     -- Fault, error, failure + RIPR (which test reveals the failure)
    (14, 1, 4),                 -- V&V (validation question)
    (15, 1, 7),                 -- Testing basic facts (black-box)
    (16, 1, 6),                 -- Technical reviews (most effective, often skipped)
    (27, 1, 2),                 -- Fault, error, failure
    (28, 1, 4),                 -- V&V
    (36, 1, 7),                 -- Testing basic facts (goal: find defects)
    (37, 1, 7),                 -- Testing basic facts (exhaustive testing)
    (63, 1, 1), (63, 1, 7),     -- Why test + testing basic facts (purpose of testing)
    (74, 1, 7),                 -- Testing basic facts (black-box)
    (75, 1, 3),                 -- Triangle program (Myers)
    (76, 1, 7),                 -- Testing basic facts (defects cluster)

    -- Week 2 - Test Design
    (5, 2, 3),                  -- RIPR (propagation)
    (11, 2, 7),                 -- Black-box, white-box, model-based
    (19, 2, 8),                 -- Four test activities (design, automation)
    (31, 2, 8),                 -- Four test activities (automation)
    (32, 2, 9),                 -- MDTD (edge-pair coverage)
    (39, 2, 3),                 -- RIPR (no assertion: not revealed)
    (48, 2, 6), (48, 1, 7),     -- Coverage criteria (input space) + exhaustive path testing (Week 1)
    (64, 2, 5),                 -- Test levels (integration)
    (77, 2, 2),                 -- Testing and debugging
    (78, 2, 8),                 -- Four test activities (evaluation)
    (97, 2, 3),                 -- RIPR (propagation masked)

    -- Week 3 - Test Automation
    (6, 3, 1),                  -- What is test automation (benefits)
    (7, 3, 2),                  -- Testability
    (25, 3, 2),                 -- Testability (inject clock, return result)
    (29, 3, 2),                 -- Testability (replaceable clock)
    (30, 3, 6), (30, 3, 4),     -- FIRST (independent, shared state) + JUnit lifecycle
    (41, 3, 6),                 -- FIRST (fast) - closest; unit vs UI tests is not in the chapter
    (59, 3, 4),                 -- JUnit (failure vs error)
    (79, 3, 3),                 -- Four parts of a test case (prefix)
    (80, 3, 1),                 -- Revenue and excise tasks
    (81, 3, 5),                 -- Data-driven tests
    (82, 3, 6),                 -- Test smells (no assertion)
    (83, 3, 6), (83, 2, 3),     -- Weak tests + RIPR (Week 2)
    (99, 3, 2),                 -- Testability

    -- Week 4 - Traditional vs Agile Testing
    (8, 4, 1),                  -- Traditional way (V-Model)
    (17, 4, 4), (17, 4, 2),     -- Agile testing (automation vs TDD) + TDD cycle
    (21, 4, 3),                 -- Traditional vs agile (change)
    (24, 4, 3),                 -- Traditional vs agile
    (35, 4, 2),                 -- Agile way (Scrum Master)
    (38, 4, 2),                 -- Agile way (XP pair programming)
    (40, 4, 2),                 -- Agile way (Sprint)
    (46, 4, 2),                 -- Agile way (Product Owner)
    (47, 4, 2),                 -- Agile way (Daily Scrum)
    (50, 4, 2),                 -- Agile way (INVEST)
    (51, 4, 2),                 -- Agile way (XP iteration change) - closest
    (54, 4, 2),                 -- Agile way (TDD red)
    (65, 4, 2),                 -- Agile way (manifesto)
    (84, 4, 2),                 -- Agile way (manifesto)
    (85, 4, 2),                 -- Agile way (Increment, Sprint Review)
    (86, 4, 4),                 -- Agile testing (testing shortfall)
    (98, 4, 1), (98, 4, 3),     -- Traditional way (late feedback) + traditional vs agile

    -- Week 5 - Testing Criteria
    (12, 6, 4), (12, 6, 7),     -- Week 6: static vs dynamic + user story review (no Week 5 section fits)
    (20, 5, 7),                 -- Statement vs branch coverage
    (33, 5, 7),                 -- Statement vs branch coverage (branch coverage level)
    (34, 5, 3),                 -- Coverage level (minimal vs minimum)
    (60, 5, 7),                 -- Statement vs branch coverage
    (66, 5, 6),                 -- Subsumption
    (67, 5, 7),                 -- Statement vs branch coverage
    (68, 5, 6),                 -- Subsumption
    (71, 5, 3),                 -- Coverage level (more tests vs coverage)
    (72, 5, 9), (72, 2, 3),     -- Four structures and RIPR + RIPR model (Week 2)
    (87, 5, 4),                 -- Infeasible test requirements
    (88, 5, 6),                 -- Subsumption
    (89, 5, 3),                 -- Coverage level
    (90, 5, 5),                 -- Generator and recognizer

    -- Week 6 - Static Testing
    (9, 6, 4),                  -- Static and dynamic testing
    (10, 6, 5),                 -- Review process (individual review)
    (18, 6, 4),                 -- Static and dynamic testing
    (26, 3, 1),                 -- Week 3: regression testing (no Week 6 section fits)
    (44, 6, 4), (44, 6, 7),     -- Static testing + requirements review example
    (45, 6, 5), (45, 6, 6),     -- Review roles + Myers inspection (find, do not fix)
    (52, 6, 5),                 -- Review types (walkthrough)
    (53, 6, 5), (53, 6, 6),     -- Review types (inspection) + Myers inspection
    (58, 6, 6),                 -- Myers checklist (comparison)
    (69, 6, 2),                 -- Seven principles (pesticide paradox)
    (70, 6, 5),                 -- Review types (walkthrough)
    (91, 6, 3),                 -- Test process (analysis)
    (92, 6, 8),                 -- Static analysis tools
    (93, 6, 5),                 -- Review roles (scribe)

    -- Week 7 - Specification-Based Testing
    (22, 7, 5),                 -- BVA
    (23, 7, 4),                 -- EP (one invalid value per test, masking)
    (42, 7, 7),                 -- State transition testing (invalid transition)
    (43, 7, 6),                 -- Decision tables (collapsing)
    (56, 7, 4),                 -- EP (invalid partition)
    (57, 7, 5),                 -- BVA (2-value)
    (61, 7, 4),                 -- EP (list of options)
    (62, 7, 5),                 -- BVA (2-value)
    (73, 7, 4),                 -- EP (masking)
    (94, 7, 5),                 -- BVA (3-value)
    (95, 7, 6),                 -- Decision tables (2^n columns)
    (96, 7, 7);                 -- State transition testing (counting tests)

do $$
declare
    cid bigint;
    n   int;
    ids text;
begin
    begin
        select id into strict cid from course where code = 'SENG 403';
    exception
        when no_data_found then raise exception 'course SENG 403 not found';
        when too_many_rows then raise exception 'more than one course with code SENG 403';
    end;

    select string_agg(distinct l.question_id::text, ', '), count(distinct l.question_id) into ids, n
      from topic_link l
      left join question q on q.id = l.question_id
      left join chapter ch on ch.id = q.chapter_id and ch.course_id = cid
     where ch.id is null;
    if n > 0 then raise exception '% question(s) missing or not in SENG 403: %', n, ids; end if;

    select count(*) into n from topic t join chapter ch on ch.id = t.chapter_id where ch.course_id = cid;
    if n = 0 then
        raise exception 'no topic rows for SENG 403: start the application once so topics are created';
    end if;

    select string_agg(distinct l.chapter_number || '.' || l.topic_number, ', '),
           count(distinct (l.chapter_number, l.topic_number)) into ids, n
      from topic_link l
      left join chapter ch on ch.course_id = cid and ch.number = l.chapter_number
      left join topic t on t.chapter_id = ch.id and t.number = l.topic_number
     where t.id is null;
    if n > 0 then
        raise exception '% topic(s) not found (chapter.topic): % - start the application once so topics are created', n, ids;
    end if;

    select string_agg(q.id::text, ', ' order by q.id), count(*) into ids, n
      from question q
      join chapter ch on ch.id = q.chapter_id
     where ch.course_id = cid
       and not exists (select 1 from topic_link l where l.question_id = q.id);
    if n > 0 then raise exception '% SENG 403 question(s) have no topic link: %', n, ids; end if;

    insert into question_topic(question_id, topic_id)
    select l.question_id, t.id
      from topic_link l
      join chapter ch on ch.course_id = cid and ch.number = l.chapter_number
      join topic t on t.chapter_id = ch.id and t.number = l.topic_number
    on conflict do nothing;
end $$;

-- Report: links per chapter (chapter of the topic)
select ch.number as week, count(*) as links
  from question_topic qt
  join topic t on t.id = qt.topic_id
  join chapter ch on ch.id = t.chapter_id
  join course c on c.id = ch.course_id
 where c.code = 'SENG 403'
 group by 1 order by 1;

-- Report: questions per topic
select ch.number as week, t.number as topic, t.title_en, count(qt.question_id) as questions
  from topic t
  join chapter ch on ch.id = t.chapter_id
  join course c on c.id = ch.course_id
  left join question_topic qt on qt.topic_id = t.id
 where c.code = 'SENG 403'
 group by 1, 2, 3 order by 1, 2;

-- Report: questions with two or more topics
select count(*) as questions_with_2plus_topics
  from (select qt.question_id
          from question_topic qt
          join question q on q.id = qt.question_id
          join chapter ch on ch.id = q.chapter_id
          join course c on c.id = ch.course_id
         where c.code = 'SENG 403'
         group by 1 having count(*) >= 2) x;

-- Report: topics of the Midterm 1 questions
select eq.position,
       string_agg(tc.number || '.' || t.number || ' ' || t.title_en, ' | ' order by tc.number, t.number) as topics
  from exam e
  join course c on c.id = e.course_id
  join exam_question eq on eq.exam_id = e.id
  left join question_topic qt on qt.question_id = eq.pool_question_id
  left join topic t on t.id = qt.topic_id
  left join chapter tc on tc.id = t.chapter_id
 where c.code = 'SENG 403' and e.title = 'Midterm 1'
 group by eq.position order by eq.position;

commit;
