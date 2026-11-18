import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MSG } from '../shared/messages';
import { errorMessage } from '../shared/errors';
import { TopicChipsComponent } from '../shared/topics/topic-chips.component';
import type { MyResultDetailDto } from '../core/api.models';
import { resultView, textSegments } from './my-result.logic';

/** One published exam result of mine: what to review (topics of the missed questions), then every question. */
@Component({
  selector: 'app-my-result',
  imports: [RouterLink, TopicChipsComponent],
  template: `
    <div class="container">
      <nav class="breadcrumb" aria-label="Konum">
        <a routerLink="/courses">{{ msg.my.title }}</a> ›
        <a [routerLink]="['/courses', id()]">{{ courseCode() || m.back }}</a>
        @if (result(); as r) { › {{ r.title }} }
      </nav>
      @if (error()) { <p class="form-error" role="alert">{{ error() }}</p> }

      @if (result(); as r) {
        <div class="page-header">
          <div>
            <h1>{{ r.title }}</h1>
            @if (r.examDate) { <div class="text-sm text-secondary">{{ formatDay(r.examDate) }}</div> }
          </div>
          <div class="total card" [attr.aria-label]="m.total">
            <span class="text-sm text-secondary">{{ m.total }}</span>
            <span class="total-value"><strong>{{ fmt(r.points) }}</strong> / {{ fmt(r.maxPoints) }}</span>
            @if (percent() !== null) { <span class="text-sm text-secondary">%{{ percent() }}</span> }
          </div>
        </div>

        <section class="card review" aria-labelledby="review-title">
          <h2 id="review-title">{{ m.review }}</h2>
          @if (!view().hasTopics) {
            <p class="text-secondary">{{ m.noTopics }}</p>
          } @else if (!view().reviewTopics.length) {
            <p class="text-secondary">{{ view().missedCount ? m.noTopics : m.noMisses }}</p>
          } @else {
            <p class="text-sm text-secondary">{{ m.reviewHint }}</p>
            <ul class="review-list">
              @for (rt of view().reviewTopics; track rt.topic.id) {
                <li [class.ok]="rt.missed === 0">
                  <app-topic-chips [topics]="[rt.topic]" />
                  <span class="text-sm" [class.text-danger]="rt.missed > 0" [class.text-success]="rt.missed === 0">
                    {{ rt.missed > 0 ? m.missed(rt.missed, rt.total) : m.allRight(rt.total) }}
                  </span>
                </li>
              }
            </ul>
          }
        </section>

        <div class="q-head">
          <h2>{{ m.questions }}</h2>
          <label class="only-missed">
            <input type="checkbox" [checked]="onlyMissed()" (change)="onlyMissed.set($any($event.target).checked)" />
            {{ m.onlyMissed }} ({{ view().missedCount }})
          </label>
        </div>

        @for (q of shown(); track q.position) {
          <article class="card question" [class.is-wrong]="q.status === 'wrong'" [class.is-right]="q.status === 'right'" [class.is-partial]="q.status === 'partial'">
            <div class="q-top">
              <strong>{{ m.question }} {{ q.position }}</strong>
              @switch (q.status) {
                @case ('right') { <span class="badge badge-success">✓ {{ m.right }}</span> }
                @case ('wrong') { <span class="badge badge-danger">✗ {{ m.wrong }}</span> }
                @case ('partial') { <span class="badge badge-warning">{{ m.partial }}</span> }
              }
              <span class="grow"></span>
              <span class="text-sm">{{ m.points }}: <strong>{{ q.points === null ? '—' : fmt(q.points) }}</strong> / {{ fmt(q.maxPoints) }}</span>
            </div>
            @for (seg of segments(q.text); track $index) {
              @if (seg.code) { <pre class="q-code"><code>{{ seg.text }}</code></pre> }
              @else { <div class="q-text">{{ seg.text }}</div> }
            }
            @if (q.type === 'CHOICE' || q.correctChoice) {
              <div class="answers">
                <span class="answer" [class.wrong]="q.correct === false" [class.right]="q.correct === true">
                  {{ m.myAnswer }}: <strong>{{ q.myAnswer || m.blank }}</strong>
                </span>
                @if (q.correctChoice) {
                  <span class="answer key">{{ m.correctAnswer }}: <strong>{{ q.correctChoice }}</strong></span>
                }
              </div>
            } @else if (q.myAnswer) {
              <div class="answers"><span class="answer">{{ m.myAnswer }}: <strong>{{ q.myAnswer }}</strong></span></div>
            }
            <app-topic-chips [topics]="q.topicList" />
          </article>
        } @empty {
          <p class="text-secondary">{{ onlyMissed() ? m.noMisses : m.noQuestions }}</p>
        }
      } @else if (!error()) {
        <p class="text-secondary">{{ msg.topics.loading }}</p>
      }
    </div>
  `,
  styles: [`
    .page-header { flex-wrap: wrap; gap: var(--space-md); }
    .total { display: flex; flex-direction: column; align-items: flex-end; padding: var(--space-sm) var(--space-md); margin: 0; }
    .total-value { font-size: 1.4rem; }
    .review { border-left: 4px solid var(--c-primary); }
    .review h2 { margin-top: 0; font-size: 1.1rem; }
    .review-list { list-style: none; margin: 0; padding: 0; }
    .review-list li { display: flex; flex-wrap: wrap; align-items: center; gap: 2px 12px; padding: 4px 0; border-top: 1px solid var(--c-border); }
    .review-list li:first-child { border-top: 0; }
    .review-list li.ok { opacity: 0.75; }
    .q-head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--space-sm); margin-top: var(--space-lg); }
    .q-head h2 { margin: 0; }
    .only-missed { display: inline-flex; align-items: center; gap: 8px; min-height: 40px; cursor: pointer; }
    .question { margin: var(--space-sm) 0; border-left: 4px solid var(--c-border); }
    .question.is-wrong { border-left-color: var(--c-danger); }
    .question.is-right { border-left-color: var(--c-success); }
    .question.is-partial { border-left-color: var(--c-warning); }
    .q-top { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; margin-bottom: 6px; }
    .grow { flex: 1; }
    .q-text { white-space: pre-wrap; overflow-wrap: anywhere; margin: 4px 0; }
    .q-code { white-space: pre-wrap; overflow-wrap: anywhere; font-family: var(--font-mono); font-size: 0.88rem; background: var(--c-bg); border: 1px solid var(--c-border); border-radius: var(--radius-md); padding: 8px 10px; margin: 6px 0; }
    .answers { display: flex; flex-wrap: wrap; gap: 6px 10px; margin: 8px 0 2px; }
    .answer { padding: 3px 10px; border-radius: var(--radius-md); background: var(--c-bg); border: 1px solid var(--c-border); }
    .answer.wrong { background: var(--c-danger-light); border-color: var(--c-danger); color: var(--c-danger); }
    .answer.right, .answer.key { background: var(--c-success-light); border-color: var(--c-success); color: var(--c-success); }
  `],
})
export class MyResultComponent {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  readonly msg = MSG;
  readonly m = MSG.myResult;
  readonly id = input.required<string>();
  readonly examId = input.required<string>();

