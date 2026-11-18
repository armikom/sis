import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { PoolApiService } from '../pool/pool-api.service';
import { when } from '../pool/pool-util';
import type { McpTokenInfo } from '../pool/pool.models';
import { errorMessage } from '../shared/errors';
import { agentSnippet, MCP_CLIENTS, type McpClientId } from './agent-snippets';

/** Personal MCP token and ready-made client configuration. One token for all my courses. */
@Component({
  selector: 'app-agent',
  imports: [RouterLink],
  template: `
    <h1>AI agent (MCP) <span class="muted small">/ Yapay zekâ ajanı</span></h1>
    <p>
      Connect your own AI agent (Claude, ChatGPT-style tools, Copilot …) to the question pool of your SIS courses with MCP.
      @if (teacher()) {
        Your agent can review questions, read participation and study reports, handle problem reports and build exams.
      } @else {
        Your agent can teach the chapters, quiz you with pool questions, keep your progress and notes, and help you
        review your mistakes.
      }
    </p>
    <p class="muted small">
      Kendi yapay zekâ ajanınızı MCP ile SIS derslerinizin soru havuzuna bağlayın.
      @if (teacher()) {
        Ajan soruları incelemenize, katılım ve çalışma raporlarını okumanıza, bildirimleri ele almanıza ve sınav hazırlamanıza yardım eder.
      } @else {
        Ajan bölümleri anlatır, havuzdaki sorularla sizi sınar, ilerlemenizi ve notlarınızı saklar, yanlışlarınızı tekrar ettirir.
      }
    </p>
    <p class="note small">
      One token works for all your courses: the tools take a <code>course</code> argument (the course code), and
      <code>list_courses</code> shows your courses.
      / Tek token tüm dersleriniz için geçerlidir: araçlar <code>course</code> (ders kodu) parametresi alır,
      <code>list_courses</code> derslerinizi listeler.
    </p>

    <p class="note">
      New to MCP? Read <a routerLink="/agent/help"><strong>How to use the AI agent</strong></a> first: step by
      step for each app, a first session, rules and problems.
      / MCP'de yeni misiniz? Önce <a routerLink="/agent/help">kullanım rehberini</a> okuyun.
    </p>

    @if (!teacher()) {
      <div class="note amber small">
        The same rules apply: you see the answer only after you choose, and only your first answer counts. Choose
        yourself; do not let the agent answer for you. The instructor can see your study notes and diary.
        / Kurallar aynı: cevabı seçtikten sonra görürsünüz, yalnız ilk cevap sayılır. Seçimi siz yapın. Eğitmen
        çalışma notlarınızı ve günlüğünüzü görebilir.
      </div>
    }

    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }

    @if (info(); as info) {
      <div class="card">
        <div><strong>Endpoint</strong> <span class="muted small">/ Adres</span></div>
        <div class="row">
          <code class="grow pre">{{ endpoint() }}</code>
          <button type="button" class="small" (click)="copy(endpoint(), 'url')">{{ copied() === 'url' ? 'Copied ✓' : 'Copy' }}</button>
        </div>

        <div class="mt-md"><strong>Your token</strong> <span class="muted small">/ Token'ınız</span></div>
        @if (token()) {
          <div class="note green small">
            Copy it now: it is shown only once. Keep it secret, like a password.
            / Şimdi kopyalayın: yalnızca bir kez gösterilir. Parola gibi gizli tutun.
          </div>
          <div class="row">
            <code class="grow pre">{{ token() }}</code>
            <button type="button" class="small" (click)="copy(token(), 'token')">{{ copied() === 'token' ? 'Copied ✓' : 'Copy' }}</button>
          </div>
        } @else if (info.has_token) {
          <p class="small">
            <code>{{ info.hint }}</code> · created {{ when(info.created_at) }} ·
            last used {{ info.last_used_at ? when(info.last_used_at) : 'never' }}
          </p>
        } @else {
          <p class="muted small">You have no token yet. / Henüz token'ınız yok.</p>
        }
        <p class="row">
          <button type="button" class="primary" (click)="create()">{{ info.has_token ? 'Create a new token' : 'Create my token' }}</button>
          @if (info.has_token) { <button type="button" class="danger small" (click)="remove()">Delete token</button> }
        </p>
      </div>
    }

    <h2>Connect <span class="muted small">/ Bağlan</span></h2>
    <div class="tabs">
      @for (c of clients; track c.id) {
        <button type="button" [class.on]="client() === c.id" (click)="client.set(c.id)">{{ c.label }}</button>
      }
    </div>
    <div class="card">
      <p class="small muted">{{ snippet().where }}</p>
      <pre class="pre small">{{ snippet().text }}</pre>
      <button type="button" class="small" (click)="copy(snippet().text, 'snippet')">{{ copied() === 'snippet' ? 'Copied ✓' : 'Copy' }}</button>
      @if (!token()) {
        <p class="small muted">Create a token above and it appears here instead of YOUR_TOKEN. / Yukarıda token oluşturunca YOUR_TOKEN yerine görünür.</p>
      }
    </div>

    <h2>Try it <span class="muted small">/ Deneyin</span></h2>
    @if (!teacher()) {
      <ul class="small">
        <li>"Show my courses, then my progress in the first one." / "Derslerimi, sonra ilkindeki ilerlememi göster."</li>
        <li>"Show my progress and tell me what to study next." / "İlerlememi göster, sırada ne var?"</li>
        <li>"Teach me week 6 and then quiz me with 5 pool questions." / "6. haftayı anlat, sonra 5 soru sor."</li>
        <li>"Let's review the questions I got wrong." / "Yanlış yaptığım soruları tekrar edelim."</li>
        <li>"I wrote a question for week 7; give me feedback." / "7. hafta için soru yazdım, geri bildirim ver."</li>
      </ul>
    } @else {
      <ul class="small">
        <li>"Go through the waiting questions of week 7 with me." / "7. haftanın bekleyen sorularını birlikte inceleyelim."</li>
        <li>"Write a weekly report: who is behind, which chapters need questions?" / "Haftalık rapor yaz."</li>
        <li>"Which approved questions may have a wrong key?" / "Hangi onaylı soruların anahtarı yanlış olabilir?"</li>
        <li>"What are the most common weak points in the students' study notes?" / "Öğrenci notlarında en sık zayıf konular?"</li>
      </ul>
    }
    <p class="small">
      More examples, steps for each app and solutions to common problems:
      <a routerLink="/agent/help">How to use the AI agent / Kullanım rehberi</a>.
    </p>
    <p class="muted small">
      Clients that show MCP prompts list ready commands too (for example <code>/study_chapter</code>, <code>/quiz_me</code>,
      <code>/weekly_report</code>). / Prompt destekleyen istemcilerde hazır komutlar da görünür.
    </p>
  `,
})
export class AgentComponent implements OnInit {
  private readonly api = inject(PoolApiService);
  private readonly auth = inject(AuthService);
  readonly when = when;
  readonly clients = MCP_CLIENTS;
  /** Staff of any course see the instructor wording (the same token serves all courses). */
  readonly teacher = computed(() => this.auth.isStaff());
  readonly info = signal<McpTokenInfo | null>(null);
  readonly token = signal('');
  readonly client = signal<McpClientId>('claude-code');
  readonly error = signal('');
  readonly copied = signal('');

