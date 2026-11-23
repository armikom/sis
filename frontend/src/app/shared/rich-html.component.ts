import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';
import { sanitizeHtml } from './rich-html';

/** Renders staff-written HTML (question / group content) after DOMPurify. */
@Component({
  selector: 'app-rich-html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="rich" [innerHTML]="safe()"></div>`,
  styles: [`
    :host { display: block; }
    .rich { overflow-wrap: anywhere; line-height: 1.5; }
    .rich :is(p, ul, ol, pre, table, blockquote) { margin: 0.35em 0; }
    .rich img { max-width: 100%; height: auto; }
    .rich table { border-collapse: collapse; }
    .rich :is(td, th) { border: 1px solid var(--c-border, #ccc); padding: 2px 6px; }
    .rich pre, .rich code { font-family: var(--font-mono, monospace); font-size: 0.9em; }
    .rich pre { white-space: pre-wrap; background: var(--c-bg, #f6f6f6); border: 1px solid var(--c-border, #ddd); border-radius: 6px; padding: 6px 8px; }
  `],
})
export class RichHtmlComponent {
  private readonly sanitizer = inject(DomSanitizer);
  readonly html = input<string | null | undefined>('');
  /** DOMPurify first; Angular's own sanitizer is bypassed only for that cleaned string. */
  readonly safe = computed(() => this.sanitizer.bypassSecurityTrustHtml(sanitizeHtml(this.html())));
}
