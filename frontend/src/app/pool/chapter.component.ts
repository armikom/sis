import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgTemplateOutlet } from '@angular/common';
import { DomSanitizer } from '@angular/platform-browser';
import { PoolApiService } from './pool-api.service';
import { PoolContext } from './pool-context.service';
import { PM } from './pool-messages';
import { renderMarkdown } from './pool-util';
import type { ChapterDetail, QuestionListItem } from './pool.models';
import { getItem, setItem } from '../core/storage';
import { errorMessage } from '../shared/errors';

type Filter = 'all' | 'todo' | 'mine';

/** One chapter: 1. read the text (EN/TR), 2. its questions. */
@Component({
  selector: 'app-pool-chapter',
  imports: [RouterLink, NgTemplateOutlet],
  template: `
    <p class="small"><a [routerLink]="ctx.base()">{{ pm.back }}</a></p>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (chapter(); as ch) {
      <h1>{{ ch.title }}</h1>
      <div class="tabs" role="tablist">
        <button type="button" role="tab" [class.on]="tab() === 'read'" [attr.aria-selected]="tab() === 'read'" (click)="setTab('read')">{{ pm.read }}</button>
        <button type="button" role="tab" [class.on]="tab() === 'questions'" [attr.aria-selected]="tab() === 'questions'" (click)="setTab('questions')">{{ pm.questions(questions().length) }}</button>
      </div>

      @if (tab() === 'read') {
        <section>
          <div class="row">
            <span class="muted small">{{ pm.language }}</span>
            <label class="inline"><input type="radio" name="doclang" [checked]="lang() === 'en'" (change)="setLang('en')" /> English</label>
            <label class="inline"><input type="radio" name="doclang" [checked]="lang() === 'tr'" (change)="setLang('tr')" /> Türkçe</label>
          </div>
          @if (docHtml()) {
            <div class="card doc" [innerHTML]="docHtml()" (click)="onDocClick($event)"></div>
          } @else {
            <p class="muted">{{ pm.notReady }}</p>
          }
          <p><button type="button" class="primary" (click)="setTab('questions')">{{ pm.toQuestions }}</button></p>
        </section>
      } @else {
        <section>
          <div class="row spread">
            <div class="tabs flush">
              <button type="button" [class.on]="filter() === 'all'" (click)="filter.set('all')">{{ pm.all }}</button>
              @if (!ctx.staff()) {
                <button type="button" [class.on]="filter() === 'todo'" (click)="filter.set('todo')">{{ pm.todo }}</button>
                <button type="button" [class.on]="filter() === 'mine'" (click)="filter.set('mine')">{{ pm.mine }}</button>
              }
            </div>
            @if (canWrite()) { <a [routerLink]="[ctx.base(), 'chapters', ch.id, 'new']" class="btn primary">{{ pm.write }}</a> }
          </div>
          @if (!ctx.staff()) {
            <p class="small muted">
              {{ pm.wrote(mineCount(), ctx.target()) }}
              @if (!ch.open) { <span>{{ pm.closed }}</span> }
              {{ pm.readFirst }}
            </p>
          }
          @if (!shown().length) { <p class="muted">{{ pm.empty }}</p> }

          @for (q of shown(); track q.id) {
            @if (canOpen(q)) {
              <a [routerLink]="[ctx.base(), 'questions', q.id]" class="card">
                <ng-container *ngTemplateOutlet="item; context: { $implicit: q }" />
              </a>
            } @else {
              <div class="card">
                <ng-container *ngTemplateOutlet="item; context: { $implicit: q }" />
              </div>
            }
          }
        </section>
      }
    }

    <ng-template #item let-q>
      <div>{{ q.stem }}</div>
      <div class="row small muted mt-sm">
        <span>#{{ q.id }}</span>
        @if (q.mine) { <span class="badge blue">{{ pm.mine }}</span> } @else { <span>{{ pm.by }} {{ q.author }}</span> }
        @switch (q.status) {
          @case ('PENDING') { <span class="badge amber">{{ status.PENDING[0] }}</span> }
          @case ('NEEDS_REVISION') { <span class="badge amber">{{ status.NEEDS_REVISION[0] }}</span> }
          @case ('REJECTED') { <span class="badge red">{{ status.REJECTED[0] }}</span> }
          @default {
            @if (!q.mine && !ctx.staff()) {
              @if (!q.attempted) { <span class="badge">{{ pm.notAnswered }}</span> }
              @else if (q.my_correct) { <span class="badge green">{{ pm.right }}</span> }
              @else { <span class="badge red">{{ pm.wrong }}</span> }
            }
          }
        }
        @if (q.comments) { <span>💬 {{ q.comments }}</span> }
      </div>
    </ng-template>
  `,
})
export class ChapterComponent {
  private readonly api = inject(PoolApiService);
  private readonly router = inject(Router);
  private readonly sanitizer = inject(DomSanitizer);
  readonly ctx = inject(PoolContext);
  readonly pm = PM.chapter;
  readonly status = PM.status;
  readonly id = input.required<string>();
  /** ?tab=questions opens the question list. */
  readonly tabParam = input<string | undefined>(undefined, { alias: 'tab' });

  readonly chapter = signal<ChapterDetail | null>(null);
  readonly questions = signal<QuestionListItem[]>([]);
  readonly error = signal('');
  readonly tab = signal<'read' | 'questions'>('read');
  readonly lang = signal<'en' | 'tr'>(getItem('docLang') === 'tr' ? 'tr' : 'en');
  readonly filter = signal<Filter>('all');

  /** DOMPurify-sanitized in renderMarkdown, so Angular may keep heading ids and tables. */
  readonly docHtml = computed(() => {
    const c = this.chapter();
    const html = renderMarkdown(this.lang() === 'tr' ? c?.doc_tr : c?.doc_en);
    return html ? this.sanitizer.bypassSecurityTrustHtml(html) : '';
  });
  readonly mineCount = computed(() => this.questions().filter(q => q.mine && q.status !== 'REJECTED').length);
  readonly canWrite = computed(() => this.ctx.staff() || (!!this.chapter()?.open && this.mineCount() < this.ctx.target()));
  readonly shown = computed(() => this.questions().filter(q => {
    const f = this.filter();
    if (f === 'mine') return q.mine;
    if (f === 'todo') return q.status === 'APPROVED' && !q.mine && !q.attempted;
    return true;
  }));

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.load(id));
    });
    effect(() => this.tab.set(this.tabParam() === 'questions' ? 'questions' : 'read'));
  }

  private async load(id: string) {
    this.error.set('');
    try {
      const [c, qs] = await Promise.all([this.api.chapter(id), this.api.chapterQuestions(id)]);
      this.chapter.set(c);
      this.questions.set(qs);
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  /** Students open their own questions and approved ones. Staff open everything. */
  canOpen(q: QuestionListItem): boolean {
    return q.mine || q.status === 'APPROVED' || this.ctx.staff();
  }

  setTab(t: 'read' | 'questions') {
    this.tab.set(t);
    this.router.navigate([], { queryParams: { tab: t }, replaceUrl: true });
  }

  setLang(l: 'en' | 'tr') {
    this.lang.set(l);
    setItem('docLang', l);
  }

  /** In-page links of the chapter text ("#section") scroll instead of leaving the app. */
  onDocClick(e: MouseEvent) {
    const a = (e.target as HTMLElement | null)?.closest('a');
    const href = a?.getAttribute('href');
    if (href?.startsWith('#')) {
      e.preventDefault();
      document.getElementById(decodeURIComponent(href.slice(1)))?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }
}
