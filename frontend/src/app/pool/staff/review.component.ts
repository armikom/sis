import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PoolApiService } from '../pool-api.service';
import { PoolContext } from '../pool-context.service';
import { PM } from '../pool-messages';
import { LETTERS, when } from '../pool-util';
import type { ChapterSummary, Letter, PoolTopicRef, QuestionStatus, ReviewQuestion, StatusCount } from '../pool.models';
import { AiReviewComponent } from '../components/ai-review.component';
import { errorMessage } from '../../shared/errors';
import { TopicChipsComponent } from '../../shared/topics/topic-chips.component';
import { TopicPickerComponent } from '../../shared/topics/topic-picker.component';
import { poolTopics, Topic } from '../../shared/topics/topic';

/** Staff: approve / send back / reject the questions of the course. */
@Component({
  selector: 'app-pool-review',
  imports: [FormsModule, RouterLink, AiReviewComponent, TopicChipsComponent, TopicPickerComponent],
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
        <div class="row small">
          <span class="muted">{{ pm.topics }}</span>
          @if (topicsOf(q).length) { <app-topic-chips [topics]="topicsOf(q)" [label]="pm.topics" /> }
          @else { <span class="muted">{{ pm.noTopics }}</span> }
          @if (courseTopics().length && editing() !== q.id) { <button type="button" class="link" (click)="startEdit(q)">{{ pm.editTopics }}</button> }
        </div>
        @if (editing() === q.id) {
          <app-topic-picker [topics]="courseTopics()" [(selected)]="draftTopics" [firstChapterId]="q.chapter_id" [label]="pm.topics" />
          <div class="row mt-sm">
            <button type="button" class="primary small" [disabled]="savingTopics()" (click)="saveTopics(q)">{{ pm.saveTopics }}</button>
            <button type="button" class="small" (click)="editing.set(null)">{{ pm.cancel }}</button>
          </div>
        }
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
  readonly courseTopics = signal<Topic[]>([]);
  readonly editing = signal<number | null>(null);
  readonly draftTopics = signal<number[]>([]);
  readonly savingTopics = signal(false);

  async ngOnInit() {
    this.api.courseTopics(this.ctx.courseId()).then(list => this.courseTopics.set(poolTopics(list)), () => {});
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

  topicsOf(q: ReviewQuestion): Topic[] {
    return poolTopics(q.topics);
  }

  startEdit(q: ReviewQuestion) {
    this.draftTopics.set((q.topics ?? []).map(t => t.id));
    this.editing.set(q.id);
  }

  /** PUT …/questions/{id}/topics; the reply may be the topics, the question, or nothing (then built from the course list). */
  async saveTopics(q: ReviewQuestion) {
    this.savingTopics.set(true);
    this.error.set('');
    const ids = this.draftTopics();
    try {
      const res = await this.api.setQuestionTopics(this.ctx.courseId(), q.id, ids);
      const topics: PoolTopicRef[] = Array.isArray(res) ? res
        : res && Array.isArray(res.topics) ? res.topics
        : this.courseTopics().filter(t => ids.includes(t.id)).map(t => ({
            id: t.id, chapter_id: t.chapterId ?? 0, chapter_number: t.chapterNumber ?? 0, chapter_title: t.chapterTitle,
            number: t.number, title_en: t.titleEn, title_tr: t.titleTr,
          }));
      this.questions.update(list => list.map(x => (x.id === q.id ? { ...x, topics } : x)));
      this.editing.set(null);
    } catch (e) {
      this.error.set(`Question #${q.id}: ${errorMessage(e)}`);
    }
    this.savingTopics.set(false);
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
