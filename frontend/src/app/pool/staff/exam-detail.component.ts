import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { PoolApiService } from '../pool-api.service';
import { PoolContext } from '../pool-context.service';
import { PM } from '../pool-messages';
import { LETTERS } from '../pool-util';
import type { ExamDetail, ExamItem, Letter } from '../pool.models';
import { errorMessage } from '../../shared/errors';

/** Staff: one drawn exam — print forms/keys, swap or remove questions. */
@Component({
  selector: 'app-pool-exam-detail',
  imports: [RouterLink],
  template: `
    <p class="small"><a [routerLink]="[ctx.base(), 'staff', 'exams']">{{ pm.back }}</a></p>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (exam(); as exam) {
      <h1>{{ exam.title }}</h1>
      <p class="muted">{{ pm.meta(exam.items.length, exam.forms) }}</p>

      <div class="card">
        <b>{{ pm.print }}</b>
        <span class="small muted">{{ pm.printHint }}</span>
        <div class="row mt-sm">
          @for (n of formNumbers(); track n) {
            <a [routerLink]="[ctx.base(), 'staff', 'exams', id(), 'print', n]" target="_blank" class="btn">{{ pm.form }} {{ letters[n - 1] }}</a>
            <a [routerLink]="[ctx.base(), 'staff', 'exams', id(), 'print', n]" [queryParams]="{ key: 1 }" target="_blank" class="btn">{{ pm.key }} {{ letters[n - 1] }}</a>
          }
        </div>
        <p class="small note amber">{{ pm.reprint }}</p>
      </div>

      <h2>{{ pm.questions }}</h2>
      <p class="small muted">{{ pm.orderHint }}</p>
      @for (q of exam.items; track q.item_id; let i = $index) {
        <div class="card">
          <div class="row small muted">
            <span>{{ i + 1 }} · {{ pm.week }} {{ q.chapter_number }} · #{{ q.question_id }} · {{ q.author_name }}</span>
            @if (q.attempts) { <span>{{ answered(q.correct_attempts, q.attempts) }}</span> }
            @if (q.open_flags) { <span class="badge red">{{ pm.openReports(q.open_flags) }}</span> }
            @if (q.status !== 'APPROVED') { <span class="badge red">{{ pm.notApproved }}</span> }
          </div>
          <p class="pre"><b>{{ q.stem }}</b></p>
          @for (l of letters; track l) {
            <div class="small" [class.key-option]="q.correct === l">{{ l }}) {{ option(q, l) }}</div>
          }
          <div class="row mt-sm">
            <button type="button" (click)="act(q, 'swap')">{{ pm.swap }}</button>
            <button type="button" class="danger" (click)="act(q, 'remove')">{{ pm.remove }}</button>
            <a [routerLink]="[ctx.base(), 'questions', q.question_id, 'edit']" class="btn">{{ pm.editText }}</a>
            <a [routerLink]="[ctx.base(), 'questions', q.question_id]" class="btn">{{ pm.open }}</a>
          </div>
        </div>
      }
      <p><button type="button" class="danger" (click)="removeExam()">{{ pm.deleteExam }}</button></p>
    }
  `,
})
export class ExamDetailComponent {
  private readonly api = inject(PoolApiService);
  private readonly router = inject(Router);
  readonly ctx = inject(PoolContext);
  readonly pm = PM.exam;
  readonly answered = PM.review.answeredCorrectly;
  readonly letters = LETTERS;
  readonly id = input.required<string>();
  readonly exam = signal<ExamDetail | null>(null);
  readonly error = signal('');

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.load(id).catch(e => this.error.set(errorMessage(e))));
    });
  }

  formNumbers(): number[] {
    return Array.from({ length: this.exam()?.forms ?? 0 }, (_, i) => i + 1);
  }

  private async load(id: string) {
    this.exam.set(await this.api.exam(this.ctx.courseId(), id));
  }

  option(q: ExamItem, l: Letter): string {
    return q[`option_${l.toLowerCase()}` as 'option_a'];
  }

  async act(item: ExamItem, what: 'swap' | 'remove') {
    this.error.set('');
    if (what === 'remove' && !confirm(this.pm.removeConfirm)) return;
    try {
      await this.api.examItem(this.ctx.courseId(), this.id(), item.item_id, what);
      await this.load(this.id());
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  async removeExam() {
    if (!confirm(this.pm.deleteConfirm)) return;
    try {
      await this.api.deleteExam(this.ctx.courseId(), this.id());
      this.router.navigate([this.ctx.base(), 'staff', 'exams']);
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }
}