  readonly result = signal<MyResultDetailDto | null>(null);
  readonly error = signal('');
  readonly onlyMissed = signal(false);

  readonly view = computed(() => {
    const r = this.result();
    return r ? resultView(r) : { reviewTopics: [], questions: [], hasTopics: false, missedCount: 0 };
  });
  readonly shown = computed(() => (this.onlyMissed() ? this.view().questions.filter(q => q.missed) : this.view().questions));
  readonly percent = computed(() => {
    const r = this.result();
    return r && r.maxPoints > 0 ? Math.round((r.points / r.maxPoints) * 100) : null;
  });
  readonly courseCode = computed(() => this.auth.user()?.courses.find(c => c.id === Number(this.id()))?.code ?? '');

  readonly segments = textSegments;

  constructor() {
    effect(() => {
      const courseId = this.id();
      const examId = this.examId();
      untracked(() => this.load(courseId, examId));
    });
  }

  private async load(courseId: string, examId: string) {
    this.result.set(null);
    this.error.set('');
    try {
      this.result.set(await this.api.getMyResult(courseId, examId));
    } catch (e) {
      const code = (e as { code?: string } | null)?.code;
      this.error.set(code === 'HTTP_404' || code === 'NOT_FOUND' ? this.m.notPublished : errorMessage(e));
    }
  }

  fmt(n: number | null | undefined): string {
    return n == null ? '—' : String(Math.round(n * 100) / 100).replace('.', ',');
  }

  formatDay(d: string) {
    try { return new Date(d + 'T00:00:00').toLocaleDateString('tr-TR', { dateStyle: 'medium' }); }
    catch { return d; }
  }
}
