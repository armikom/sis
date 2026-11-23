import {
  ChangeDetectionStrategy, Component, ElementRef, afterNextRender, effect, inject, Injector, input, output, signal, untracked,
  viewChild,
} from '@angular/core';
import { sanitizeHtml } from './rich-html';

/** Inline images above this size are refused: they live inside the question row. */
const MAX_IMAGE_BYTES = 400_000;

/**
 * Small rich-content editor for questions and groups: a contenteditable area (paste from Word, the web or an editor
 * keeps formatting and inline images) plus an "HTML source" view. Everything that leaves the editor is sanitized.
 */
@Component({
  selector: 'app-html-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="editor" [class.source]="sourceMode()">
      <div class="toolbar" role="toolbar" [attr.aria-label]="label() + ' biçim araçları'">
        @if (!sourceMode()) {
          <button type="button" class="tb" (mousedown)="$event.preventDefault()" (click)="cmd('bold')" title="Kalın"><b>K</b></button>
          <button type="button" class="tb" (mousedown)="$event.preventDefault()" (click)="cmd('italic')" title="İtalik"><i>İ</i></button>
          <button type="button" class="tb" (mousedown)="$event.preventDefault()" (click)="cmd('underline')" title="Altı çizili"><u>A</u></button>
          <button type="button" class="tb mono" (mousedown)="$event.preventDefault()" (click)="code()" title="Kod">&lt;/&gt;</button>
          <button type="button" class="tb" (mousedown)="$event.preventDefault()" (click)="cmd('insertUnorderedList')" title="Liste">•≡</button>
          <button type="button" class="tb" (mousedown)="$event.preventDefault()" (click)="cmd('insertOrderedList')" title="Numaralı liste">1≡</button>
          <button type="button" class="tb" (mousedown)="$event.preventDefault()" (click)="cmd('removeFormat')" title="Biçimi temizle">Tx</button>
          <label class="tb file" title="Resim ekle (en fazla 400 KB)">🖼<input type="file" accept="image/*" (change)="image($event)" /></label>
        }
        <span class="grow"></span>
        <button type="button" class="tb toggle" (click)="toggleSource()" [attr.aria-pressed]="sourceMode()">{{ sourceMode() ? 'Görsel' : 'HTML' }}</button>
      </div>
      @if (sourceMode()) {
        <textarea class="form-input src" rows="8" spellcheck="false" [attr.aria-label]="label() + ' HTML kaynağı'"
          [value]="value()" (input)="onSource($any($event.target).value)"></textarea>
      } @else {
        <div #area class="area form-input" contenteditable="true" role="textbox" aria-multiline="true" [attr.aria-label]="label()"
          [attr.data-placeholder]="placeholder()" (input)="onInput()" (blur)="onInput()"></div>
      }
      @if (notice()) { <p class="text-sm text-danger notice">{{ notice() }}</p> }
    </div>
  `,
  styles: [`
    .editor { border: 1px solid var(--c-border); border-radius: var(--radius-md); background: var(--c-surface); }
    .toolbar { display: flex; flex-wrap: wrap; gap: 2px; padding: 4px; border-bottom: 1px solid var(--c-border); }
    .tb { min-width: 30px; height: 28px; padding: 0 6px; border: 1px solid transparent; border-radius: 4px; background: transparent; cursor: pointer; font-size: 0.85rem; }
    .tb:hover { background: var(--c-bg); border-color: var(--c-border); }
    .tb.mono { font-family: var(--font-mono); font-size: 0.75rem; }
    .tb.file { display: inline-flex; align-items: center; justify-content: center; }
    .tb.file input { display: none; }
    .toggle { font-size: 0.75rem; text-transform: uppercase; color: var(--c-text-secondary); }
    .grow { flex: 1; }
    .area { min-height: 6em; border: 0; border-radius: 0; outline: none; white-space: pre-wrap; overflow-wrap: anywhere; }
    .area:focus { box-shadow: none; }
    .area:empty::before { content: attr(data-placeholder); color: var(--c-text-secondary); }
    .area img { max-width: 100%; height: auto; }
    .area table { border-collapse: collapse; }
    .area :is(td, th) { border: 1px solid var(--c-border); padding: 2px 6px; }
    .area pre, .area code { font-family: var(--font-mono); font-size: 0.9em; }
    .src { border: 0; border-radius: 0; font-family: var(--font-mono); font-size: 0.85rem; width: 100%; }
    .notice { margin: 4px 8px; }
  `],
})
export class HtmlEditorComponent {
  private readonly injector = inject(Injector);
  private readonly area = viewChild<ElementRef<HTMLDivElement>>('area');

  /** Sanitized HTML; '' = empty. */
  readonly value = input<string>('');
  readonly label = input<string>('İçerik');
  readonly placeholder = input<string>('İçeriği yazın ya da yapıştırın (biçim ve resimler korunur)');
  readonly valueChange = output<string>();

  readonly sourceMode = signal(false);
  readonly notice = signal('');
  /** The last value this editor emitted; an external change to `value` is written into the area only when it differs. */
  private lastEmitted: string | null = null;

  constructor() {
    effect(() => {
      const v = this.value();
      const el = this.area()?.nativeElement;
      if (!el) return;
      untracked(() => {
        if (v !== this.lastEmitted && el.innerHTML !== v) el.innerHTML = sanitizeHtml(v);
      });
    });
  }

  onInput() {
    const el = this.area()?.nativeElement;
    if (!el) return;
    this.emit(sanitizeHtml(el.innerHTML));
  }

  onSource(raw: string) {
    this.emit(sanitizeHtml(raw));
  }

  private emit(html: string) {
    const v = isBlankHtml(html) ? '' : html;
    this.lastEmitted = v;
    this.valueChange.emit(v);
  }

  toggleSource() {
    const el = this.area()?.nativeElement;
    if (el) this.emit(sanitizeHtml(el.innerHTML));   // leaving the visual view: keep what is typed
    this.sourceMode.set(!this.sourceMode());
    if (this.sourceMode()) return;
    afterNextRender(() => {
      const a = this.area()?.nativeElement;
      if (a) a.innerHTML = sanitizeHtml(this.value());
    }, { injector: this.injector });
  }

  cmd(name: string) {
    this.area()?.nativeElement.focus();
    document.execCommand(name);
    this.onInput();
  }

  /** Wraps the selection in <code>; without a selection inserts an empty code element. */
  code() {
    const el = this.area()?.nativeElement;
    if (!el) return;
    el.focus();
    const sel = window.getSelection();
    const text = sel && sel.rangeCount ? sel.toString() : '';
    document.execCommand('insertHTML', false, `<code>${escapeHtml(text || 'kod')}</code>`);
    this.onInput();
  }

  image(e: Event) {
    const inp = e.target as HTMLInputElement;
    const file = inp.files?.[0];
    inp.value = '';
    if (!file) return;
    if (file.size > MAX_IMAGE_BYTES) { this.notice.set(`Resim çok büyük (${Math.round(file.size / 1024)} KB); en fazla 400 KB.`); return; }
    this.notice.set('');
    const reader = new FileReader();
    reader.onload = () => {
      const el = this.area()?.nativeElement;
      if (!el) return;
      el.focus();
      document.execCommand('insertHTML', false, `<img src="${reader.result}" alt="">`);
      this.onInput();
    };
    reader.readAsDataURL(file);
  }
}

function isBlankHtml(html: string): boolean {
  const box = document.createElement('div');
  box.innerHTML = html;
  return !box.textContent?.trim() && !box.querySelector('img, table, hr');
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
