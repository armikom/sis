import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PoolApiService } from './pool-api.service';
import { PoolContext } from './pool-context.service';
import { PM } from './pool-messages';
import type { ChapterSummary } from './pool.models';
import { errorMessage } from '../shared/errors';

/** Pool home: the chapters with my progress. */
@Component({
  selector: 'app-pool-chapters-home',
  imports: [RouterLink],
  template: `
    <h1>{{ pm.title }}</h1>
    @if (!ctx.staff()) {
      <div class="note">
        <b>{{ pm.howTitle }}</b> {{ pm.how(ctx.target()) }}
        <div class="small muted">{{ pm.nickname }} <b>{{ ctx.me()!.nickname }}</b>{{ pm.nicknameEnd }}</div>
      </div>
    }
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (!chapters() && !error()) { <p class="muted">{{ loading }}</p> }
    @if (chapters()?.length === 0) { <p class="muted">{{ pm.empty }}</p> }

    @for (c of chapters(); track c.id) {
      <a [routerLink]="[ctx.base(), 'chapters', c.id]" class="card">
        <div class="row spread">
          <b>{{ c.title }}</b>
          @if (!c.open) { <span class="badge">{{ pm.closed }}</span> }
        </div>
        <div class="row small muted mt-sm">
          @if (!ctx.staff()) {
            <span class="badge" [class.green]="c.my_questions >= ctx.target()" [class.amber]="c.my_questions < ctx.target()">{{ pm.myQuestions }} {{ c.my_questions }}/{{ ctx.target() }}</span>
            <span>{{ pm.answered(c.my_attempts, c.approved) }}</span>
            @if (c.my_attempts) { <span>{{ pm.correct }} {{ c.my_correct }}</span> }
          } @else {
            <span>{{ pm.approved(c.approved) }}</span>
          }
          @if (!c.has_doc_en && !c.has_doc_tr) { <span>{{ pm.noText }}</span> }
        </div>
        @if (!ctx.staff() && c.approved) {
          <div class="bar"><i [style.width.%]="(100 * c.my_attempts) / c.approved"></i></div>
        }
      </a>
    }
  `,
})
export class ChaptersHomeComponent implements OnInit {
  private readonly api = inject(PoolApiService);
  readonly ctx = inject(PoolContext);
  readonly pm = PM.home;
  readonly loading = PM.loading;
  readonly chapters = signal<ChapterSummary[] | null>(null);
  readonly error = signal('');

  async ngOnInit() {
    try {
      this.chapters.set(await this.api.chapters(this.ctx.courseId()));
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }
}
