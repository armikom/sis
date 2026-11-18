import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PoolApiService } from '../pool-api.service';
import { PoolContext } from '../pool-context.service';
import { PM } from '../pool-messages';
import { threadTree, when } from '../pool-util';
import type { PoolComment } from '../pool.models';
import { errorMessage } from '../../shared/errors';

/** Discussion under a question, with "ask the AI helper". */
@Component({
  selector: 'pool-thread',
  imports: [FormsModule],
  template: `
    <h2>{{ pm.title }}</h2>
    <p class="small muted">{{ pm.intro }}</p>
    @if (!comments().length) { <p class="muted">{{ pm.empty }}</p> }

    @for (t of tree(); track t.root.id) {
      @for (c of [t.root].concat(t.replies); track c.id) {
        <div class="comment" [class.reply]="c !== t.root" [class.ai]="c.is_ai">
          <div class="row small muted">
            <b>{{ c.author }}</b>
            @if (c.real_name && !c.is_ai) { <span>({{ c.real_name }})</span> }
            @if (c.wrote_question) { <span class="badge blue">{{ pm.wrote }}</span> }
            @if (c.mine) { <span class="badge">{{ pm.you }}</span> }
            <span>{{ when(c.created_at) }}</span>
            <button type="button" class="link" (click)="replyTo.set(c)">{{ pm.reply }}</button>
            @if (ctx.staff()) { <button type="button" class="link" (click)="hide(c)">{{ pm.hide }}</button> }
          </div>
          <div class="pre">{{ c.body }}</div>
        </div>
      }
    }

    <div class="card">
      @if (replyTo(); as r) {
        <div class="small muted">{{ pm.replyTo }} {{ r.author }} <button type="button" class="link" (click)="replyTo.set(null)">{{ pm.cancel }}</button></div>
      }
      <textarea [ngModel]="body()" (ngModelChange)="body.set($event)" maxlength="2000" [placeholder]="pm.placeholder" aria-label="Comment"></textarea>
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      <div class="row mt-sm">
        <button type="button" class="primary" [disabled]="!!busy() || !body().trim()" (click)="send(false)">{{ pm.send }}</button>
        @if (ctx.me()?.ai_enabled) {
          <button type="button" [disabled]="!!busy() || !body().trim()" (click)="send(true)">{{ busy() === 'ai' ? pm.thinking : pm.askAi }}</button>
        }
      </div>
      @if (ctx.me()?.ai_enabled) { <p class="small muted">{{ pm.aiNote }}</p> }
    </div>
  `,
})
export class ThreadComponent implements OnInit {
  private readonly api = inject(PoolApiService);
  readonly ctx = inject(PoolContext);
  readonly questionId = input.required<number>();
  readonly pm = PM.thread;
  readonly when = when;
  readonly comments = signal<PoolComment[]>([]);
  readonly body = signal('');
  readonly replyTo = signal<PoolComment | null>(null);
  readonly busy = signal<'' | 'ai' | 'send'>('');
  readonly error = signal('');
  readonly tree = computed(() => threadTree(this.comments()));

  ngOnInit() {
    this.load().catch(e => this.error.set(errorMessage(e)));
  }

  private async load() {
    this.comments.set(await this.api.comments(this.questionId()));
  }

  async send(askAi: boolean) {
    if (!this.body().trim()) return;
    this.busy.set(askAi ? 'ai' : 'send');
    this.error.set('');
    try {
      await this.api.comment(this.questionId(), this.body(), this.replyTo()?.id ?? null, askAi);
      this.body.set('');
      this.replyTo.set(null);
      await this.load();
    } catch (e) {
      this.error.set(errorMessage(e));
    }
    this.busy.set('');
  }

  async hide(c: PoolComment) {
    if (!confirm(this.pm.hideConfirm)) return;
    try {
      await this.api.hideComment(this.ctx.courseId(), c.id);
      await this.load();
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }
}
