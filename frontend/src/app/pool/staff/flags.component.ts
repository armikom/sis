import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PoolApiService } from '../pool-api.service';
import { PoolContext } from '../pool-context.service';
import { PM } from '../pool-messages';
import { when } from '../pool-util';
import type { FlagRow } from '../pool.models';
import { errorMessage } from '../../shared/errors';

/** Staff: problems reported by students. */
@Component({
  selector: 'app-pool-flags',
  imports: [RouterLink],
  template: `
    <h1>{{ pm.title }}</h1>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (loaded() && !flags().length) { <p class="muted">{{ pm.empty }}</p> }
    @for (f of flags(); track f.id) {
      <div class="card">
        <div class="row small muted">
          <span class="badge" [class.green]="f.resolved" [class.red]="!f.resolved">{{ f.resolved ? pm.done : pm.open }}</span>
          <span>{{ f.student_name }} ({{ f.nickname }})</span>
          <span>{{ when(f.created_at) }}</span>
        </div>
        <p class="pre"><b>{{ f.reason }}</b></p>
        <p class="small">{{ pm.on }} <a [routerLink]="[ctx.base(), 'questions', f.question_id]">#{{ f.question_id }}</a>: {{ f.stem }}</p>
        <div class="row">
          <a [routerLink]="[ctx.base(), 'questions', f.question_id, 'edit']" class="btn">{{ pm.edit }}</a>
          @if (!f.resolved) { <button type="button" class="primary" (click)="resolve(f)">{{ pm.markDone }}</button> }
        </div>
      </div>
    }
  `,
})
export class FlagsComponent implements OnInit {
  private readonly api = inject(PoolApiService);
  readonly ctx = inject(PoolContext);
  readonly pm = PM.flags;
  readonly when = when;
  readonly flags = signal<FlagRow[]>([]);
  readonly loaded = signal(false);
  readonly error = signal('');

  async ngOnInit() {
    await this.load();
  }

  private async load() {
    try {
      this.flags.set(await this.api.flags(this.ctx.courseId()));
    } catch (e) {
      this.error.set(errorMessage(e));
    }
    this.loaded.set(true);
  }

  async resolve(f: FlagRow) {
    try {
      await this.api.resolveFlag(this.ctx.courseId(), f.id);
      await this.load();
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }
}
