import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { PoolApiService } from '../pool-api.service';
import { PoolContext } from '../pool-context.service';
import { PM } from '../pool-messages';
import { examTotal, when } from '../pool-util';
import type { ChapterSummary, ExamRequest, ExamSummary } from '../pool.models';
import { errorMessage } from '../../shared/errors';

/** Staff: exams drawn from the approved questions, and the builder for a new one. */
@Component({
  selector: 'app-pool-exams',
  imports: [FormsModule, RouterLink],
  template: `
    <h1>{{ pm.title }}</h1>
    <p class="muted small">{{ pm.intro }}</p>

    @for (e of exams(); track e.id) {
      <a [routerLink]="[ctx.base(), 'staff', 'exams', e.id]" class="card">
        <b>{{ e.title }}</b>
        <div class="small muted">{{ pm.meta(e.questions, e.forms, when(e.created_at)) }}</div>
      </a>
    }

    <h2>{{ pm.newTitle }}</h2>
    <form class="card" (ngSubmit)="create()" ngNativeValidate>
      <label for="title">{{ pm.titleLabel }}</label>
      <input id="title" name="title" type="text" [(ngModel)]="form.title" maxlength="120" required />

      <label>{{ pm.fromEach }}</label>
      <div class="scroll"><table>
        <thead><tr><th>{{ pm.chapter }}</th><th>{{ pm.approved }}</th><th class="take">{{ pm.take }}</th></tr></thead>
        <tbody>
          @for (c of chapters(); track c.id) {
            <tr>
              <td>{{ c.title }}</td>
              <td>{{ c.approved }}</td>
              <td><input class="count" type="number" min="0" [max]="c.approved" [name]="'count-' + c.id"
                [ngModel]="form.counts[c.id]" (ngModelChange)="setCount(c.id, $event)" [attr.aria-label]="pm.take + ' ' + c.title" /></td>
            </tr>
          }
          <tr><td><b>{{ pm.total }}</b></td><td></td><td><b>{{ total() }}</b></td></tr>
        </tbody>
      </table></div>

      <label for="forms">{{ pm.forms }}</label>
      <select id="forms" name="forms" class="auto" [(ngModel)]="form.forms">
        <option [ngValue]="1">1 (A)</option><option [ngValue]="2">2 (A, B)</option>
        <option [ngValue]="3">3 (A, B, C)</option><option [ngValue]="4">4 (A, B, C, D)</option>
      </select>
      <p><label class="inline"><input type="checkbox" name="skip" [(ngModel)]="form.skip_flagged" /> {{ pm.skipFlagged }}</label></p>
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      <button type="submit" class="primary" [disabled]="busy() || !total()">{{ pm.draw }}</button>
    </form>
  `,
})
export class ExamsComponent implements OnInit {
  private readonly api = inject(PoolApiService);
  private readonly router = inject(Router);
  readonly ctx = inject(PoolContext);
  readonly pm = PM.exams;
  readonly when = when;
  readonly exams = signal<ExamSummary[]>([]);
  readonly chapters = signal<ChapterSummary[]>([]);
  readonly total = signal(0);
  readonly error = signal('');
  readonly busy = signal(false);
  form: ExamRequest = { title: PM.exams.defaultTitle(this.ctx.courseLabel()), forms: 2, skip_flagged: true, counts: {} };

  async ngOnInit() {
    const courseId = this.ctx.courseId();
    try {
      const [exams, chapters] = await Promise.all([this.api.exams(courseId), this.api.chapters(courseId)]);
      for (const c of chapters) this.form.counts[c.id] = 0;
      this.exams.set(exams);
      this.chapters.set(chapters);
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  setCount(chapterId: number, v: number | null) {
    this.form.counts[chapterId] = Number(v) || 0;
    this.total.set(examTotal(this.form.counts));
  }

  async create() {
    this.busy.set(true);
    this.error.set('');
    try {
      const { id } = await this.api.createExam(this.ctx.courseId(), this.form);
      this.router.navigate([this.ctx.base(), 'staff', 'exams', id]);
    } catch (e) {
      this.error.set(errorMessage(e));
      this.busy.set(false);
    }
  }
}
