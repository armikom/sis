import { Injectable, computed, inject, signal } from '@angular/core';
import { PoolApiService } from './pool-api.service';
import type { PoolMe } from './pool.models';
import { errorMessage } from '../shared/errors';

/**
 * The course the pool screens work on and my place in it (GET /api/pool/courses/{id}/me). Provided by the pool route,
 * loaded by the pool shell; child screens render only after `me` is set, so they may read `me()!`.
 */
@Injectable()
export class PoolContext {
  private readonly api = inject(PoolApiService);
  readonly courseId = signal<number>(0);
  readonly me = signal<PoolMe | null>(null);
  readonly error = signal('');

  readonly staff = computed(() => this.me()?.staff === true);
  readonly target = computed(() => this.me()?.questions_per_chapter ?? 0);
  /** Router prefix of every pool screen of this course. */
  readonly base = computed(() => `/courses/${this.courseId()}/pool`);
  readonly courseLabel = computed(() => this.me()?.course.code ?? '');

  private pending: Promise<void> | null = null;

  /** Loads `me` for a course once; concurrent callers (guard, shell) share the request. */
  load(courseId: number): Promise<void> {
    if (courseId === this.courseId() && (this.me() || this.pending)) return this.pending ?? Promise.resolve();
    this.courseId.set(courseId);
    this.me.set(null);
    this.error.set('');
    const p = this.api.me(courseId)
      .then(me => { if (this.courseId() === courseId) this.me.set(me); })
      .catch(e => { if (this.courseId() === courseId) this.error.set(errorMessage(e)); })
      .finally(() => { if (this.pending === p) this.pending = null; });
    this.pending = p;
    return p;
  }
}
