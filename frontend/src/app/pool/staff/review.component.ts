import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PoolApiService } from '../pool-api.service';
import { PoolContext } from '../pool-context.service';
import { PM } from '../pool-messages';
import { LETTERS, when } from '../pool-util';
import type { ChapterSummary, Letter, QuestionStatus, ReviewQuestion, StatusCount } from '../pool.models';
import { AiReviewComponent } from '../components/ai-review.component';
import { errorMessage } from '../../shared/errors';

/** Staff: approve / send back / reject the questions of the course. */
@Component({
  selector: 'app-pool-review',
  imports: [FormsModule, RouterLink, AiReviewComponent],
  template: `
    <h1>{{ pm.title }}</h1>
    <div class="row spread">
      <div class="tabs flush">
        @for (s of pm.statuses; track s[0]) {
          <button type="button" [class.on]="status() === s[0]" (click)="pick(s[0])">{{ s[1] }} ({{ counts()[s[0]] || 0 }})</button>
        }
      </div>
      <select class="auto" [ngModel]="chapterId()" (ngModelChange)="chapterId.set($event); load()" aria-label="Chapter">
        <option value="">{{ pm.allChapters }}</option>
        @for (c of chapters(); track c.id) { <option [value]="c.id">{{ c.title }}</option> }
      </select>
    </div>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (!questions().length) { <p class="muted">{{ pm.nothing }}</p> }

    @for (q of questions(); track q.id) {
      <div class="card">
        <div class="row small muted">
          <span>#{{ q.id }} · {{ pm.week }} {{ q.chapter_number }}</span>
          <a [routerLink]="[ctx.base(), 'staff', 'students', q.author_id]">{{ q.author_name }}</a>
          <span>({{ q.author }})</span>
          <span>{{ when(q.created_at) }}</span>
          @if (q.attempts) { <span>{{ pm.answeredCorrectly(q.correct_attempts, q.attempts) }}</span> }
          @if (q.open_flags) { <span class="badge red">{{ pm.openFlags(q.open_flags) }}</span> }
        </div>
        <p class="pre"><b>{{ q.stem }}</b></p>
        @for (l of letters; track l) {
          <div class="option" [class.correct]="q.correct === l">
            <span class="letter">{{ l }}</span>
            <div class="grow">
              <div>{{ field(q, 'option', l) }}</div>
              <div class="why">{{ field(q, 'rationale', l) }}</div>
            </div>
          </div>
        }
        <p class="small muted">{{ pm.reference }} {{ q.reference }}</p>
        <pool-ai-review [status]="q.ai_status" [review]="q.ai_review" />
        @if (q.ai_status === 'SKIPPED') { <p class="small muted">{{ pm.noCheck }}</p> }
        @if (q.explanation) { <div class="note small"><b>{{ pm.explanation }}</b><div class="pre">{{ q.explanation }}</div></div> }
        @if (q.instructor_note) { <div class="small muted">{{ pm.lastNote }} {{ q.instructor_note }}</div> }

        <textarea class="mt-sm" rows="2" [(ngModel)]="notes[q.id]" [placeholder]="pm.notePlaceholder" [attr.aria-label]="pm.notePlaceholder"></textarea>
        <div class="row mt-sm">
          @if (q.status !== 'APPROVED') { <button type="button" class="primary" (click)="decide(q, 'APPROVE')">{{ pm.approve }}</button> }
          @if (q.status !== 'NEEDS_REVISION') { <button type="button" (click)="decide(q, 'REVISE')">{{ pm.sendBack }}</button> }
          @if (q.status !== 'REJECTED') { <button type="button" class="danger" (click)="decide(q, 'REJECT')">{{ pm.reject }}</button> }
          <a [routerLink]="[ctx.base(), 'questions', q.id, 'edit']" class="btn">{{ pm.editText }}</a>
          <a [routerLink]="[ctx.base(), 'questions', q.id]" class="btn">{{ pm.open }}</a>
          <button type="button" class="link" (click)="rerun(q, 'ai-check')">{{ pm.rerunCheck }}</button>
          @if (q.status === 'APPROVED') { <button type="button" class="link" (click)="rerun(q, 'explain')">{{ pm.rerunExplain }}</button> }
        </div>
      </div>
    }
  `,
})
export class ReviewComponent implements OnInit {
  private readonly api = inject(PoolApiService);
  readonly ctx = inject(PoolContext);
  readonly pm = PM.review;
  readonly letters = LETTERS;
  readonly when = when;
  readonly status = signal<QuestionStatus>('PENDING');
  readonly chapterId = signal<string>('');
  readonly chapters = signal<ChapterSummary[]>([]);
  readonly counts = signal<Record<string, number>>({});
  readonly questions = signal<ReviewQuestion[]>([]);
  readonly error = signal('');
  notes: Record<number, string> = {};

  async ngOnInit() {
    this.chapters.set(await this.api.chapters(this.ctx.courseId()).catch(() => []));
    this.load();
  }

  private setCounts(c: StatusCount[]) {
    this.counts.set(Object.fromEntries(c.map(x => [x.status, x.count])));
  }

  async load() {
    this.error.set('');
    const courseId = this.ctx.courseId();
    try {
      const [list, c] = await Promise.all([
        this.api.staffQuestions(courseId, this.status(), this.chapterId() || null),
        this.api.questionCounts(courseId),
      ]);
      this.questions.set(list);
      this.setCounts(c);
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  field(q: ReviewQuestion, kind: 'option' | 'rationale', l: Letter): string {
    return q[`${kind}_${l.toLowerCase()}` as 'option_a'];
  }

  async decide(q: ReviewQuestion, action: 'APPROVE' | 'REVISE' | 'REJECT') {
    this.error.set('');
    const courseId = this.ctx.courseId();
    try {
      await this.api.decide(courseId, q.id, action, this.notes[q.id] || '');
      this.questions.update(list => list.filter(x => x.id !== q.id));
      this.setCounts(await this.api.questionCounts(courseId));
    } catch (e) {
      this.error.set(`Question #${q.id}: ${errorMessage(e)}`);
    }
  }

  async rerun(q: ReviewQuestion, what: 'ai-check' | 'explain') {
    try {
      await this.api.rerun(this.ctx.courseId(), q.id, what);
      setTimeout(() => this.load(), 6000);
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  pick(s: QuestionStatus) {
    this.status.set(s);
    this.load();
  }
}
