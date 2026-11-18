import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PoolApiService } from './pool-api.service';
import { PoolContext } from './pool-context.service';
import { PM } from './pool-messages';
import type { MyQuestion } from './pool.models';
import { StatusBadgeComponent } from './components/status-badge.component';
import { errorMessage } from '../shared/errors';

/** My questions in this course, with status and instructor notes. */
@Component({
  selector: 'app-pool-mine',
  imports: [RouterLink, StatusBadgeComponent],
  template: `
    <h1>{{ pm.title }}</h1>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (questions()?.length === 0) {
      <p class="muted">{{ pm.emptyA }}<a [routerLink]="ctx.base()">{{ pm.emptyLink }}</a>{{ pm.emptyB }}</p>
    }
    @for (q of questions(); track q.id) {
      <a [routerLink]="[ctx.base(), 'questions', q.id]" class="card">
        <div class="small muted">{{ q.chapter_title }}</div>
        <div>{{ q.stem }}</div>
        <div class="row small muted mt-sm">
          <pool-status-badge [status]="q.status" />
          @if (q.ai_status === 'RUNNING') { <span>{{ pm.running }}</span> }
          @if (q.status === 'APPROVED') { <span>{{ pm.answers(q.attempts) }}</span> }
          @if (q.comments) { <span>💬 {{ q.comments }}</span> }
        </div>
        @if (q.instructor_note && q.status !== 'APPROVED') {
          <div class="note amber small">{{ pm.instructor }} {{ q.instructor_note }}</div>
        }
      </a>
    }
  `,
})
export class MineComponent implements OnInit {
  private readonly api = inject(PoolApiService);
  readonly ctx = inject(PoolContext);
  readonly pm = PM.mine;
  readonly questions = signal<MyQuestion[] | null>(null);
  readonly error = signal('');

  async ngOnInit() {
    try {
      this.questions.set(await this.api.myQuestions(this.ctx.courseId()));
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }
}
