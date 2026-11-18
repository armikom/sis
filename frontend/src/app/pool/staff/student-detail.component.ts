import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PoolApiService } from '../pool-api.service';
import { PoolContext } from '../pool-context.service';
import { PM } from '../pool-messages';
import { when } from '../pool-util';
import type { StudentDetail } from '../pool.models';
import { StatusBadgeComponent } from '../components/status-badge.component';
import { errorMessage } from '../../shared/errors';

/** Staff: one student's questions, answers, comments and reports in this course (id = person id). */
@Component({
  selector: 'app-pool-student-detail',
  imports: [RouterLink, StatusBadgeComponent],
  template: `
    <p class="small"><a [routerLink]="[ctx.base(), 'staff', 'students']">{{ pm.back }}</a></p>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (s(); as s) {
      <h1>{{ s.full_name }}</h1>
      <p class="muted">{{ s.student_no }} · {{ s.email }} · {{ pm.nickname }} {{ s.nickname }} · {{ pm.lastLogin }} {{ when(s.last_login_at) }}</p>

      <h2>{{ pm.written(s.questions.length) }}</h2>
      @for (q of s.questions; track q.id) {
        <a [routerLink]="[ctx.base(), 'questions', q.id]" class="card">
          <div class="row small muted"><span>#{{ q.id }} · {{ pm.week }} {{ q.chapter_number }}</span><pool-status-badge [status]="q.status" /><span>{{ when(q.created_at) }}</span></div>
          <div>{{ q.stem }}</div>
        </a>
      }

      <h2>{{ pm.byChapter }}</h2>
      @if (!s.attempts_by_chapter.length) {
        <p class="muted">{{ pm.noAnswers }}</p>
      } @else {
        <div class="scroll"><table>
          <thead><tr><th>{{ pm.week }}</th><th>{{ pm.answered }}</th><th>{{ pm.correct }}</th></tr></thead>
          <tbody>
            @for (a of s.attempts_by_chapter; track a.chapter_number) {
              <tr><td>{{ a.chapter_number }}</td><td>{{ a.attempts }}</td><td>{{ a.correct }}</td></tr>
            }
          </tbody>
        </table></div>
      }

      <h2>{{ pm.comments(s.comments.length) }}</h2>
      @for (c of s.comments; track c.id) {
        <div class="card">
          <div class="small muted">
            {{ when(c.created_at) }} {{ pm.on }} <a [routerLink]="[ctx.base(), 'questions', c.question_id]">#{{ c.question_id }}</a>: {{ c.stem }}
            @if (c.deleted) { <span class="badge red">{{ pm.hidden }}</span> }
          </div>
          <div class="pre">{{ c.body }}</div>
        </div>
      }

      <h2>{{ pm.flags(s.flags.length) }}</h2>
      @for (f of s.flags; track f.id) {
        <div class="card">
          <div class="small muted">{{ when(f.created_at) }} {{ pm.on }} <a [routerLink]="[ctx.base(), 'questions', f.question_id]">#{{ f.question_id }}</a></div>
          <div class="pre">{{ f.reason }}</div>
        </div>
      }
    }
  `,
})
export class StudentDetailComponent {
  private readonly api = inject(PoolApiService);
  readonly ctx = inject(PoolContext);
  readonly pm = PM.student;
  readonly when = when;
  readonly id = input.required<string>();
  readonly s = signal<StudentDetail | null>(null);
  readonly error = signal('');

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.load(id));
    });
  }

  private async load(id: string) {
    this.s.set(null);
    this.error.set('');
    try {
      this.s.set(await this.api.student(this.ctx.courseId(), id));
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }
}
