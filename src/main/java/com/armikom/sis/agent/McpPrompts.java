package com.armikom.sis.agent;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ready-made prompts the MCP client can show as commands (for example "/study_chapter" in Claude Code). Student
 * prompts are listed for people with a student enrollment, staff prompts for course staff. Every prompt takes an
 * optional {@code course}.
 */
final class McpPrompts {

    private record Prompt(String name, String title, String description, boolean staff, List<Arg> args,
                          String text) {
    }

    private record Arg(String name, String description, boolean required) {
    }

    private static final Arg COURSE = new Arg("course", "Course code or id (empty = your only course)", false);

    private static final List<Prompt> PROMPTS = List.of(
            new Prompt("study_chapter", "Study a chapter", "Learn one chapter step by step, then answer its questions.",
                    false, List.of(new Arg("week", "Chapter (week) number", true), COURSE), """
                            Let's study chapter {week}{course_text}.
                            1. Call get_progress and read my saved notes for chapter {week}.
                            2. Call read_chapter for chapter {week}. Teach me the main ideas in small parts, with short \
                            examples. After each part, ask me one quick check question in your own words.
                            3. Then use next_question for chapter {week}. Show me the stem and options; I choose. Do not \
                            give hints before I choose.
                            4. Save my weak points with update_study_state, and write log_study_session at the end."""),
            new Prompt("quiz_me", "Quiz me", "Answer new pool questions one by one.", false,
                    List.of(new Arg("week", "Chapter (week) number (empty = any chapter)", false),
                            new Arg("count", "How many questions (default 5)", false), COURSE),
                    """
                            Quiz me with {count} new questions from the pool{week_text}{course_text}. Use next_question, \
                            show the stem and the four options, and wait for my choice. Send my choice with \
                            answer_question, then explain the result shortly. At the end, give a short score, save weak \
                            points with update_study_state and write log_study_session."""),
            new Prompt("review_mistakes", "Review my mistakes",
                    "Practise again the questions you answered wrong.", false,
                    List.of(new Arg("week", "Chapter (week) number (empty = any chapter)", false), COURSE), """
                            Help me review my mistakes{week_text}{course_text}. Use next_review_question, wait for my \
                            choice, check it with practice_answer and explain with the chapter text. Continue until the \
                            queue has no wrong answers or I stop. Then update my study state and write \
                            log_study_session."""),
            new Prompt("improve_my_question", "Get feedback on my question",
                    "Feedback on a question you are writing. The agent does not write it for you.", false,
                    List.of(new Arg("week", "Chapter (week) number", true), COURSE), """
                            I want to write a question for chapter {week}{course_text}. First call list_questions for \
                            chapter {week} so we avoid a duplicate, and read_chapter for chapter {week}. Then I will \
                            paste my draft. Check it like a CTFL item writer: one clear stem, exactly one correct option, \
                            believable wrong options, a reason for every option, a reference to the chapter text, simple \
                            English. Tell me what to fix, but do not write the question for me. When I say it is ready, \
                            show the final version and ask before submit_question."""),
            new Prompt("review_exam", "Learn from my exam",
                    "Go through the topics you missed in a published exam: read, explain, practise.", false,
                    List.of(new Arg("exam", "Exam id (empty = the latest published exam)", false), COURSE), """
                            Help me learn from my exam{exam_text}{course_text}.
                            1. Call my_exam_results{exam_call}. Show my score next to the class stats and which \
                            questions I missed. For a missed question use its options, their rationales and the \
                            explanation to show why my answer was wrong and why the key is right; the question stats \
                            show how the class did.
                            2. Take the reviewTopics, most missed first. For each topic: call read_topic with its id, \
                            explain the main ideas simply with short examples, and show how they answer the question I \
                            missed. Ask me one quick check question in your own words.
                            3. Practise the topic: call next_question with topic=<topic id>, show the stem and the four \
                            options, wait for my choice and send it with answer_question. When no new questions are \
                            left, use next_review_question with the same topic and practice_answer.
                            4. At the end, save my weak points with update_study_state and write log_study_session."""),
            new Prompt("study_plan", "Make my study plan",
                    "Look at exams, weak topics and pool progress, then plan the week.", false,
                    List.of(COURSE), """
                            Make me a study plan{course_text}.
                            1. Call my_overview. Tell me shortly where I stand: exam totals next to the class, weak \
                            topics, and (if the course has the pool) how many questions I still have \
                            to answer or write.
                            2. For the weak topics call read_topic and explain the main idea of each in a few \
                            sentences; link them to the exam questions I missed (my_exam_results with the exam id).
                            3. Propose a plan for the next 7 days: which topic to read, which questions to practise \
                            (next_question with topic, next_review_question), and when to write my own questions. \
                            Keep it short and realistic. Ask me to confirm, then save the weak points with \
                            update_study_state and write log_study_session."""),
            new Prompt("review_pending", "Review waiting questions",
                    "Go through the waiting questions with the AI check and statistics.", true,
                    List.of(new Arg("week", "Chapter (week) number (empty = all chapters)", false), COURSE), """
                            Let's review the waiting questions{week_text}{course_text}. Use list_questions_for_review \
                            (PENDING). For each question give me: a one-line summary, whether the key is right, problems \
                            (clarity, one correct answer, distractors, duplicates, chapter fit), and your suggestion \
                            (APPROVE, REVISE with a simple-English note, or REJECT). Wait for my decision before \
                            calling decide_question."""),
            new Prompt("tag_exam_topics", "Tag the exam questions with topics",
                    "Propose a topic for every question of an exam, then link them after your approval.", true,
                    List.of(new Arg("exam", "Exam id (empty = the latest exam)", false), COURSE), """
                            Let's tag the questions of the exam{exam_text}{course_text} with topics.
                            1. Call list_exams{exam_pick} and get_exam with that exam_id; call list_topics for the course.
                            2. For every question (use its label), read the text (or html) and the options and propose \
                            the best matching topic or topics; quote the topic as "chapter.number title". Mark the \
                            questions where you are unsure. Questions linked to a pool question already carry that \
                            question's topics: show them, propose extra ones only when needed.
                            3. Show me the whole table and wait for my corrections. Then call set_exam_question_topics \
                            for each question. Finish with topic_stats for the chapters of the exam."""),
            new Prompt("weekly_report", "Weekly class report",
                    "Participation, study state and question quality in one report.", true, List.of(COURSE), """
                            Write a short weekly report for me in Turkish{course_text}. Use class_overview, \
                            question_counts, study_overview, question_stats and list_flags. Include: students with low \
                            participation (no questions or few answers), chapters that need more approved questions for \
                            the exam, the most common weak points from the students' study notes, questions with a \
                            possibly wrong key or weak distractors, and open problem reports."""));

