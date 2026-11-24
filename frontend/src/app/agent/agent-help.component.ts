import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { getItem, setItem } from '../core/storage';

/**
 * User guide for the AI agent (MCP), English and Turkish. Content kept from question-pool's AgentHelp page, with the
 * server name "sis" and the multi-course notes (course argument, list_courses).
 */
@Component({
  selector: 'app-agent-help',
  imports: [RouterLink],
  template: `
<div class="agent-help" (click)="onClick($event)">

  <div class="spread">
    <h1>{{ lang === 'en' ? 'How to use the AI agent (MCP)' : 'Yapay zekâ ajanı (MCP) nasıl kullanılır?' }}</h1>
  </div>
  <div class="tabs">
    <button type="button" [class.on]="lang === 'en'" (click)="setLang('en')">English</button>
    <button type="button" [class.on]="lang === 'tr'" (click)="setLang('tr')">Türkçe</button>
  </div>

  <!-- =====================================================================  ENGLISH  ===== -->
  @if (lang === 'en') { <div class="doc">
    <nav class="card small">
      <strong>On this page:</strong>
      <a href="#what">What is it?</a> · <a href="#need">What you need</a> · <a href="#setup">Set up</a> ·
      <a href="#clients">Steps for each app</a> · <a href="#first">First session</a> · <a href="#tools">What the agent can do</a> ·
      @if (!teacher) { <a href="#progress">Your progress</a> } · <a href="#rules">Rules</a> · <a href="#trouble">Problems</a> ·
      <a href="#privacy">Privacy</a>
    </nav>

    <h2 id="what">1. What is it?</h2>
    <p>
      <strong>MCP</strong> (Model Context Protocol) lets an AI app talk to this site. After you connect it, you do
      not copy and paste anything: the agent itself
      @if (!teacher) {reads the chapter texts and topics, asks you questions from the pool, checks your answers,
        goes through your exam results with you and remembers what you studied.}
      @else {reads the course for you — roster, chapter texts and topics, the question pool, exams and results,
        attendance — answers your questions about it and, when you say so, changes it: edits a chapter, approves
        questions, builds or edits an exam, enters results, opens an attendance session.}
    </p>
    @if (!teacher) { <p>
      The exams of a pool course are made of approved questions from its pool. So studying the pool with an agent is real
      exam practice.
    </p> }

    <h2 id="need">2. What you need</h2>
    <ul>
      <li>An AI app that supports MCP: <strong>Claude Code</strong>, <strong>Claude Desktop</strong>,
        <strong>VS Code</strong> (GitHub Copilot, agent mode), <strong>Cursor</strong>, <strong>Codex CLI</strong> or
        <strong>Gemini CLI</strong>. Free plans usually work, with daily limits.</li>
      <li>Your personal <strong>token</strong> from the <a routerLink="/agent">AI agent</a> page. It is like a
        password: it says to the site "this is me".</li>
      <li>A computer. MCP apps do not run on a phone.</li>
    </ul>
    <div class="note amber small">
      The claude.ai web site and the ChatGPT web site cannot connect: they need a different login method (OAuth).
      Use one of the apps above.
    </div>

    <h2 id="setup">3. Set up in three steps</h2>
    <ol>
      <li>Open <a routerLink="/agent">AI agent</a> and press <strong>Create my token</strong>. Copy it now:
        it is shown only once.</li>
      <li>On the same page, choose your app and copy the ready text. Your token is already inside it. Put it where
        the page says (see <a href="#clients">steps for each app</a>).</li>
      <li>Restart the app and write: <code>Call whoami from sis.</code> The agent must answer with your
        nickname and your courses@if (teacher) {<span> (with your role in each)</span>}. Done.</li>
    </ol>
    <p class="small muted">
      Lost the token, or a friend saw it? Press <strong>Create a new token</strong>. The old token stops working at
      once. Then put the new token in your app.
    </p>

    <h2 id="clients">4. Steps for each app</h2>
    <details>
      <summary><strong>Claude Code</strong> (terminal)</summary>
      <ol>
        <li>Copy the <code>claude mcp add …</code> command from the AI agent page and run it in a terminal.</li>
        <li>Start <code>claude</code>. Write <code>/mcp</code>: <em>sis</em> must be "connected".</li>
        <li>Prompts appear as commands, for example
          <code>{{ teacher ? '/sis:weekly_report' : '/sis:study_chapter' }}</code> (the exact name can differ
          a little between versions; type <code>/</code> and look for sis).</li>
      </ol>
    </details>
    <details>
      <summary><strong>Claude Desktop</strong></summary>
      <ol>
        <li>Install <a href="https://nodejs.org" target="_blank" rel="noopener">Node.js</a> (LTS) first.</li>
        <li>Claude Desktop → <em>Settings → Developer → Edit Config</em>. This opens
          <code>claude_desktop_config.json</code>.</li>
        <li>Paste the text from the AI agent page. If the file already has <code>"mcpServers"</code>, add only the
          <code>"sis"</code> part inside it.</li>
        <li>Quit Claude Desktop fully and open it again. The tools icon in the chat box shows <em>sis</em>.</li>
      </ol>
    </details>
    <details>
      <summary><strong>VS Code</strong> (GitHub Copilot)</summary>
      <ol>
        <li>Command palette → <em>MCP: Open User Configuration</em> (or create <code>.vscode/mcp.json</code> in a
          folder).</li>
        <li>Paste the text from the AI agent page and press <em>Start</em> above <code>sis</code>.</li>
        <li>Open Copilot Chat, choose <strong>Agent</strong> mode, and check the tools list.</li>
      </ol>
    </details>
    <details>
      <summary><strong>Cursor</strong></summary>
      <ol>
        <li>Open <code>~/.cursor/mcp.json</code> (Settings → MCP → Add new server opens it).</li>
        <li>Paste the text from the AI agent page and save. <em>sis</em> must show a green dot.</li>
      </ol>
    </details>
    <details>
      <summary><strong>Codex CLI</strong></summary>
      <ol>
        <li>Add the <code>[mcp_servers.sis]</code> part to <code>~/.codex/config.toml</code>.</li>
        <li>Set the token in your shell: <code>export SIS_TOKEN="qp_…"</code> (put it in
          <code>~/.zshrc</code> or <code>~/.bashrc</code> to keep it).</li>
        <li>Start <code>codex</code> and write <code>/mcp</code> to see the server.</li>
      </ol>
    </details>
    <details>
      <summary><strong>Gemini CLI</strong></summary>
      <ol>
        <li>Add the text to <code>~/.gemini/settings.json</code> (inside <code>"mcpServers"</code>).</li>
        <li>Start <code>gemini</code> and write <code>/mcp</code> to see the tools.</li>
      </ol>
    </details>

    <h2 id="first">5. Your first session</h2>
    @if (!teacher) {
      <p>Think of the agent as a tutor who has read your course. You say what you want in your own words — in
        English or Turkish — and it chooses the right tools itself. It will not give you an answer before you choose
        one, it does not write your questions for you, and everything it learns about you (weak topics, notes, what
        you studied) is saved on the site, so you can continue in another app or on another day.</p>
      <p>What people use it for:</p>
      <ul>
        <li><strong>Understanding a topic</strong> — have a chapter or a single topic explained in small parts, with
          examples and check questions, in the language you prefer.</li>
        <li><strong>Exam practice</strong> — answer pool questions by topic or by week; the exams are built from the
          same pool.</li>
        <li><strong>Learning from an exam</strong> — go through a published exam: what you missed, why the key is
          right, which topics to read again, then practise exactly those topics.</li>
        <li><strong>Keeping track</strong> — your progress, weak topics, and exam totals next to the class in
          one overview; a study plan for the week.</li>
        <li><strong>Writing questions</strong> — feedback on your draft before you submit it.</li>
      </ul>
      <p>A short example:</p>
      <div class="card small chat">
        <p><strong>You:</strong> Show my progress and tell me what to study next.</p>
        <p><strong>Agent:</strong> <em>(calls get_progress)</em> You answered 4 of 11 questions in week 6. One answer
          was wrong. Shall we start with week 6?</p>
        <p><strong>You:</strong> Yes. Teach me the main ideas, then ask me 3 questions.</p>
        <p><strong>Agent:</strong> <em>(read_chapter)</em> … <em>(next_question)</em> Question 1: Which activity is
          static testing? A) … B) … C) … D) … Which one do you choose?</p>
        <p><strong>You:</strong> B</p>
        <p><strong>Agent:</strong> <em>(answer_question with B)</em> Correct! A review checks a work product without
          running it …</p>
        <p><strong>You:</strong> That's enough for today.</p>
        <p><strong>Agent:</strong> <em>(update_study_state, log_study_session)</em> I saved your notes. Next time we
          start with the question you got wrong.</p>
      </div>
      <p>Useful sentences:</p>
      <ul>
        <li>"Show my progress and tell me what to study next."</li>
        <li>"Teach me week 7 in small parts. Ask me a check question after each part."</li>
        <li>"Quiz me with 5 new pool questions from week 6."</li>
        <li>"Let's review the questions I got wrong."</li>
        <li>"I wrote a question for week 5. Give me feedback, but do not write it for me."</li>
        <li>"Explain this answer again in Turkish."</li>
        <li>"Go through my midterm: what did I miss and which topics should I read again?"</li>
      </ul>
      <p>Ready prompts (if your app shows them): <code>study_plan</code>, <code>study_chapter</code>,
        <code>quiz_me</code>, <code>review_mistakes</code>, <code>review_exam</code>,
        <code>improve_my_question</code>.</p>
    }
    @else {
      <p>Think of the agent as an assistant who has read your course and can also edit it. You say what you want in
        your own words — Turkish or English — and it chooses the tools itself. It only reads until you ask for a
        change; before every change it shows what it is about to do and waits for your yes. It cannot delete
        people, exams or sessions; those stay on the web site.</p>
      <p>What it is good for:</p>
      <ul>
        <li><strong>Course content</strong> — add or rewrite a chapter section (that is how topics are made),
          link pool and exam questions to topics, see which topics the class finds hard.</li>
        <li><strong>Question review</strong> — go through the waiting questions with the AI check and statistics,
          find questions with a doubtful key or weak distractors, answer problem reports.</li>
        <li><strong>Exams</strong> — build an exam from the pool or write one from scratch, add or edit questions,
          options and rationales, write the explanations students see after publishing, tag questions with topics.</li>
        <li><strong>Results</strong> — enter or correct result sheets, publish, read the analysis per question, topic
          and student.</li>
        <li><strong>Attendance</strong> — open and close a session, add late students by hand, decide flagged
          records, get the attendance report of the course or of one student, export a session.</li>
        <li><strong>Following students</strong> — participation, study notes, weak topics, attendance and exam
          history of one student or of the whole class in one report.</li>
      </ul>
      <p>Examples:</p>
      <ul>
        <li>"7. haftanın bekleyen sorularını birlikte inceleyelim. Her biri için öneri ver, kararı ben vereyim."</li>
        <li>"Write a weekly report: who is behind, which chapters need more approved questions?"</li>
        <li>"Which approved questions may have a wrong key or a weak distractor?"</li>
        <li>"Tag every question of Midterm 1 with a topic; show me the table before you save."</li>
        <li>"Add a section on Karnaugh maps to chapter 6, in English and Turkish."</li>
        <li>"Open today's attendance session for 15 minutes, then add these three late students."</li>
        <li>"Ayşe Kaya'nın durumunu göster: soruları, cevapları, çalışma notları, yoklaması."</li>
        <li>"Build an exam with 3 questions from each week, 2 forms." (print it on the Exams page)</li>
      </ul>
      <p>Ready prompts: <code>review_pending</code>, <code>weekly_report</code>, <code>tag_exam_topics</code>.</p>
      <div class="note small">
        The agent is told to show its plan and ask you before every change (approve, reject, edit, exam, results,
        attendance, import, chapters). Read the plan before you say yes; replacing a whole exam or removing an exam
        question also deletes the stored answers.
      </div>
    }

    <h2 id="tools">6. What the agent can do</h2>
    <div class="note small">
      One token works for all your SIS courses. <code>list_courses</code> shows them, and the other tools take a
      <code>course</code> argument (the course code, for example <code>SENG 403</code>). Tell the agent which course
      you mean, or say it once at the start: "We work on course X today."
    </div>
    @if (!teacher) { <table>
      <thead><tr><th>Area</th><th>Tools</th></tr></thead>
      <tbody>
        <tr><td>Courses</td><td><code>list_courses</code>, <code>whoami</code>, <code>my_overview</code></td></tr>
        <tr><td>Learn</td><td><code>read_chapter</code>, <code>list_chapters</code>, <code>list_topics</code>, <code>read_topic</code></td></tr>
        <tr><td>Exams</td><td><code>my_exam_results</code>, <code>review_topics</code></td></tr>
        <tr><td>Answer new questions</td><td><code>next_question</code> → <code>answer_question</code> (first answer, counts)</td></tr>
        <tr><td>Practise again</td><td><code>next_review_question</code> → <code>practice_answer</code> (does not change the first answer)</td></tr>
        <tr><td>Progress</td><td><code>get_progress</code>, <code>update_study_state</code>, <code>log_study_session</code></td></tr>
        <tr><td>Your questions</td><td><code>my_questions</code>, <code>submit_question</code>, <code>revise_question</code>, <code>list_questions</code></td></tr>
        <tr><td>Discussion</td><td><code>get_question</code>, <code>get_comments</code>, <code>post_comment</code>, <code>flag_question</code></td></tr>
      </tbody>
    </table> }
    @else { <table>
      <thead><tr><th>Area</th><th>Tools</th></tr></thead>
      <tbody>
        <tr><td>Participation</td><td><code>class_overview</code>, <code>student_detail</code>, <code>study_overview</code>, <code>import_students</code></td></tr>
        <tr><td>Review</td><td><code>question_counts</code>, <code>list_questions_for_review</code>, <code>decide_question</code>, <code>edit_question</code>, <code>question_stats</code></td></tr>
        <tr><td>Reports</td><td><code>list_flags</code>, <code>resolve_flag</code>, <code>hide_comment</code></td></tr>
        <tr><td>Roster</td><td><code>course_roster</code>, <code>add_student</code>, <code>import_students</code></td></tr>
        <tr><td>Chapters, topics</td><td><code>create_chapter</code>, <code>update_chapter</code>, <code>set_chapter_open</code>, <code>list_topics</code>, <code>sync_topics</code>, <code>set_question_topics</code>, <code>topic_stats</code></td></tr>
        <tr><td>Exams</td><td><code>list_exams</code>, <code>get_exam</code>, <code>create_exam</code>, <code>create_exam_from_pool</code>, <code>add_exam_question</code>, <code>update_exam_question</code>, <code>set_exam_question_topics</code>, <code>remove_exam_question</code>, <code>exam_results</code>, <code>save_exam_result</code>, <code>exam_analysis</code>, <code>publish_exam</code></td></tr>
        <tr><td>Attendance</td><td><code>list_sessions</code>, <code>create_session</code>, <code>open_session</code>, <code>close_session</code>, <code>session_attendances</code>, <code>decide_attendance</code>, <code>add_attendance</code>, <code>attendance_report</code>, <code>student_attendance</code>, <code>session_export</code></td></tr>
        <tr><td>Shared</td><td><code>list_courses</code>, <code>whoami</code>, <code>list_chapters</code>, <code>read_chapter</code>, <code>list_questions</code>, <code>get_question</code>, <code>get_comments</code>, <code>post_comment</code></td></tr>
      </tbody>
    </table> }
    <p class="small muted">The agent cannot delete people, roster entries, exams or sessions. Use the web site for
      those. Tools that change something ask you first.</p>

    @if (!teacher) {
      <h2 id="progress">7. How your progress is kept</h2>
      <p>Your progress is saved on the site, not in the AI app. You can continue tomorrow, on another computer, or
        with another app.</p>
      <ul>
        <li><strong>First answers</strong>: the answers that count (the same as on the web site).</li>
        <li><strong>Practice answers</strong>: when you answer an old question again. They decide what goes to the
          review list: a question is "to review" when your last answer to it was wrong.</li>
        <li><strong>Study state</strong> per chapter: <code>NOT_STARTED</code> → <code>STUDYING</code> →
          <code>REVIEWING</code> → <code>DONE</code>, a confidence from 1 to 5, and short notes about your weak
          points. You can tell the agent: "Mark week 6 as done, my confidence is 4."</li>
        <li><strong>Study diary</strong>: a few sentences at the end of each session. The next session starts from it.</li>
      </ul>
    }

    <h2 id="rules">{{ teacher ? 7 : 8 }}. Rules</h2>
    @if (!teacher) { <ul>
      <li><strong>You choose.</strong> The agent shows the question; you choose the option. Letting the agent answer
        for you only hurts your exam result, and only your first answer counts.</li>
      <li><strong>You write your questions.</strong> The agent gives feedback; it does not write them. The automatic
        check and the instructor see copied or AI-written questions.</li>
      <li>The agent does not solve lab work or homework.</li>
      <li>The agent asks before it posts a comment, reports a problem or sends a question. Read the text first.</li>
      <li>AI can be wrong. When the agent and the chapter text do not agree, the chapter text wins. Report a wrong
        key with <code>flag_question</code>.</li>
    </ul> }
    @else { <ul>
      <li>Every change is made in your name. Read the agent's plan before you say yes.</li>
      <li>Notes for students (REVISE, REJECT) must be in short, simple English.</li>
      <li>Real names and e-mail addresses are only for you. Do not let the agent put them in comments.</li>
      <li>Exams are printed on the web site (Question pool → Exams → Form A / Key A).</li>
    </ul> }

    <h2 id="trouble">{{ teacher ? 8 : 9 }}. Problems and answers</h2>
    <table>
      <thead><tr><th>You see</th><th>What to do</th></tr></thead>
      <tbody>
        <tr><td>401, "Missing or wrong MCP token"</td><td>The token is wrong, old or deleted. Create a new token and put it in your app again.</td></tr>
        <tr><td>No sis tools in the app</td><td>Restart the app. Check the config file (a missing comma breaks JSON). In Claude Code, write <code>/mcp</code>.</td></tr>
        <tr><td>"Answer the question first"</td><td>Normal: the discussion opens after you answer.</td></tr>
        <tr><td>"already answered … use practice_answer"</td><td>Normal: the first answer does not change. Practise it instead.</td></tr>
        <tr><td>"You already have 2 questions in this chapter"</td><td>The limit per chapter. Rejected questions do not count.</td></tr>
        <tr><td>"This chapter is closed for new questions"</td><td>The instructor closed it. You can still study and answer.</td></tr>
        <tr><td>Claude Desktop: "spawn npx ENOENT"</td><td>Node.js is not installed, or the app was opened before you installed it. Install Node.js and restart.</td></tr>
      </tbody>
    </table>

    <h2 id="privacy">{{ teacher ? 9 : 10 }}. Privacy and safety</h2>
    <ul>
      <li>Keep the token secret. Do not put it in a public GitHub repository or a screenshot.</li>
      <li>The site stores only a fingerprint of the token, not the token itself. Nobody can show it to you again.</li>
      @if (!teacher) { <li>The instructor can see your study state, notes and diary. Other students cannot; they see
        only your nickname next to your questions and comments.</li> }
      <li>The AI app sends what it reads (chapter texts, questions) to its own company. Your real name is not sent:
        the agent knows you by your nickname@if (teacher) { (except on the instructor tools, which show
          real names to you)}.</li>
      <li>Delete the token on the <a routerLink="/agent">AI agent</a> page when you stop using it.</li>
    </ul>
  </div> }

  <!-- =====================================================================  TÜRKÇE  ===== -->
  @else { <div class="doc">
    <nav class="card small">
      <strong>Bu sayfada:</strong>
      <a href="#what">Nedir?</a> · <a href="#need">Neler gerekir?</a> · <a href="#setup">Kurulum</a> ·
      <a href="#clients">Uygulamaya göre adımlar</a> · <a href="#first">İlk oturum</a> · <a href="#tools">Ajan neler yapabilir?</a> ·
      @if (!teacher) { <a href="#progress">İlerlemeniz</a> } · <a href="#rules">Kurallar</a> · <a href="#trouble">Sorunlar</a> ·
      <a href="#privacy">Gizlilik</a>
    </nav>

    <h2 id="what">1. Nedir?</h2>
    <p>
      <strong>MCP</strong> (Model Context Protocol), bir yapay zekâ uygulamasının bu siteyle konuşmasını sağlar.
      Bağladıktan sonra hiçbir şeyi kopyalayıp yapıştırmazsınız; ajan
      @if (!teacher) {bölüm metinlerini ve konuları kendisi okur, havuzdan size soru sorar, cevabınızı kontrol
        eder, sınav sonucunuzu sizinle birlikte inceler ve neler çalıştığınızı hatırlar.}
      @else {dersi sizin için okur — liste, bölüm metinleri ve konular, soru havuzu, sınavlar ve sonuçlar,
        yoklama — sorularınızı cevaplar ve siz söyleyince değiştirir: bölüm düzenler, soru onaylar, sınav hazırlar
        ya da düzenler, sonuç girer, yoklama oturumu açar.}
    </p>
    @if (!teacher) { <p>
      Havuz kullanan derslerin sınavları o havuzdaki onaylı sorulardan oluşur. Yani havuzu ajanla çalışmak gerçek sınav
      hazırlığıdır.
    </p> }

    <h2 id="need">2. Neler gerekir?</h2>
    <ul>
      <li>MCP destekleyen bir uygulama: <strong>Claude Code</strong>, <strong>Claude Desktop</strong>,
        <strong>VS Code</strong> (GitHub Copilot, agent modu), <strong>Cursor</strong>, <strong>Codex CLI</strong>
        ya da <strong>Gemini CLI</strong>. Ücretsiz planlar genellikle günlük sınırla çalışır.</li>
      <li><a routerLink="/agent">AI agent</a> sayfasından alacağınız kişisel <strong>token</strong>. Parola
        gibidir: siteye "bu benim" der.</li>
      <li>Bir bilgisayar. MCP uygulamaları telefonda çalışmaz.</li>
    </ul>
    <div class="note amber small">
      claude.ai ve ChatGPT web siteleri bağlanamaz; başka bir giriş yöntemi (OAuth) isterler. Yukarıdaki
      uygulamalardan birini kullanın.
    </div>

    <h2 id="setup">3. Üç adımda kurulum</h2>
    <ol>
      <li><a routerLink="/agent">AI agent</a> sayfasını açın, <strong>Create my token</strong> düğmesine
        basın. Token'ı hemen kopyalayın: yalnızca bir kez gösterilir.</li>
      <li>Aynı sayfada uygulamanızı seçin ve hazır metni kopyalayın; token'ınız içine yerleştirilmiştir. Sayfanın
        söylediği yere yapıştırın (<a href="#clients">uygulamaya göre adımlar</a>).</li>
      <li>Uygulamayı yeniden başlatın ve şunu yazın: <code>sis'ten whoami çağır.</code> Ajan takma adınızla
        ve derslerinizle@if (teacher) {<span> (her derste rolünüzle)</span>} cevap vermeli. Bu kadar.</li>
    </ol>
    <p class="small muted">
      Token'ı kaybettiniz ya da biri gördü mü? <strong>Create a new token</strong> düğmesine basın. Eski token hemen
      çalışmaz olur. Sonra yeni token'ı uygulamanıza yazın.
    </p>

    <h2 id="clients">4. Uygulamaya göre adımlar</h2>
    <details>
      <summary><strong>Claude Code</strong> (terminal)</summary>
      <ol>
        <li>AI agent sayfasındaki <code>claude mcp add …</code> komutunu kopyalayıp terminalde çalıştırın.</li>
        <li><code>claude</code> başlatın, <code>/mcp</code> yazın: <em>sis</em> "connected" görünmeli.</li>
        <li>Hazır komutlar <code>/</code> menüsünde görünür, örneğin
          <code>{{ teacher ? '/sis:weekly_report' : '/sis:study_chapter' }}</code> (adı sürüme göre biraz
          değişebilir; <code>/</code> yazıp sis'ü arayın).</li>
      </ol>
    </details>
    <details>
      <summary><strong>Claude Desktop</strong></summary>
      <ol>
        <li>Önce <a href="https://nodejs.org" target="_blank" rel="noopener">Node.js</a> (LTS) kurun.</li>
        <li>Claude Desktop → <em>Settings → Developer → Edit Config</em>; <code>claude_desktop_config.json</code>
          açılır.</li>
        <li>AI agent sayfasındaki metni yapıştırın. Dosyada zaten <code>"mcpServers"</code> varsa yalnızca
          <code>"sis"</code> kısmını onun içine ekleyin.</li>
        <li>Claude Desktop'u tamamen kapatıp yeniden açın. Sohbet kutusundaki araç simgesinde <em>sis</em>
          görünür.</li>
      </ol>
    </details>
    <details>
      <summary><strong>VS Code</strong> (GitHub Copilot)</summary>
      <ol>
        <li>Komut paleti → <em>MCP: Open User Configuration</em> (ya da bir klasörde <code>.vscode/mcp.json</code>).</li>
        <li>AI agent sayfasındaki metni yapıştırın, <code>sis</code> üstündeki <em>Start</em>'a basın.</li>
        <li>Copilot Chat'i açın, <strong>Agent</strong> modunu seçin ve araç listesine bakın.</li>
      </ol>
    </details>
    <details>
      <summary><strong>Cursor</strong></summary>
      <ol>
        <li><code>~/.cursor/mcp.json</code> dosyasını açın (Settings → MCP → Add new server).</li>
        <li>Metni yapıştırıp kaydedin. <em>sis</em> yanında yeşil nokta görünmeli.</li>
      </ol>
    </details>
    <details>
      <summary><strong>Codex CLI</strong></summary>
      <ol>
        <li><code>[mcp_servers.sis]</code> kısmını <code>~/.codex/config.toml</code> dosyasına ekleyin.</li>
        <li>Token'ı kabuğa tanımlayın: <code>export SIS_TOKEN="qp_…"</code> (kalıcı olsun diye
          <code>~/.zshrc</code> ya da <code>~/.bashrc</code> dosyasına yazın).</li>
        <li><code>codex</code> başlatıp <code>/mcp</code> yazın.</li>
      </ol>
    </details>
    <details>
      <summary><strong>Gemini CLI</strong></summary>
      <ol>
        <li>Metni <code>~/.gemini/settings.json</code> dosyasına (<code>"mcpServers"</code> içine) ekleyin.</li>
        <li><code>gemini</code> başlatıp <code>/mcp</code> yazın.</li>
      </ol>
    </details>

    <h2 id="first">5. İlk oturum</h2>
    @if (!teacher) {
      <p>Ajanı dersinizi okumuş bir çalışma arkadaşı gibi düşünün. Ne istediğinizi kendi cümlelerinizle — Türkçe ya
        da İngilizce — söylersiniz, doğru aracı kendisi seçer. Siz şık seçmeden cevabı söylemez, sorularınızı sizin
        yerinize yazmaz; sizinle ilgili öğrendiği her şey (zayıf konular, notlar, ne çalıştığınız) sitede saklanır,
        başka bir uygulamada ya da başka bir gün kaldığınız yerden devam edersiniz.</p>
      <p>Ne işe yarar:</p>
      <ul>
        <li><strong>Bir konuyu anlamak</strong> — bir bölümü ya da tek bir konuyu küçük parçalarla, örneklerle ve
          kontrol sorularıyla, istediğiniz dilde anlattırmak.</li>
        <li><strong>Sınava hazırlanmak</strong> — havuz sorularını konuya ya da haftaya göre çözmek; sınavlar aynı
          havuzdan hazırlanıyor.</li>
        <li><strong>Sınavdan öğrenmek</strong> — yayınlanmış bir sınavı baştan sona geçmek: neyi kaçırdınız, doğru
          cevap neden doğru, hangi konuları yeniden okumalısınız; sonra tam o konulardan pratik.</li>
        <li><strong>Takip</strong> — ilerlemeniz, zayıf konularınız, ve sınıfın yanında sınav puanlarınız
          tek bakışta; haftalık çalışma planı.</li>
        <li><strong>Soru yazmak</strong> — göndermeden önce taslağınıza geri bildirim.</li>
      </ul>
      <p>Kısa bir örnek:</p>
      <div class="card small chat">
        <p><strong>Siz:</strong> İlerlememi göster, sırada ne var?</p>
        <p><strong>Ajan:</strong> <em>(get_progress)</em> 6. haftada 11 sorudan 4'ünü cevapladın, biri yanlış.
          6. haftayla başlayalım mı?</p>
        <p><strong>Siz:</strong> Evet. Önce ana fikirleri anlat, sonra 3 soru sor.</p>
        <p><strong>Ajan:</strong> <em>(read_chapter)</em> … <em>(next_question)</em> Soru 1: Which activity is static
          testing? A) … B) … C) … D) … Hangisini seçiyorsun?</p>
        <p><strong>Siz:</strong> B</p>
        <p><strong>Ajan:</strong> <em>(answer_question, B)</em> Doğru! Gözden geçirme, ürünü çalıştırmadan inceler …</p>
        <p><strong>Siz:</strong> Bugünlük bu kadar.</p>
        <p><strong>Ajan:</strong> <em>(update_study_state, log_study_session)</em> Notlarını kaydettim. Gelecek sefer
          yanlış yaptığın soruyla başlayacağız.</p>
      </div>
      <p>İşe yarayan cümleler:</p>
      <ul>
        <li>"İlerlememi göster, sırada ne var?"</li>
        <li>"7. haftayı küçük parçalarla anlat; her parçadan sonra bir kontrol sorusu sor."</li>
        <li>"6. haftadan 5 yeni havuz sorusu sor."</li>
        <li>"Yanlış yaptığım soruları tekrar edelim."</li>
        <li>"5. hafta için bir soru yazdım; geri bildirim ver ama benim yerime yazma."</li>
        <li>"Bu cevabı Türkçe olarak yeniden açıkla."</li>
        <li>"Vize sınavımı birlikte geçelim: neleri kaçırdım, hangi konuları yeniden okumalıyım?"</li>
      </ul>
      <p>Hazır komutlar (uygulamanız gösteriyorsa): <code>study_plan</code>, <code>study_chapter</code>,
        <code>quiz_me</code>, <code>review_mistakes</code>, <code>review_exam</code>,
        <code>improve_my_question</code>.</p>
    }
    @else {
      <p>Ajanı dersinizi okumuş ve düzenleyebilen bir asistan gibi düşünün. Ne istediğinizi kendi cümlelerinizle —
        Türkçe ya da İngilizce — söylersiniz, araçları kendisi seçer. Siz değişiklik istemedikçe yalnızca okur; her
        değişiklikten önce ne yapacağını gösterir ve onayınızı bekler. Kişi, sınav ya da oturum silemez; bunlar web
        sitesinde kalır.</p>
      <p>Ne işe yarar:</p>
      <ul>
        <li><strong>Ders içeriği</strong> — bölüm metnine yeni bir kısım eklemek ya da yazdırmak (konular böyle
          oluşur), havuz ve sınav sorularını konulara bağlamak, sınıfın hangi konularda zorlandığını görmek.</li>
        <li><strong>Soru inceleme</strong> — bekleyen soruları yapay zekâ kontrolü ve istatistiklerle gözden
          geçirmek, anahtarı şüpheli ya da çeldiricisi zayıf soruları bulmak, bildirimleri yanıtlamak.</li>
        <li><strong>Sınav</strong> — havuzdan sınav hazırlamak ya da sıfırdan yazmak, soru/şık/gerekçe eklemek ve
          düzenlemek, yayından sonra öğrencinin göreceği açıklamaları yazmak, soruları konularla etiketlemek.</li>
        <li><strong>Sonuçlar</strong> — sonuç kâğıtlarını girmek ya da düzeltmek, yayınlamak, soru/konu/öğrenci
          bazında analizi okumak.</li>
        <li><strong>Yoklama</strong> — oturum açıp kapatmak, geç gelenleri elle eklemek, işaretli kayıtlara karar
          vermek, dersin ya da bir öğrencinin yoklama raporunu almak, oturumu dışa aktarmak.</li>
        <li><strong>Öğrenci takibi</strong> — bir öğrencinin ya da tüm sınıfın katılımı, çalışma notları, zayıf
          konuları, yoklaması ve sınav geçmişi tek raporda.</li>
      </ul>
      <p>Örnekler:</p>
      <ul>
        <li>"7. haftanın bekleyen sorularını birlikte inceleyelim. Her biri için öneri ver, kararı ben vereyim."</li>
        <li>"Haftalık rapor yaz: kim geride, hangi bölümlerde onaylı soru az?"</li>
        <li>"Hangi onaylı soruların anahtarı yanlış ya da çeldiricisi zayıf olabilir?"</li>
        <li>"Vize 1'in her sorusunu bir konuyla etiketle; kaydetmeden önce tabloyu göster."</li>
        <li>"6. bölüme Karnaugh haritaları diye bir kısım ekle, İngilizce ve Türkçe."</li>
        <li>"Bugünün yoklama oturumunu 15 dakikalığına aç, sonra şu üç geç gelen öğrenciyi ekle."</li>
        <li>"Ayşe Kaya'nın durumunu göster: soruları, cevapları, çalışma notları, yoklaması."</li>
        <li>"Her haftadan 3 soruyla 2 formlu bir sınav hazırla." (çıktıyı Exams sayfasından alın)</li>
      </ul>
      <p>Hazır komutlar: <code>review_pending</code>, <code>weekly_report</code>, <code>tag_exam_topics</code>.</p>
      <div class="note small">
        Ajana her değişiklikten önce (onay, ret, düzenleme, sınav, sonuç, yoklama, liste aktarma, bölüm) planını
        gösterip size sorması söylenmiştir. "Evet" demeden önce planı okuyun; bir sınavı bütünüyle değiştirmek ya da
        bir sınav sorusunu silmek kayıtlı cevapları da siler.
      </div>
    }

    <h2 id="tools">6. Ajan neler yapabilir?</h2>
    <div class="note small">
      Tek token tüm SIS dersleriniz için geçerlidir. <code>list_courses</code> derslerinizi gösterir; diğer araçlar
      <code>course</code> parametresi (ders kodu, örneğin <code>SENG 403</code>) alır. Ajana hangi dersi kastettiğinizi
      söyleyin ya da başta bir kez belirtin: "Bugün X dersine çalışıyoruz."
    </div>
    @if (!teacher) { <table>
      <thead><tr><th>Alan</th><th>Araçlar</th></tr></thead>
      <tbody>
        <tr><td>Dersler</td><td><code>list_courses</code>, <code>whoami</code>, <code>my_overview</code></td></tr>
        <tr><td>Öğrenme</td><td><code>read_chapter</code>, <code>list_chapters</code>, <code>list_topics</code>, <code>read_topic</code></td></tr>
        <tr><td>Sınav</td><td><code>my_exam_results</code>, <code>review_topics</code></td></tr>
        <tr><td>Yeni soru cevaplama</td><td><code>next_question</code> → <code>answer_question</code> (ilk cevap, sayılır)</td></tr>
        <tr><td>Tekrar</td><td><code>next_review_question</code> → <code>practice_answer</code> (ilk cevabı değiştirmez)</td></tr>
        <tr><td>İlerleme</td><td><code>get_progress</code>, <code>update_study_state</code>, <code>log_study_session</code></td></tr>
        <tr><td>Sorularınız</td><td><code>my_questions</code>, <code>submit_question</code>, <code>revise_question</code>, <code>list_questions</code></td></tr>
        <tr><td>Tartışma</td><td><code>get_question</code>, <code>get_comments</code>, <code>post_comment</code>, <code>flag_question</code></td></tr>
      </tbody>
    </table> }
    @else { <table>
      <thead><tr><th>Alan</th><th>Araçlar</th></tr></thead>
      <tbody>
        <tr><td>Katılım</td><td><code>class_overview</code>, <code>student_detail</code>, <code>study_overview</code>, <code>import_students</code></td></tr>
        <tr><td>İnceleme</td><td><code>question_counts</code>, <code>list_questions_for_review</code>, <code>decide_question</code>, <code>edit_question</code>, <code>question_stats</code></td></tr>
        <tr><td>Bildirimler</td><td><code>list_flags</code>, <code>resolve_flag</code>, <code>hide_comment</code></td></tr>
        <tr><td>Liste</td><td><code>course_roster</code>, <code>add_student</code>, <code>import_students</code></td></tr>
        <tr><td>Bölüm, konu</td><td><code>create_chapter</code>, <code>update_chapter</code>, <code>set_chapter_open</code>, <code>list_topics</code>, <code>sync_topics</code>, <code>set_question_topics</code>, <code>topic_stats</code></td></tr>
        <tr><td>Sınav</td><td><code>list_exams</code>, <code>get_exam</code>, <code>create_exam</code>, <code>create_exam_from_pool</code>, <code>add_exam_question</code>, <code>update_exam_question</code>, <code>set_exam_question_topics</code>, <code>remove_exam_question</code>, <code>exam_results</code>, <code>save_exam_result</code>, <code>exam_analysis</code>, <code>publish_exam</code></td></tr>
        <tr><td>Yoklama</td><td><code>list_sessions</code>, <code>create_session</code>, <code>open_session</code>, <code>close_session</code>, <code>session_attendances</code>, <code>decide_attendance</code>, <code>add_attendance</code>, <code>attendance_report</code>, <code>student_attendance</code>, <code>session_export</code></td></tr>
        <tr><td>Ortak</td><td><code>list_courses</code>, <code>whoami</code>, <code>list_chapters</code>, <code>read_chapter</code>, <code>list_questions</code>, <code>get_question</code>, <code>get_comments</code>, <code>post_comment</code></td></tr>
      </tbody>
    </table> }
    <p class="small muted">Ajan kişi, liste kaydı, sınav ya da yoklama oturumu silemez. Bunlar için web sitesini
      kullanın. Değişiklik yapan araçlar önce size sorar.</p>

    @if (!teacher) {
      <h2 id="progress">7. İlerlemeniz nasıl saklanır?</h2>
      <p>İlerlemeniz yapay zekâ uygulamasında değil, sitede saklanır. Yarın, başka bir bilgisayarda ya da başka bir
        uygulamayla kaldığınız yerden devam edersiniz.</p>
      <ul>
        <li><strong>İlk cevaplar</strong>: sayılan cevaplar (web sitesindekiyle aynı).</li>
        <li><strong>Tekrar cevapları</strong>: eski bir soruyu yeniden cevapladığınızda. Tekrar listesini bunlar
          belirler: bir soruya son cevabınız yanlışsa soru "tekrar edilecek" olur.</li>
        <li><strong>Bölüm başına çalışma durumu</strong>: <code>NOT_STARTED</code> (başlanmadı) →
          <code>STUDYING</code> (çalışılıyor) → <code>REVIEWING</code> (tekrar) → <code>DONE</code> (bitti), 1–5
          arası güven puanı ve zayıf noktalarınızla ilgili kısa notlar. Ajana "6. haftayı bitti olarak işaretle,
          güvenim 4" diyebilirsiniz.</li>
        <li><strong>Çalışma günlüğü</strong>: her oturumun sonunda birkaç cümle. Sonraki oturum buradan başlar.</li>
      </ul>
    }

    <h2 id="rules">{{ teacher ? 7 : 8 }}. Kurallar</h2>
    @if (!teacher) { <ul>
      <li><strong>Seçimi siz yaparsınız.</strong> Ajan soruyu gösterir, şıkkı siz seçersiniz. Ajana cevaplatmak
        yalnızca sınav sonucunuza zarar verir; üstelik yalnız ilk cevap sayılır.</li>
      <li><strong>Sorularınızı siz yazarsınız.</strong> Ajan geri bildirim verir, yazmaz. Otomatik kontrol ve
        eğitmen kopya ya da yapay zekâya yazdırılmış soruları fark eder.</li>
      <li>Ajan lab ödevini ya da ev ödevini çözmez.</li>
      <li>Ajan yorum göndermeden, sorun bildirmeden ya da soru göndermeden önce size sorar. Önce metni okuyun.</li>
      <li>Yapay zekâ yanılabilir. Ajan ile bölüm metni çelişirse bölüm metni geçerlidir. Yanlış anahtarı
        <code>flag_question</code> ile bildirin.</li>
    </ul> }
    @else { <ul>
      <li>Her değişiklik sizin adınıza yapılır. "Evet" demeden önce ajanın planını okuyun.</li>
      <li>Öğrenciye giden notlar (REVISE, REJECT) kısa ve sade İngilizce olmalı.</li>
      <li>Gerçek adlar ve e-postalar yalnızca sizin için. Ajanın bunları yorumlara yazmasına izin vermeyin.</li>
      <li>Sınav çıktısı web sitesinden alınır (Soru havuzu → Exams → Form A / Key A).</li>
    </ul> }

    <h2 id="trouble">{{ teacher ? 8 : 9 }}. Sorunlar ve çözümler</h2>
    <table>
      <thead><tr><th>Gördüğünüz</th><th>Ne yapmalı?</th></tr></thead>
      <tbody>
        <tr><td>401, "Missing or wrong MCP token"</td><td>Token yanlış, eski ya da silinmiş. Yeni token oluşturup uygulamaya yeniden yazın.</td></tr>
        <tr><td>Uygulamada sis araçları yok</td><td>Uygulamayı yeniden başlatın. Ayar dosyasını kontrol edin (eksik virgül JSON'u bozar). Claude Code'da <code>/mcp</code> yazın.</td></tr>
        <tr><td>"Answer the question first"</td><td>Normal: tartışma, cevap verdikten sonra açılır.</td></tr>
        <tr><td>"already answered … use practice_answer"</td><td>Normal: ilk cevap değişmez. Tekrar olarak çözün.</td></tr>
        <tr><td>"You already have 2 questions in this chapter"</td><td>Bölüm başına sınır. Reddedilen sorular sayılmaz.</td></tr>
        <tr><td>"This chapter is closed for new questions"</td><td>Eğitmen bölümü kapattı. Çalışmaya ve cevaplamaya devam edebilirsiniz.</td></tr>
        <tr><td>Claude Desktop: "spawn npx ENOENT"</td><td>Node.js kurulu değil ya da uygulama kurulumdan önce açılmış. Node.js kurun, uygulamayı yeniden başlatın.</td></tr>
      </tbody>
    </table>

    <h2 id="privacy">{{ teacher ? 9 : 10 }}. Gizlilik ve güvenlik</h2>
    <ul>
      <li>Token'ı gizli tutun. Herkese açık bir GitHub deposuna ya da ekran görüntüsüne koymayın.</li>
      <li>Site token'ın kendisini değil, yalnızca parmak izini saklar. Kimse onu size yeniden gösteremez.</li>
      @if (!teacher) { <li>Eğitmen çalışma durumunuzu, notlarınızı ve günlüğünüzü görebilir. Diğer öğrenciler göremez;
        sorularınızın ve yorumlarınızın yanında yalnızca takma adınızı görürler.</li> }
      <li>Yapay zekâ uygulaması okuduklarını (bölüm metinleri, sorular) kendi şirketine gönderir. Gerçek adınız
        gönderilmez; ajan sizi takma adınızla tanır@if (teacher) { (gerçek adları size gösteren eğitmen
          araçları hariç)}.</li>
      <li>Kullanmayı bıraktığınızda token'ı <a routerLink="/agent">AI agent</a> sayfasından silin.</li>
    </ul>
  </div> }
</div>
  `,
})
export class AgentHelpComponent {
  private readonly auth = inject(AuthService);
  lang: 'en' | 'tr' = getItem('agentHelpLang') === 'tr' ? 'tr' : 'en';
  /** Staff of any course read the instructor version. */
  readonly teacher = this.auth.isStaff();

  setLang(l: 'en' | 'tr') {
    this.lang = l;
    setItem('agentHelpLang', l);
  }

  /** "#section" links scroll inside the page instead of going through the router. */
  onClick(e: MouseEvent) {
    const href = (e.target as HTMLElement | null)?.closest('a')?.getAttribute('href');
    if (href?.startsWith('#')) {
      e.preventDefault();
      document.getElementById(href.slice(1))?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }
}
