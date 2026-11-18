import {
  afterNextRender, Component, computed, effect, ElementRef, inject, input, OnDestroy, output, signal, untracked,
  viewChild, ViewEncapsulation,
} from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { PoolApiService } from '../../pool/pool-api.service';
import { renderMarkdown } from '../../pool/pool-util';
import type { TopicDetail } from '../../pool/pool.models';
import { AuthService } from '../../core/auth.service';
import { setItem } from '../../core/storage';
import { MSG } from '../messages';
import { errorMessage } from '../errors';
import { DocLang, fromPoolTopic, pickLang, preferredLang, stripLeadingHeading, topicFragment, topicHeading } from './topic';

/**
 * Quick preview of one topic (GET /api/pool/topics/{id}) as a modal popup. Opened through TopicPreviewService, so it
 * lives outside the page layouts: the styles are its own (tokens only) and work in the SIS and the pool screens.
 */
@Component({
  selector: 'app-topic-preview-dialog',
  encapsulation: ViewEncapsulation.None,
  template: `
    <div class="tp-backdrop" (click)="close()">
      <div #box class="tp-dialog" role="dialog" aria-modal="true" aria-labelledby="tp-title" tabindex="-1"
           (click)="$event.stopPropagation()" (keydown)="onKeydown($event)">
        <header class="tp-head">
          <div class="tp-heading">
            @if (topic(); as t) {
              <div class="tp-chapter">W{{ t.chapter_number }} · {{ t.chapter_title }}</div>
              <h2 id="tp-title">{{ heading() }}</h2>
            } @else {
              <h2 id="tp-title">{{ msg.loading }}</h2>
            }
          </div>
          <button #closeBtn type="button" class="tp-close" (click)="close()" [attr.aria-label]="msg.close" [title]="msg.close">✕</button>
        </header>

        @if (topic()) {
          <div class="tp-lang" role="group" [attr.aria-label]="msg.language">
            <button type="button" [class.on]="lang() === 'en'" [attr.aria-pressed]="lang() === 'en'" (click)="setLang('en')">English</button>
            <button type="button" [class.on]="lang() === 'tr'" [attr.aria-pressed]="lang() === 'tr'" (click)="setLang('tr')">Türkçe</button>
          </div>
        }

        <div class="tp-body">
          @if (error()) {
            <p class="tp-error" role="alert">{{ error() }}</p>
          } @else if (!topic()) {
            <p class="tp-muted">{{ msg.loading }}</p>
          } @else if (html()) {
            <div class="tp-doc" [innerHTML]="html()" (click)="onDocClick($event)"></div>
          } @else {
            <p class="tp-muted">{{ msg.empty }}</p>
          }
        </div>

        @if (topic()) {
          <footer class="tp-foot">
            @if (canNavigate()) {
              <button type="button" class="tp-btn" (click)="openInChapter()">{{ msg.openInChapter }}</button>
              <button type="button" class="tp-btn primary" (click)="practise()">{{ msg.practise }}</button>
            } @else {
              <span class="tp-muted tp-small">{{ msg.previewOnly }}</span>
            }
          </footer>
        }
      </div>
    </div>
  `,
  styles: [`
    .tp-backdrop {
      position: fixed; inset: 0; z-index: 1100; background: var(--c-overlay);
      display: flex; align-items: center; justify-content: center; padding: 16px;
    }
    .tp-dialog {
      display: flex; flex-direction: column; width: 100%; max-width: 720px; max-height: min(86vh, 900px);
      background: var(--c-surface); color: var(--c-text); border-radius: var(--radius-lg); box-shadow: var(--shadow-md);
      outline: none; line-height: 1.5; font-family: var(--font);
    }
    .tp-head { display: flex; align-items: flex-start; gap: 12px; padding: 16px 16px 8px; border-bottom: 1px solid var(--c-border); }
    .tp-heading { flex: 1; min-width: 0; }
    .tp-heading h2 { margin: 2px 0 4px; font-size: 1.15rem; line-height: 1.3; overflow-wrap: anywhere; }
    .tp-chapter { font-size: 0.82rem; color: var(--c-text-secondary); }
    .tp-close {
      flex: none; width: 40px; height: 40px; border-radius: 999px; border: 1px solid var(--c-border);
      background: var(--c-surface); color: var(--c-text); font-size: 1rem; cursor: pointer;
    }
    .tp-close:hover { border-color: var(--c-primary); }
    .tp-lang { display: flex; gap: 6px; padding: 10px 16px 0; }
    .tp-lang button {
      font: inherit; font-size: 0.85rem; padding: 5px 14px; border-radius: 999px; cursor: pointer;
      border: 1px solid var(--c-border); background: var(--c-surface); color: var(--c-text);
    }
    .tp-lang button.on { background: var(--c-text); color: var(--c-surface); border-color: var(--c-text); }
    .tp-body { flex: 1; min-height: 120px; overflow-y: auto; padding: 8px 16px 16px; overscroll-behavior: contain; -webkit-overflow-scrolling: touch; }
    .tp-doc { overflow-wrap: break-word; }
    .tp-doc img { max-width: 100%; }
    .tp-doc pre { background: var(--c-bg); border: 1px solid var(--c-border); padding: 10px; border-radius: var(--radius-md); overflow-x: auto; }
    .tp-doc code { font-family: var(--font-mono); font-size: 0.9em; }
    .tp-doc blockquote { margin: 10px 0; padding: 4px 12px; border-left: 4px solid var(--c-warning); background: var(--c-warning-light); border-radius: 6px; }
    .tp-doc table { display: block; overflow-x: auto; border-collapse: collapse; margin: 10px 0; }
    .tp-doc th, .tp-doc td { border-bottom: 1px solid var(--c-border); padding: 6px 8px; text-align: left; vertical-align: top; }
    .tp-doc h1, .tp-doc h2 { font-size: 1.1rem; }
    .tp-doc h3, .tp-doc h4 { font-size: 1rem; }
    .tp-foot { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; padding: 12px 16px; border-top: 1px solid var(--c-border); }
    .tp-btn {
      font: inherit; padding: 9px 16px; border-radius: var(--radius-md); cursor: pointer;
      border: 1px solid var(--c-border); background: var(--c-surface); color: var(--c-text);
    }
    .tp-btn:hover { border-color: var(--c-primary); }
    .tp-btn.primary { background: var(--c-primary); border-color: var(--c-primary); color: #fff; font-weight: 600; }
    .tp-btn.primary:hover { background: var(--c-primary-hover); }
    .tp-muted { color: var(--c-text-secondary); }
    .tp-small { font-size: 0.85rem; }
    .tp-error { color: var(--c-danger); font-weight: 600; }
    @media (max-width: 600px) {
      .tp-backdrop { padding: 0; align-items: stretch; }
      .tp-dialog { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .tp-foot { padding-bottom: max(12px, env(safe-area-inset-bottom)); }
      .tp-btn { flex: 1 1 100%; }
    }
  `],
})
export class TopicPreviewDialogComponent implements OnDestroy {
  private readonly api = inject(PoolApiService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly sanitizer = inject(DomSanitizer);
  readonly msg = MSG.topics;

  readonly topicId = input.required<number>();
  readonly closed = output<void>();

  private readonly box = viewChild<ElementRef<HTMLElement>>('box');
  private readonly closeBtn = viewChild<ElementRef<HTMLButtonElement>>('closeBtn');

  readonly topic = signal<TopicDetail | null>(null);
  readonly error = signal('');
  readonly lang = signal<DocLang>(preferredLang());

  private readonly previousFocus = typeof document !== 'undefined' ? document.activeElement as HTMLElement | null : null;
  private readonly previousOverflow = typeof document !== 'undefined' ? document.body.style.overflow : '';

  readonly heading = computed(() => {
    const t = this.topic();
    return t ? topicHeading(fromPoolTopic(t), this.lang()) : '';
  });

  /** DOMPurify-sanitized in renderMarkdown; heading ids are dropped so they never clash with the page's ids. */
  readonly html = computed(() => {
    const t = this.topic();
    if (!t) return '';
    const md = stripLeadingHeading(this.lang() === 'tr' ? t.content_tr : t.content_en, t.number);
    const html = renderMarkdown(md);
    if (!html.trim()) return '';
    const box = document.createElement('div');
    box.innerHTML = html;
    box.querySelectorAll('[id]').forEach(el => el.removeAttribute('id'));
    return this.sanitizer.bypassSecurityTrustHtml(box.innerHTML);
  });

  readonly canNavigate = computed(() => {
    const t = this.topic();
    if (!t) return false;
    if (this.auth.isAdmin()) return true;
    return this.auth.user()?.courses.some(c => c.id === t.course_id && c.poolEnabled) === true;
  });

  constructor() {
    if (typeof document !== 'undefined') document.body.style.overflow = 'hidden';
    effect(() => {
      const id = this.topicId();
      untracked(() => this.load(id));
    });
    afterNextRender(() => this.closeBtn()?.nativeElement.focus());
  }

  ngOnDestroy() {
    if (typeof document === 'undefined') return;
    document.body.style.overflow = this.previousOverflow;
    if (this.previousFocus?.isConnected) this.previousFocus.focus();
  }

  private async load(id: number) {
    this.topic.set(null);
    this.error.set('');
    try {
      const t = await this.api.topic(id);
      if (id !== this.topicId()) return;
      this.lang.set(pickLang(preferredLang(), t.content_en, t.content_tr));
      this.topic.set(t);
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  setLang(l: DocLang) {
    this.lang.set(l);
    setItem('docLang', l);
  }

  close() {
    this.closed.emit();
  }

  openInChapter() {
    const t = this.topic();
    if (!t) return;
    const lang = this.lang();
    this.close();
    this.router.navigate(['/courses', t.course_id, 'pool', 'chapters', t.chapter_id], {
      queryParams: { lang }, fragment: topicFragment(fromPoolTopic(t), lang),
    });
  }

  practise() {
    const t = this.topic();
    if (!t) return;
    this.close();
    this.router.navigate(['/courses', t.course_id, 'pool', 'chapters', t.chapter_id], { queryParams: { tab: 'questions', topic: t.id } });
  }

  /** Esc closes; Tab stays inside the dialog. */
  onKeydown(e: KeyboardEvent) {
    if (e.key === 'Escape') {
      e.preventDefault();
      this.close();
      return;
    }
    if (e.key !== 'Tab') return;
    const root = this.box()?.nativeElement;
    if (!root) return;
    const items = Array.from(root.querySelectorAll<HTMLElement>('button, a[href], input, select, textarea, [tabindex]:not([tabindex="-1"])'))
      .filter(el => !el.hasAttribute('disabled'));
    if (!items.length) return;
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;
    if (e.shiftKey && (active === first || active === root)) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && active === last) {
      e.preventDefault();
      first.focus();
    }
  }

  /** In-section "#…" links have no target inside the popup: keep them from changing the page URL. */
  onDocClick(e: MouseEvent) {
    const href = (e.target as HTMLElement | null)?.closest('a')?.getAttribute('href');
    if (href?.startsWith('#')) e.preventDefault();
  }
}
