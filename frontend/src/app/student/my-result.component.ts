import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MSG } from '../shared/messages';
import { errorMessage } from '../shared/errors';
import { TopicChipsComponent } from '../shared/topics/topic-chips.component';
import type { MyResultDetailDto } from '../core/api.models';
import { correctShareText, OptionRow, percent, ResultView, resultView, sortByMostMissed, textSegments } from './my-result.logic';

const EMPTY_VIEW: ResultView = { reviewTopics: [], questions: [], hasTopics: false, missedCount: 0, hasQuestionStats: false };

/**
 * The student's review of one published exam: score next to the class figures, what to review (topics of the missed
 * questions), then every question with my answer, the options (class bars, key and my choice marked, rationales),
 * the explanation and its topics (preview / jump to the chapter text).
 */
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
            <h1>{{ m.pageTitle }}</h1>
            <div class="exam-title">{{ r.title }}@if (r.examDate) { <span class="text-sm text-secondary"> · {{ formatDay(r.examDate) }}</span> }</div>
          </div>
        </div>

        <div class="score-row">
          <div class="card score mine">
            <span class="text-sm text-secondary">{{ m.myScore }}</span>
            <span class="score-value"><strong>{{ fmt(r.points) }}</strong> / {{ fmt(r.maxPoints) }}</span>
            @if (myPercent() !== null) { <span class="text-sm text-secondary">%{{ myPercent() }}</span> }
          </div>
          @if (r.stats; as s) {
            <div class="card score">
              <span class="text-sm text-secondary">{{ m.classAverage }}</span>
              <span class="score-value">{{ fmt(s.average) }}</span>
              <span class="text-sm text-secondary">{{ m.participants(s.participants) }}</span>
            </div>
            <div class="card score">
              <span class="text-sm text-secondary">{{ m.median }}</span>
              <span class="score-value">{{ fmt(s.median) }}</span>
              <span class="text-sm text-secondary">/ {{ fmt(r.maxPoints) }}</span>
            </div>
          }
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
          <div class="q-controls">
            <label class="toggle">
              <input type="checkbox" [checked]="onlyMissed()" (change)="onlyMissed.set($any($event.target).checked)" />
              {{ m.onlyMissed }} ({{ view().missedCount }})
            </label>
            @if (view().hasQuestionStats) {
              <label class="toggle">
                <input type="checkbox" [checked]="mostMissed()" (change)="mostMissed.set($any($event.target).checked)" />
                {{ m.mostMissed }}
              </label>
            }
          </div>
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
            @if (q.statsView; as st) {
              <div class="q-stats text-sm text-secondary">
                @if (shareText(st.correctShare); as t) { <span>{{ t }}</span> }
                @if (st.averagePoints !== null && q.type !== 'CHOICE') { <span>{{ m.classAvgPoints }}: {{ fmt(st.averagePoints) }} / {{ fmt(q.maxPoints) }}</span> }
                <span>{{ m.answered(st.answered) }}</span>
              </div>
            }

            @for (seg of segments(q.text); track $index) {
              @if (seg.code) { <pre class="q-code"><code>{{ seg.text }}</code></pre> }
              @else { <div class="q-text">{{ seg.text }}</div> }
            }

            @if (q.optionRows.length) {
              <ul class="options" [attr.aria-label]="m.options">
                @for (o of q.optionRows; track o.letter) {
                  <li class="opt" [class.key]="o.correct" [class.mine-wrong]="o.mine && !o.correct" [class.mine]="o.mine">
                    <div class="opt-main">
                      <span class="opt-letter">{{ o.letter }}</span>
                      <div class="opt-body">
                        @if (o.text) { <div class="q-text">{{ o.text }}</div> }
                        <div class="markers">
                          @if (o.correct) { <span class="badge badge-success">✓ {{ m.key }}</span> }
                          @if (o.mine) { <span class="badge" [class.badge-danger]="!o.correct" [class.badge-info]="o.correct">● {{ m.mine }}</span> }
                        </div>
                      </div>
                    </div>
                    @if (o.share !== null) {
                      <div class="bar-row" [attr.aria-label]="o.letter + ': %' + pct(o.share) + ', ' + o.count">
                        <div class="bar"><div class="fill" [style.width.%]="pct(o.share)"></div></div>
                        <span class="bar-text">%{{ pct(o.share) }} · {{ o.count }}</span>
                      </div>
                    }
                    @if (o.rationale && (o.correct || o.mine || allReasons().has(q.position))) {
                      <div class="why">{{ o.rationale }}</div>
                    }
                  </li>
                }
              </ul>
              @if (q.statsView?.blank) {
                <div class="text-sm text-secondary">{{ m.blankAnswers }}: %{{ pct(q.statsView!.blankShare) }} · {{ q.statsView!.blank }}</div>
              }
              @if (hiddenReasons(q.optionRows)) {
                <button type="button" class="btn btn-outline btn-sm mt-sm" (click)="toggleReasons(q.position)" [attr.aria-expanded]="allReasons().has(q.position)">
                  {{ allReasons().has(q.position) ? m.hideAll : m.showAll }}
                </button>
              }
              @if (!q.myAnswer && q.type === 'CHOICE') { <div class="text-sm text-secondary mt-sm">{{ m.myAnswer }}: {{ m.blank }}</div> }
            } @else if (q.type === 'CHOICE' || q.correctChoice) {
              <div class="answers">
                <span class="answer" [class.wrong]="q.correct === false" [class.right]="q.correct === true">
                  {{ m.myAnswer }}: <strong>{{ q.myAnswer || m.blank }}</strong>
                </span>
                @if (q.correctChoice) {
                  <span class="answer key">{{ m.correctAnswer }}: <strong>{{ q.correctChoice }}</strong></span>
                }
              </div>
            } @else if (q.myAnswer) {
              <div class="answers"><span class="answer">{{ m.myAnswer }}: <strong class="q-text">{{ q.myAnswer }}</strong></span></div>
            }

            @if (q.explanation?.trim()) {
              <div class="explanation"><strong>{{ m.explanation }}</strong><div class="q-text">{{ q.explanation }}</div></div>
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
    .exam-title { font-weight: 600; }
    .score-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: var(--space-sm); margin-bottom: var(--space-md); }
    .score { display: flex; flex-direction: column; gap: 2px; padding: var(--space-sm) var(--space-md); margin: 0; }
    .score.mine { border-left: 4px solid var(--c-primary); }
    .score-value { font-size: 1.35rem; }
    .review { border-left: 4px solid var(--c-primary); }
    .review h2 { margin-top: 0; font-size: 1.1rem; }
    .review-list { list-style: none; margin: 0; padding: 0; }
    .review-list li { display: flex; flex-wrap: wrap; align-items: center; gap: 2px 12px; padding: 4px 0; border-top: 1px solid var(--c-border); }
    .review-list li:first-child { border-top: 0; }
    .review-list li.ok { opacity: 0.75; }
    .q-head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--space-sm); margin-top: var(--space-lg); }
    .q-head h2 { margin: 0; }
    .q-controls { display: flex; flex-wrap: wrap; gap: 4px 16px; }
    .toggle { display: inline-flex; align-items: center; gap: 8px; min-height: 40px; cursor: pointer; }
    .question { margin: var(--space-sm) 0; border-left: 4px solid var(--c-border); }
    .question.is-wrong { border-left-color: var(--c-danger); }
    .question.is-right { border-left-color: var(--c-success); }
    .question.is-partial { border-left-color: var(--c-warning); }
    .q-top { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; margin-bottom: 4px; }
    .q-stats { display: flex; flex-wrap: wrap; gap: 2px 12px; margin-bottom: 6px; }
    .grow { flex: 1; }
    .mt-sm { margin-top: 6px; }
    .q-text { white-space: pre-wrap; overflow-wrap: anywhere; margin: 2px 0; }
    .q-code { white-space: pre-wrap; overflow-wrap: anywhere; font-family: var(--font-mono); font-size: 0.88rem; background: var(--c-bg); border: 1px solid var(--c-border); border-radius: var(--radius-md); padding: 8px 10px; margin: 6px 0; }
    .options { list-style: none; margin: 8px 0 4px; padding: 0; display: flex; flex-direction: column; gap: 6px; }
    .opt { border: 1px solid var(--c-border); border-radius: var(--radius-md); padding: 8px 10px; background: var(--c-surface); }
    .opt.key { border-color: var(--c-success); background: var(--c-success-light); }
    .opt.mine-wrong { border-color: var(--c-danger); background: var(--c-danger-light); }
    .opt-main { display: flex; gap: 10px; align-items: flex-start; }
    .opt-letter { font-weight: 700; min-width: 1.4em; }
    .opt-body { flex: 1; min-width: 0; }
    .markers { display: flex; flex-wrap: wrap; gap: 4px; }
    .markers:empty { display: none; }
    .markers .badge { text-transform: none; }
    .bar-row { display: flex; align-items: center; gap: 8px; margin: 6px 0 0 calc(1.4em + 10px); }
    .bar { flex: 1; height: 8px; background: var(--c-border); border-radius: 999px; overflow: hidden; }
    .fill { height: 100%; background: var(--c-text-secondary); border-radius: 999px; }
    .opt.key .fill { background: var(--c-success); }
    .opt.mine-wrong .fill { background: var(--c-danger); }
    .bar-text { font-size: 0.8rem; white-space: nowrap; min-width: 5.5em; text-align: right; }
    .why { margin: 6px 0 0 calc(1.4em + 10px); font-size: 0.88rem; color: var(--c-text-secondary); white-space: pre-wrap; overflow-wrap: anywhere; }
    .answers { display: flex; flex-wrap: wrap; gap: 6px 10px; margin: 8px 0 2px; }
    .answer { padding: 3px 10px; border-radius: var(--radius-md); background: var(--c-bg); border: 1px solid var(--c-border); }
    .answer.wrong { background: var(--c-danger-light); border-color: var(--c-danger); color: var(--c-danger); }
    .answer.right, .answer.key { background: var(--c-success-light); border-color: var(--c-success); color: var(--c-success); }
    .explanation { margin: 8px 0 4px; padding: 8px 10px; border-left: 3px solid var(--c-primary); background: var(--c-primary-light); border-radius: 4px; }
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
  readonly mostMissed = signal(false);
  /** Questions (by position) whose every rationale is shown, not only the key's and my choice's. */
  readonly allReasons = signal<ReadonlySet<number>>(new Set());

  readonly view = computed(() => {
    const r = this.result();
    return r ? resultView(r) : EMPTY_VIEW;
  });
  readonly shown = computed(() => {
    const list = this.onlyMissed() ? this.view().questions.filter(q => q.missed) : this.view().questions;
    return this.mostMissed() ? sortByMostMissed(list) : list;
  });
  readonly myPercent = computed(() => {
    const r = this.result();
    return r && r.maxPoints > 0 ? Math.round((r.points / r.maxPoints) * 100) : null;
  });
  readonly courseCode = computed(() => this.auth.user()?.courses.find(c => c.id === Number(this.id()))?.code ?? '');

  readonly segments = textSegments;
  readonly shareText = correctShareText;

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

  pct(share: number | null): number {
    return percent(share) ?? 0;
  }

  /** Some rationale is hidden by default (it belongs to neither the key nor my choice). */
  hiddenReasons(rows: OptionRow[]): boolean {
    return rows.some(o => o.rationale && !o.correct && !o.mine);
  }

  toggleReasons(position: number) {
    this.allReasons.update(s => { const n = new Set(s); if (n.has(position)) n.delete(position); else n.add(position); return n; });
  }

  fmt(n: number | null | undefined): string {
    return n == null ? '—' : String(Math.round(n * 100) / 100).replace('.', ',');
  }

  formatDay(d: string) {
    try { return new Date(d + 'T00:00:00').toLocaleDateString('tr-TR', { dateStyle: 'medium' }); }
    catch { return d; }
  }
}
