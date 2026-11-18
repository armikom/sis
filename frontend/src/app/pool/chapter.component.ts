import { afterNextRender, Component, computed, effect, inject, Injector, input, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { NgTemplateOutlet } from '@angular/common';
import { DomSanitizer } from '@angular/platform-browser';
import { PoolApiService } from './pool-api.service';
import { PoolContext } from './pool-context.service';
import { PM } from './pool-messages';
import { renderMarkdown } from './pool-util';
import type { ChapterDetail, QuestionListItem } from './pool.models';
import { getItem, setItem } from '../core/storage';
import { errorMessage } from '../shared/errors';
import { TopicChipsComponent } from '../shared/topics/topic-chips.component';
import { DocLang, findSection, poolTopics, Topic, topicFragment, topicHeading } from '../shared/topics/topic';

type Filter = 'all' | 'todo' | 'mine';

/** One chapter: 1. read the text (EN/TR), 2. its questions. */
@Component({
  selector: 'app-pool-chapter',
  imports: [RouterLink, NgTemplateOutlet, TopicChipsComponent],
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
          @if (topics().length) {
            <nav class="card topic-nav" [attr.aria-label]="pm.topicsTitle">
              <b>{{ pm.topicsTitle }}</b>
              <ol>
                @for (t of topics(); track t.id) {
                  <li>
                    <button type="button" class="link" (click)="jumpTo(t)">{{ heading(t) }}</button>
                    <button type="button" class="link small muted" (click)="showTopicQuestions(t)">{{ pm.topicQuestions }}</button>
                  </li>
                }
              </ol>
            </nav>
          }
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
          @if (topicId()) {
            <div class="row mt-sm">
              <span class="small muted">{{ pm.filteredBy }}</span>
              <span class="filter-chip">
                {{ filterTopic() ? heading(filterTopic()!) : '#' + topicId() }}
                <button type="button" (click)="clearTopic()" [attr.aria-label]="pm.clearFilter" [title]="pm.clearFilter">✕</button>
              </span>
            </div>
          }
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
      @if (q.topics?.length) { <app-topic-chips [topics]="itemTopics(q)" /> }
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
  /** ?topic=ID filters the question list by a topic. */
  readonly topicParam = input<string | undefined>(undefined, { alias: 'topic' });
  /** ?lang=en|tr (with a #fragment) opens the text in that language. */
  readonly langParam = input<string | undefined>(undefined, { alias: 'lang' });
  private readonly fragment = toSignal(inject(ActivatedRoute).fragment, { initialValue: null });
  private readonly injector = inject(Injector);

  readonly chapter = signal<ChapterDetail | null>(null);
  readonly questions = signal<QuestionListItem[]>([]);
  readonly error = signal('');
  readonly tab = signal<'read' | 'questions'>('read');
  readonly lang = signal<'en' | 'tr'>(getItem('docLang') === 'tr' ? 'tr' : 'en');
  readonly filter = signal<Filter>('all');
  /** The list of the topic filter (null: no filter, use `questions`). */
  readonly topicQuestions = signal<QuestionListItem[] | null>(null);

  readonly topics = computed(() => {
    const c = this.chapter();
    return c ? poolTopics(c.topics, c) : [];
  });
  readonly topicId = computed(() => Number(this.topicParam()) || null);
  readonly filterTopic = computed(() => this.topics().find(t => t.id === this.topicId()) ?? null);

  /** DOMPurify-sanitized in renderMarkdown, so Angular may keep heading ids and tables. */
  readonly docHtml = computed(() => {
    const c = this.chapter();
    const html = renderMarkdown(this.lang() === 'tr' ? c?.doc_tr : c?.doc_en);
    return html ? this.sanitizer.bypassSecurityTrustHtml(html) : '';
  });
  readonly mineCount = computed(() => this.questions().filter(q => q.mine && q.status !== 'REJECTED').length);
  readonly canWrite = computed(() => this.ctx.staff() || (!!this.chapter()?.open && this.mineCount() < this.ctx.target()));
  readonly shown = computed(() => (this.topicQuestions() ?? this.questions()).filter(q => {
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
    effect(() => {
      const id = this.id();
      const topic = this.topicId();
      untracked(() => this.loadTopicQuestions(id, topic));
    });
    // A #fragment (from the topic preview or a shared link) scrolls to that section once the text is there.
    effect(() => {
      const c = this.chapter();
      const fragment = this.fragment();
      const lang = this.langParam();
      if (c && fragment) untracked(() => this.goToFragment(c, fragment, lang));
    });
  }

  private async loadTopicQuestions(id: string, topic: number | null) {
    this.topicQuestions.set(null);
    if (!topic) return;
    try {
      const list = await this.api.chapterQuestions(id, topic);
      if (id === this.id() && topic === this.topicId()) this.topicQuestions.set(list);
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  itemTopics(q: QuestionListItem): Topic[] {
    return poolTopics(q.topics);
  }

  heading(t: Topic): string {
    return topicHeading(t, this.lang());
  }

  /** Whether the text of `lang` has the section `fragment`. */
  private hasSection(c: ChapterDetail, lang: DocLang, fragment: string): boolean {
    const box = document.createElement('div');
    box.innerHTML = renderMarkdown(lang === 'tr' ? c.doc_tr : c.doc_en);
    return !!findSection(box, fragment, true);
  }

  private goToFragment(c: ChapterDetail, fragment: string, langParam: string | undefined) {
    let lang: DocLang = langParam === 'tr' || langParam === 'en' ? langParam : this.lang();
    const other: DocLang = lang === 'en' ? 'tr' : 'en';
    if (!this.hasSection(c, lang, fragment) && this.hasSection(c, other, fragment)) lang = other;
    this.lang.set(lang);
    this.tab.set('read');
    this.scrollToSection(fragment);
  }

  /** Scrolls after the next render (the text may just have been rendered or switched language). */
  private scrollToSection(fragment: string) {
    const go = () => {
      const root = document.querySelector('.doc');
      const el = root ? findSection(root, fragment) : null;
      el?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      return !!el;
    };
    afterNextRender(() => { if (!go()) setTimeout(go, 150); }, { injector: this.injector });
  }

  /** Topic list → its section in the text (the URL keeps the #fragment, so it can be shared). */
  jumpTo(t: Topic) {
    const fragment = topicFragment(t, this.lang());
    this.tab.set('read');
    this.router.navigate([], { queryParams: { tab: null }, queryParamsHandling: 'merge', fragment, replaceUrl: true });
    this.scrollToSection(fragment);
  }

  showTopicQuestions(t: Topic) {
    this.router.navigate([], { queryParams: { tab: 'questions', topic: t.id }, queryParamsHandling: 'merge' });
  }

  clearTopic() {
    this.router.navigate([], { queryParams: { topic: null }, queryParamsHandling: 'merge', replaceUrl: true });
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
    this.router.navigate([], { queryParams: { tab: t }, queryParamsHandling: 'merge', replaceUrl: true });
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