  readonly endpoint = computed(() => this.info()?.endpoint || location.origin + '/mcp');
  readonly snippet = computed(() => agentSnippet(this.client(), this.endpoint(), this.token() || 'YOUR_TOKEN'));

  async ngOnInit() {
    try {
      this.info.set(await this.api.mcpToken());
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  async create() {
    this.error.set('');
    if (this.info()?.has_token && !confirm('A new token replaces the old one. Agents that use the old token stop working. / Yeni token eskisinin yerine geçer; eski token ile çalışan ajanlar durur.')) return;
    try {
      const d = await this.api.createMcpToken();
      this.token.set(d.token ?? '');
      this.info.set({ ...d, has_token: true, endpoint: d.endpoint ?? this.info()?.endpoint });
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  async remove() {
    this.error.set('');
    if (!confirm('Delete the token? Your agent can no longer connect. / Token silinsin mi? Ajanınız artık bağlanamaz.')) return;
    try {
      await this.api.deleteMcpToken();
      this.token.set('');
      this.info.set(await this.api.mcpToken());
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  async copy(text: string, what: string) {
    try {
      await navigator.clipboard.writeText(text);
      this.copied.set(what);
      setTimeout(() => this.copied.set(''), 2000);
    } catch {
      this.copied.set('');
    }
  }
}