    private McpPrompts() {
    }

    static List<Map<String, Object>> list(boolean student, boolean staff) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Prompt p : PROMPTS) {
            if (p.staff() ? !staff : !student) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("title", p.title());
            m.put("description", p.description());
            m.put("arguments", p.args().stream()
                    .map(a -> Map.of("name", a.name(), "description", a.description(), "required", a.required()))
                    .toList());
            out.add(m);
        }
        return out;
    }

    static Map<String, Object> get(boolean student, boolean staff, String name, JsonNode args) {
        Prompt p = PROMPTS.stream().filter(x -> x.name().equals(name) && (x.staff() ? staff : student))
                .findFirst().orElseThrow(() -> new McpController.RpcError(-32602, "Unknown prompt: " + name));
        String week = value(args, "week");
        String count = value(args, "count");
        String course = value(args, "course");
        String exam = value(args, "exam");
        for (Arg a : p.args()) {
            if (a.required() && value(args, a.name()).isEmpty()) {
                throw new McpController.RpcError(-32602, "The argument '" + a.name() + "' is required.");
            }
        }
        String text = p.text().replace("{week}", week).replace("{count}", count.isEmpty() ? "5" : count)
                .replace("{week_text}", week.isEmpty() ? "" : " for chapter " + week)
                .replace("{exam_text}", exam.isEmpty() ? "" : " (exam id " + exam + ")")
                .replace("{exam_pick}", exam.isEmpty() ? " and pick the latest exam" : "")
                .replace("{exam_call}", exam.isEmpty()
                        ? " without exam_id, pick the latest published exam, then call it again with that exam_id"
                        : " with exam_id=" + exam)
                .replace("{course_text}", course.isEmpty() ? "" : " in course " + course
                        + " (pass course=" + course + " to the tools)");
        return Map.of("description", p.description(), "messages",
                List.of(Map.of("role", "user", "content", Map.of("type", "text", "text", text))));
    }

    private static String value(JsonNode args, String name) {
        JsonNode v = args == null ? null : args.path(name);
        return v == null || v.isMissingNode() || v.isNull() || !v.isValueNode() ? "" : v.asString().trim();
    }
}
