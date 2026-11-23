import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../shared/errors';
import { textSegments } from '../shared/text-segments';
import { RichHtmlComponent } from '../shared/rich-html.component';
import { hasRichContent } from '../shared/rich-html';
import type { ExamDto, ExamResultDto, RosterEntryDto } from '../core/api.models';
import { formatExamDate, formatNumber, formatPercent } from './exam-detail.logic';
import { buildSheets, STATUS_TEXT, type StudentSheet } from './exam-sheets.logic';

/**
 * Printable per-student exam report (docs/11-EXAMS.md §4.5): for each student the questions (with group stems and
 * rich content), the given answer, the key, right/wrong and points; one page per student. Staff only.
 */
@Component({
  selector: 'app-exam-sheets',
  imports: [RouterLink, RichHtmlComponent],
  template: `
    <div class="container-wide sheets-page">
      <nav class="breadcrumb no-print" aria-label="Konum">
        <a routerLink="/t">Dersler</a>
        @if (exam(); as e) { › <a [routerLink]="['/t/courses', e.courseId]">{{ e.courseCode }}</a> › <a [routerLink]="['/t/exams', id()]">{{ e.title }}</a> › Öğrenci dökümleri }
      </nav>
      @if (error()) { <p class="form-error" role="alert">{{ error() }}</p> }

      @if (exam(); as e) {
        <div class="page-header no-print">
          <div>
            <h1>Öğrenci dökümleri</h1>
            <div class="text-sm text-secondary">{{ e.courseCode }} · {{ e.title }} · {{ results().length }} sonuç</div>
          </div>
          <div class="controls">
            <label class="text-sm">Öğrenci
              <select class="form-input" [value]="selected()" (change)="selected.set($any($event.target).value)">
                <option value="">Tümü ({{ results().length }})</option>
                @for (r of resultsSorted(); track r.studentNo) { <option [value]="r.studentNo" [selected]="selected() === r.studentNo">{{ r.studentNo }} — {{ nameOf(r) }}</option> }
              </select>
            </label>
            <label class="toggle text-sm"><input type="checkbox" [checked]="showOptions()" (change)="showOptions.set($any($event.target).checked)" /> Seçenek metinleri</label>
            <label class="toggle text-sm"><input type="checkbox" [checked]="showExplanation()" (change)="showExplanation.set($any($event.target).checked)" /> Cevap açıklamaları</label>
            <label class="toggle text-sm"><input type="checkbox" [checked]="onlyWrong()" (change)="onlyWrong.set($any($event.target).checked)" /> Yalnızca yanlış/boş</label>
            <button type="button" class="btn btn-primary btn-sm" (click)="print()" [disabled]="sheets().length === 0">Yazdır / PDF</button>
          </div>
        </div>
        @if (!e.published) {
          <p class="text-sm hint no-print">Sınav yayınlanmadı: döküm cevap anahtarını içerir, yalnızca öğretim üyesi görür. Öğrencilerin kendi dökümünü sistemde görmesi için sınavı yayınlayın.</p>
        }

        @if (sheets().length === 0) {
          <p class="text-secondary">Bu sınav için girilmiş sonuç yok.</p>
        }
        @for (s of sheets(); track s.studentNo) {
          <section class="sheet">
            <header class="sheet-head">
              <div>
                <div class="course">{{ e.courseCode }} — {{ e.courseName }}</div>
                <h2>{{ e.title }}@if (e.examDate) { <span class="text-secondary"> · {{ formatExamDate(e.examDate) }}</span> }</h2>
              </div>
              <div class="student">
                <div class="who"><strong class="text-mono">{{ s.studentNo }}</strong> {{ s.fullName }}@if (s.form) { <span class="text-secondary"> · Form {{ s.form }}</span> }</div>
                <div class="score"><strong>{{ fmt(s.total) }}</strong> / {{ fmt(s.max) }} @if (s.percent !== null) { <span class="text-secondary">({{ pct(s.percent) }})</span> }</div>
                @if (s.correct + s.wrong + s.blank > 0) {
                  <div class="counts text-sm"><span class="text-success">Doğru {{ s.correct }}</span> · <span class="text-danger">Yanlış {{ s.wrong }}</span> · Boş {{ s.blank }}</div>
                }
              </div>
            </header>

            @for (it of visibleItems(s); track it.question.id) {
              @if (it.groupHead; as g) {
                <div class="group">
                  <div class="g-top"><strong>{{ g.label }}.</strong> <span class="text-sm text-secondary">Soru grubu</span></div>
                  @if (g.html) { <app-rich-html [html]="g.html" /> }
                  @else { @for (seg of segments(g.text); track $index) {
                    @if (seg.code) { <pre class="q-code"><code>{{ seg.text }}</code></pre> } @else { <div class="q-text">{{ seg.text }}</div> }
                  } }
                </div>
              }
              <article class="item" [class.sub]="it.question.groupId != null" [attr.data-status]="it.status">
                <div class="i-top">
                  <strong class="label">{{ it.label }}.</strong>
                  <span class="badge" [class.badge-success]="it.status === 'right'" [class.badge-danger]="it.status === 'wrong'" [class.badge-warning]="it.status === 'partial'" [class.badge-info]="it.status === 'blank' || it.status === 'ungraded'">{{ statusText[it.status] }}</span>
                  <span class="grow"></span>
                  <span class="text-sm">Puan: <strong>{{ it.points === null ? '—' : fmt(it.points) }}</strong> / {{ fmt(it.question.maxPoints) }}</span>
                </div>
                @if (hasHtml(it.question.html)) { <app-rich-html [html]="it.question.html" /> }
                @else { @for (seg of segments(it.question.text); track $index) {
                  @if (seg.code) { <pre class="q-code"><code>{{ seg.text }}</code></pre> } @else { <div class="q-text">{{ seg.text }}</div> }
                } }
                @if (it.question.type === 'CHOICE') {
                  @if (showOptions() && it.options.some(o => o.text)) {
                    <ul class="opts">
                      @for (o of it.options; track o.letter) {
                        <li [class.key]="o.correct" [class.mine-wrong]="o.mine && !o.correct">
                          <span class="letter">{{ o.letter }}</span>
                          <span class="otext">{{ o.text || '' }}</span>
                          <span class="marks">@if (o.correct) { <span class="mark ok">✓ doğru</span> }@if (o.mine) { <span class="mark me">● verilen</span> }</span>
                        </li>
                      }
                    </ul>
                  }
                  <div class="answers">
                    <span class="ans" [class.right]="it.status === 'right'" [class.wrong]="it.status === 'wrong' || it.status === 'blank'">Verilen cevap: <strong>{{ it.answer || 'Boş' }}</strong></span>
                    @if (it.question.correctChoice) { <span class="ans key">Doğru cevap: <strong>{{ it.question.correctChoice }}</strong></span> }
                  </div>
                } @else if (it.question.type === 'OPEN') {
                  <div class="answers"><span class="ans">Cevap: <span class="q-text">{{ it.answer || '—' }}</span></span></div>
                }
                @if (showExplanation() && it.question.explanation?.trim()) {
                  <div class="expl"><strong>Açıklama:</strong> <div class="q-text">{{ it.question.explanation }}</div></div>
                }
              </article>
            }
          </section>
        }
      } @else if (!error()) {
        <p class="text-secondary">Yükleniyor…</p>
      }
    </div>
  `,
  styles: [`
    .controls { display: flex; flex-wrap: wrap; gap: 8px 14px; align-items: flex-end; }
    .controls label { display: flex; flex-direction: column; gap: 4px; }
    .controls .toggle { flex-direction: row; align-items: center; gap: 6px; min-height: 40px; }
    .controls .form-input { width: auto; max-width: 320px; }
    .hint { background: var(--c-warning-light); padding: var(--space-sm) var(--space-md); border-radius: var(--radius-md); }
    .sheet { background: var(--c-surface); border: 1px solid var(--c-border); border-radius: var(--radius-md); padding: var(--space-md); margin-bottom: var(--space-lg); break-after: page; }
    .sheet-head { display: flex; flex-wrap: wrap; justify-content: space-between; gap: var(--space-sm) var(--space-lg); border-bottom: 2px solid var(--c-text); padding-bottom: 8px; margin-bottom: 10px; }
    .sheet-head h2 { margin: 2px 0 0; font-size: 1.15rem; }
    .course { font-size: 0.9rem; color: var(--c-text-secondary); }
    .student { text-align: right; }
    .score { font-size: 1.3rem; }
    .group { margin: 12px 0 6px; padding: 8px 10px; border-left: 4px solid var(--c-primary); background: var(--c-primary-light); border-radius: 4px; break-inside: avoid; break-after: avoid; }
    .g-top { margin-bottom: 4px; }
    .item { border: 1px solid var(--c-border); border-left-width: 4px; border-radius: var(--radius-md); padding: 8px 10px; margin: 6px 0; break-inside: avoid; }
    .item.sub { margin-left: 16px; }
    .item[data-status='right'] { border-left-color: var(--c-success); }
    .item[data-status='wrong'], .item[data-status='blank'] { border-left-color: var(--c-danger); }
    .item[data-status='partial'] { border-left-color: var(--c-warning); }
    .i-top { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; margin-bottom: 4px; }
    .i-top .grow { flex: 1; }
    .badge { text-transform: none; }
    .q-text { white-space: pre-wrap; overflow-wrap: anywhere; margin: 2px 0; }
    .q-code { white-space: pre-wrap; overflow-wrap: anywhere; font-family: var(--font-mono); font-size: 0.85rem; background: var(--c-bg); border: 1px solid var(--c-border); border-radius: var(--radius-md); padding: 6px 8px; margin: 4px 0; }
    .opts { list-style: none; margin: 6px 0; padding: 0; display: flex; flex-direction: column; gap: 3px; }
    .opts li { display: flex; gap: 8px; align-items: baseline; padding: 3px 8px; border: 1px solid var(--c-border); border-radius: 4px; }
    .opts li.key { border-color: var(--c-success); background: var(--c-success-light); }
    .opts li.mine-wrong { border-color: var(--c-danger); background: var(--c-danger-light); }
    .letter { font-weight: 700; min-width: 1.3em; }
    .otext { flex: 1; white-space: pre-wrap; overflow-wrap: anywhere; }
    .marks { display: flex; gap: 6px; font-size: 0.8rem; white-space: nowrap; }
    .mark.ok { color: var(--c-success); font-weight: 600; }
    .mark.me { color: var(--c-primary); font-weight: 600; }
    .answers { display: flex; flex-wrap: wrap; gap: 6px 10px; margin-top: 6px; }
    .ans { padding: 2px 8px; border-radius: 4px; border: 1px solid var(--c-border); background: var(--c-bg); }
    .ans.right, .ans.key { border-color: var(--c-success); color: var(--c-success); background: var(--c-success-light); }
    .ans.wrong { border-color: var(--c-danger); color: var(--c-danger); background: var(--c-danger-light); }
    .expl { margin-top: 6px; padding: 6px 8px; border-left: 3px solid var(--c-primary); background: var(--c-primary-light); border-radius: 4px; font-size: 0.9rem; }
    @media print {
      .no-print { display: none !important; }
      .sheets-page { max-width: none; padding: 0; }
      .sheet { border: 0; padding: 0; margin: 0; box-shadow: none; }
      .sheet:last-child { break-after: auto; }
      .item, .group, .opts li, .ans, .expl { background: #fff !important; }
      .badge { border: 1px solid currentColor; }
      @page { margin: 14mm 12mm; }
    }
  `],
})
export class ExamSheetsComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly id = input.required<string>();
  /** `?student=` query param: print one student only. */
  readonly student = input<string>();

  readonly exam = signal<ExamDto | null>(null);
  readonly results = signal<ExamResultDto[]>([]);
  readonly roster = signal<RosterEntryDto[]>([]);
  readonly error = signal('');
  readonly selected = signal('');
  readonly showOptions = signal(true);
  readonly showExplanation = signal(false);
  readonly onlyWrong = signal(false);

  readonly resultsSorted = computed(() => [...this.results()].sort((a, b) => a.studentNo.localeCompare(b.studentNo, 'tr', { numeric: true })));
  readonly sheets = computed(() => {
    const e = this.exam();
    return e ? buildSheets(e, this.results(), this.roster(), this.selected() || null) : [];
  });
  readonly statusText = STATUS_TEXT;
  readonly segments = textSegments;
  readonly hasHtml = hasRichContent;
  readonly formatExamDate = formatExamDate;
  readonly fmt = formatNumber;
  readonly pct = formatPercent;

  async ngOnInit() {
    this.selected.set(this.student()?.trim() ?? '');
    try {
      const exam = await this.api.getExam(this.id());
      this.exam.set(exam);
      const [roster, results] = await Promise.all([this.api.getRoster(exam.courseId).catch(() => [] as RosterEntryDto[]), this.api.getExamResults(this.id())]);
      this.roster.set(roster);
      this.results.set(results);
    } catch (e) { this.error.set(errorMessage(e)); }
  }

  nameOf(r: ExamResultDto): string {
    return this.roster().find(x => x.studentNo === r.studentNo)?.fullName || r.fullName;
  }

  /** Items to print; with "only wrong" the group header moves to the first printed member. */
  visibleItems(s: StudentSheet) {
    if (!this.onlyWrong()) return s.items;
    const out = [];
    let pendingHead = null as StudentSheet['items'][number]['groupHead'];
    for (const it of s.items) {
      if (it.groupHead) pendingHead = it.groupHead;
      if (it.question.groupId == null) pendingHead = null;
      if (it.status === 'right' || it.status === 'ungraded') continue;
      out.push(pendingHead && it.question.groupId != null ? { ...it, groupHead: pendingHead } : it);
      pendingHead = null;
    }
    return out;
  }

  print() { window.print(); }
}
