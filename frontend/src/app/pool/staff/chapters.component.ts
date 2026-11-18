import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PoolApiService } from '../pool-api.service';
import { PoolContext } from '../pool-context.service';
import { PM } from '../pool-messages';
import type { ChapterDetail, ChapterTopic } from '../pool.models';
import { errorMessage } from '../../shared/errors';

/** Staff: chapter texts (Markdown, EN/TR), open/closed, plus adding and deleting chapters. */
@Component({
  selector: 'app-pool-chapters-admin',
  imports: [FormsModule],
  template: `
    <h1>{{ pm.title }}</h1>
    <p class="muted small">{{ pm.intro }}</p>
    <p class="muted small">{{ pm.topicsHint }}</p>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (loaded() && !chapters().length) { <p class="muted">{{ pm.empty }}</p> }

    @for (c of chapters(); track c.id) {
      <details class="card">
        <summary>
          <b>{{ c.title }}</b>
          <span class="small muted"> · {{ pm.chars(c.doc_en.length, c.doc_tr.length) }} · {{ c.open ? pm.open : pm.closed }} · {{ pm.topics(c.topics?.length ?? 0) }}</span>
        </summary>
        <div class="row">
          <div class="num-field">
            <label [for]="'number-' + c.id">{{ pm.number }}</label>
            <input [id]="'number-' + c.id" type="number" min="1" max="999" [(ngModel)]="c.number" />
          </div>
          <div class="grow">
            <label [for]="'title-' + c.id">{{ pm.titleLabel }}</label>
            <input [id]="'title-' + c.id" type="text" [(ngModel)]="c.title" maxlength="300" />
          </div>
        </div>
        <label class="inline mt-sm"><input type="checkbox" [(ngModel)]="c.open" /> {{ pm.canSend }}</label>
        <label [for]="'en-' + c.id">{{ pm.en }} <input type="file" accept=".md,.txt,text/markdown,text/plain" (change)="readFile($event, c, 'doc_en')" /></label>
        <textarea [id]="'en-' + c.id" [(ngModel)]="c.doc_en" rows="8"></textarea>
        <label [for]="'tr-' + c.id">{{ pm.tr }} <input type="file" accept=".md,.txt,text/markdown,text/plain" (change)="readFile($event, c, 'doc_tr')" /></label>
        <textarea [id]="'tr-' + c.id" [(ngModel)]="c.doc_tr" rows="8"></textarea>
        <h3>{{ pm.topics(c.topics?.length ?? 0) }}</h3>
        @if (c.topics?.length) {
          <div class="scroll topic-table">
            <table>
              <thead><tr><th>{{ pm.topicNo }}</th><th>{{ pm.topicEn }}</th><th>{{ pm.topicTr }}</th></tr></thead>
              <tbody>
                @for (t of c.topics; track t.id) {
                  <tr><td>{{ t.number }}</td><td>{{ t.title_en || '—' }}</td><td>{{ t.title_tr || '—' }}</td></tr>
                }
              </tbody>
            </table>
          </div>
        } @else {
          <p class="small muted">{{ pm.noTopics }}</p>
        }
        <p class="row">
          <button type="button" class="small" [disabled]="syncing() === c.id" (click)="sync(c)">{{ pm.sync }}</button>
          @if (synced() === c.id) { <span class="small muted">{{ pm.synced(c.topics?.length ?? 0) }}</span> }
        </p>
        <p class="row">
          <button type="button" class="primary" (click)="save(c)">{{ pm.save }}</button>
          @if (saved() === c.id) { <span class="small muted">{{ pm.saved }}</span> }
          <span class="grow"></span>
          <button type="button" class="danger" (click)="remove(c)">{{ pm.delete }}</button>
        </p>
      </details>
    }

    <h2>{{ pm.newTitle }}</h2>
    <form class="card" (ngSubmit)="create()" ngNativeValidate>
      <div class="row">
        <div class="num-field">
          <label for="new-number">{{ pm.number }}</label>
          <input id="new-number" name="number" type="number" min="1" max="999" [(ngModel)]="draft.number" required />
        </div>
        <div class="grow">
          <label for="new-title">{{ pm.titleLabel }}</label>
          <input id="new-title" name="title" type="text" [(ngModel)]="draft.title" maxlength="300" required />
        </div>
      </div>
      <label class="inline mt-sm"><input type="checkbox" name="open" [(ngModel)]="draft.open" /> {{ pm.canSend }}</label>
      <p><button type="submit" class="primary" [disabled]="busy()">{{ pm.create }}</button></p>
    </form>
  `,
})
export class ChaptersAdminComponent implements OnInit {
  private readonly api = inject(PoolApiService);
  readonly ctx = inject(PoolContext);
  readonly pm = PM.chapters;
  readonly chapters = signal<ChapterDetail[]>([]);
  readonly saved = signal<number | null>(null);
  readonly loaded = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly syncing = signal<number | null>(null);
  readonly synced = signal<number | null>(null);
  draft = { number: 1, title: '', open: true };

  async ngOnInit() {
    await this.load();
  }

  private async load() {
    try {
      const list = await this.api.chapters(this.ctx.courseId());
      const full = await Promise.all(list.map(c => this.api.chapter(c.id)));
      this.chapters.set(full.map(c => ({ ...c, doc_en: c.doc_en ?? '', doc_tr: c.doc_tr ?? '' })));
      this.draft = { number: Math.max(0, ...full.map(c => c.number)) + 1, title: '', open: true };
    } catch (e) {
      this.error.set(errorMessage(e));
    }
    this.loaded.set(true);
  }

  async readFile(event: Event, chapter: ChapterDetail, field: 'doc_en' | 'doc_tr') {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;
    chapter[field] = await file.text();
    this.chapters.update(l => [...l]);
  }

  async save(c: ChapterDetail) {
    this.error.set('');
    try {
      await this.api.updateChapter(this.ctx.courseId(), c.id, { number: Number(c.number), title: c.title, doc_en: c.doc_en, doc_tr: c.doc_tr, open: c.open });
      this.saved.set(c.id);
      // The server may re-derive the topics from the new text: show what it has now.
      const fresh = await this.api.chapter(c.id).catch(() => null);
      if (fresh?.topics) this.setTopics(c.id, fresh.topics);
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  /** Re-derives the chapter's topics from its numbered headings (as saved on the server). */
  async sync(c: ChapterDetail) {
    this.error.set('');
    this.syncing.set(c.id);
    try {
      const topics = await this.api.syncTopics(this.ctx.courseId(), c.id);
      this.setTopics(c.id, Array.isArray(topics) ? topics : []);
      this.synced.set(c.id);
    } catch (e) {
      this.error.set(errorMessage(e));
    }
    this.syncing.set(null);
  }

  private setTopics(chapterId: number, topics: ChapterTopic[]) {
    // In place, so unsaved edits of the other fields stay.
    this.chapters.update(list => list.map(x => (x.id === chapterId ? Object.assign(x, { topics }) : x)));
  }

  async create() {
    this.busy.set(true);
    this.error.set('');
    try {
      await this.api.createChapter(this.ctx.courseId(), { number: Number(this.draft.number), title: this.draft.title.trim(), open: this.draft.open });
      await this.load();
    } catch (e) {
      this.error.set(errorMessage(e));
    }
    this.busy.set(false);
  }

  async remove(c: ChapterDetail) {
    if (!confirm(this.pm.deleteConfirm(c.title))) return;
    this.error.set('');
    try {
      await this.api.deleteChapter(this.ctx.courseId(), c.id);
      this.chapters.update(l => l.filter(x => x.id !== c.id));
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }
}
