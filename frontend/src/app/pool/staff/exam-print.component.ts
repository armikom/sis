import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PoolApiService } from '../pool-api.service';
import { PM } from '../pool-messages';
import { LETTERS } from '../pool-util';
import type { ExamForm, PoolMe } from '../pool.models';
import { errorMessage } from '../../shared/errors';

/** Printable exam form or answer key (no shell, no menu): …/staff/exams/:id/print/:form[?key=1]. */
@Component({
  selector: 'app-pool-exam-print',
  imports: [RouterLink],
  template: `
    <main class="pool-app pool-print">
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      @if (form(); as form) {
        <p class="no-print row">
          <button type="button" class="primary" (click)="print()">{{ pm.printBtn }}</button>
          <a [routerLink]="['/courses', courseId(), 'pool', 'staff', 'exams', id()]">{{ pm.back }}</a>
        </p>

        @if (showKey()) {
          <div class="exam-head">
            <h1 class="flush">{{ form.title }} — {{ pm.keyTitle }} {{ form.form }}</h1>
            <div>{{ pm.keyNote }}</div>
          </div>
          <div class="key-grid">
            @for (q of form.questions; track q.number) {
              <div><b>{{ q.number }}.</b> {{ q.correct }} <span class="key-id">(#{{ q.question_id }})</span></div>
            }
          </div>
        } @else {
          <div class="exam-head">
            <div class="row spread">
              <h1 class="flush">{{ form.title }}</h1>
              <h1 class="flush">Form {{ form.form }}</h1>
            </div>
            @if (me(); as me) { <div>{{ me.course.code }} {{ me.course.name }} · {{ me.course.term }}</div> }
            <p>{{ pm.name }} <span class="line"></span> &nbsp; {{ pm.studentNo }} <span class="line short"></span> &nbsp; {{ pm.signature }} <span class="line short"></span></p>
            <div>{{ pm.intro(form.questions.length) }}</div>
          </div>
          @for (q of form.questions; track q.number) {
            <div class="exam-q">
              <div class="pre"><b>{{ q.number }}.</b> {{ q.stem }}</div>
              <ol>
                @for (text of q.options; track $index) { <li>{{ letters[$index] }}) {{ text }}</li> }
              </ol>
            </div>
          }
        }
      }
    </main>
  `,
})
export class ExamPrintComponent {
  private readonly api = inject(PoolApiService);
  readonly pm = PM.print;
  readonly letters = LETTERS;
  readonly courseId = input.required<string>();
  readonly id = input.required<string>();
  readonly formNo = input.required<string>({ alias: 'form' });
  readonly key = input<string | undefined>(undefined);
  readonly form = signal<ExamForm | null>(null);
  readonly me = signal<PoolMe | null>(null);
  readonly error = signal('');

  showKey() {
    return this.key() === '1';
  }

  constructor() {
    effect(() => {
      const [courseId, id, form] = [this.courseId(), this.id(), this.formNo()];
      untracked(() => this.load(courseId, id, form));
    });
  }

  private async load(courseId: string, id: string, formNo: string) {
    try {
      const [form, me] = await Promise.all([this.api.examForm(courseId, id, formNo), this.api.me(courseId).catch(() => null)]);
      this.form.set(form);
      this.me.set(me);
      document.title = `${form.title} - ${this.showKey() ? 'Key' : 'Form'} ${form.form}`;
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  print() {
    window.print();
  }
}
