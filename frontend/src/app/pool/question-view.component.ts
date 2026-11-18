import { Component, computed, effect, inject, input, OnDestroy, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { PoolApiService } from './pool-api.service';
import { PoolContext } from './pool-context.service';
import { PM } from './pool-messages';
import { LETTERS, nextTodo } from './pool-util';
import type { Letter, QuestionDetail } from './pool.models';
import { StatusBadgeComponent } from './components/status-badge.component';
import { AiReviewComponent } from './components/ai-review.component';
import { ThreadComponent } from './components/thread.component';
import { errorMessage } from '../shared/errors';

/**
 * One question. Attempt first: the key, the reasons and the discussion appear only after the first answer, and only
 * the first answer counts (the server enforces both; `revealed` tells the state).
 */
@Component({
  selector: 'app-pool-question-view',
  imports: [RouterLink, StatusBadgeComponent, AiReviewComponent, ThreadComponent],
  template: `
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (q(); as q) {
      <p class="small"><a [routerLink]="[ctx.base(), 'chapters', q.chapter_id]" [queryParams]="{ tab: 'questions' }">{{ pm.back }}</a></p>
      <div class="row small muted">
        <span>#{{ q.id }}</span>
        @if (q.mine) { <span class="badge blue">{{ pm.mine }}</span> } @else { <span>{{ pm.by }} {{ q.author }}</span> }
        @if (q.mine || ctx.staff()) { <pool-status-badge [status]="q.status" /> }
      </div>
      <h1 class="pre">{{ q.stem }}</h1>

      <!-- Author: feedback and next steps -->
      @if (q.mine) {
        @switch (q.status) {
          @case ('PENDING') { <div class="note">{{ pm.pending }}</div> }
          @case ('NEEDS_REVISION') { <div class="note amber"><b>{{ pm.revise }}</b> {{ q.instructor_note }}</div> }
          @case ('REJECTED') { <div class="note red"><b>{{ pm.rejected }}</b> {{ q.instructor_note }}{{ pm.rejectedEnd }}</div> }
          @case ('APPROVED') { <div class="note green">{{ pm.approved }}</div> }
        }
        @if (q.status !== 'APPROVED') { <pool-ai-review [status]="q.ai_status" [review]="q.ai_review" /> }
      } @else if (ctx.staff()) {
        <pool-ai-review [status]="q.ai_status" [review]="q.ai_review" />
      }

      @if (!q.revealed) {
        <!-- Before the answer: choose one option -->
        @for (l of letters; track l) {
          <label class="option" [class.picked]="selected() === l">
            <input type="radio" name="answer" [value]="l" [checked]="selected() === l" (change)="selected.set(l)" />
            <span class="letter">{{ l }}</span>
            <span class="grow">{{ option(q, l) }}</span>
          </label>
        }
        <p class="row">
          <button type="button" class="primary" [disabled]="!selected() || busy()" (click)="answer()">{{ pm.answer }}</button>
          <span class="small muted">{{ pm.onlyOnce }}</span>
        </p>
      } @else {
        <!-- After the answer: key, reasons, explanation -->
        @if (q.my_selected) {
          <div class="note" [class.green]="q.my_selected === q.correct" [class.red]="q.my_selected !== q.correct">
            <b>{{ q.my_selected === q.correct ? pm.correctBang : pm.notCorrect }}</b>
            {{ pm.youChose(q.my_selected, q.correct ?? '') }}
          </div>
        }
        @for (l of letters; track l) {
          <div class="option" [class.correct]="q.correct === l" [class.wrong]="q.my_selected === l && q.correct !== l">
            <span class="letter">{{ l }}</span>
            <div class="grow">
              <div>
                {{ option(q, l) }}
                @if (q.correct === l) { <span class="badge green">{{ pm.correct }}</span> }
                @if (q.my_selected === l) { <span class="badge">{{ pm.yourAnswer }}</span> }
              </div>
              <div class="why">{{ rationale(q, l) }}</div>
            </div>
          </div>
        }
        <p class="small muted">{{ pm.reasonsBy }} {{ q.reference }}</p>
        @if (q.explanation) {
          <div class="note">
            <b>{{ pm.explanation }}</b> <span class="small muted">{{ pm.explanationBy }}</span>
            <div class="pre">{{ q.explanation }}</div>
          </div>
        }
      }

      <div class="row actions">
        @if (q.revealed && nextId()) { <a [routerLink]="[ctx.base(), 'questions', nextId()]" class="btn primary">{{ pm.next }}</a> }
        @if (editable()) { <a [routerLink]="[ctx.base(), 'questions', q.id, 'edit']" class="btn">{{ pm.change }}</a> }
        @if (q.mine && q.status !== 'APPROVED') { <button type="button" class="danger" (click)="remove()">{{ pm.delete }}</button> }
        @if (q.revealed && !q.mine && !ctx.staff() && !flagged()) { <button type="button" (click)="flag()">{{ pm.report }}</button> }
        @if (flagged()) { <span class="small muted">{{ pm.thanks }}</span> }
      </div>

      @if (discussable()) {
        <pool-thread [questionId]="q.id" />
      }
    }
  `,
})
export class QuestionViewComponent implements OnDestroy {
  private readonly api = inject(PoolApiService);
  private readonly router = inject(Router);
  readonly ctx = inject(PoolContext);
  readonly pm = PM.question;
  readonly letters = LETTERS;
  readonly id = input.required<string>();

  readonly q = signal<QuestionDetail | null>(null);
  readonly selected = signal<Letter | ''>('');
  readonly error = signal('');
  readonly busy = signal(false);
  readonly nextId = signal<number | null>(null);
  readonly flagged = signal(false);
  private timer: ReturnType<typeof setTimeout> | undefined;

  readonly editable = computed(() => {
    const q = this.q();
    return this.ctx.staff() || (!!q?.mine && (q.status === 'PENDING' || q.status === 'NEEDS_REVISION'));
  });
  readonly discussable = computed(() => {
    const q = this.q();
    return !!q?.revealed && (q.status === 'APPROVED' || this.ctx.staff());
  });

  constructor() {
    // "Next question" reuses this component with a new id: start over.
    effect(() => {
      const id = this.id();
      untracked(() => this.start(id));
    });
  }

  ngOnDestroy() {
    clearTimeout(this.timer);
  }

  private async start(id: string) {
    clearTimeout(this.timer);
    this.q.set(null);
    this.selected.set('');
    this.nextId.set(null);
    this.flagged.set(false);
    this.error.set('');
    try {
      await this.load(id);
      if (!this.ctx.staff()) await this.findNext();
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  private async load(id: string) {
    const q = await this.api.question(id);
    if (id !== this.id()) return;
    this.q.set(q);
    // While the automatic check runs, look again every few seconds.
    clearTimeout(this.timer);
    if (q.ai_status === 'RUNNING') this.timer = setTimeout(() => this.load(id).catch(() => {}), 4000);
  }

  private async findNext() {
    const q = this.q();
    if (!q) return;
    this.nextId.set(nextTodo(await this.api.chapterQuestions(q.chapter_id), q.id));
  }

  option(q: QuestionDetail, l: Letter): string {
    return q[`option_${l.toLowerCase()}` as 'option_a'];
  }

  rationale(q: QuestionDetail, l: Letter): string {
    return q[`rationale_${l.toLowerCase()}` as 'rationale_a'] ?? '';
  }

  async answer() {
    this.busy.set(true);
    this.error.set('');
    try {
      this.q.set(await this.api.attempt(this.id(), this.selected()));
    } catch (e) {
      this.error.set(errorMessage(e));
    }
    this.busy.set(false);
  }

  async remove() {
    if (!confirm(this.pm.deleteConfirm)) return;
    const chapterId = this.q()!.chapter_id;
    try {
      await this.api.deleteQuestion(this.id());
      this.router.navigate([this.ctx.base(), 'chapters', chapterId], { queryParams: { tab: 'questions' } });
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  async flag() {
    const reason = prompt(this.pm.flagPrompt);
    if (!reason) return;
    try {
      await this.api.flag(this.id(), reason);
      this.flagged.set(true);
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }
}
