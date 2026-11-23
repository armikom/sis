import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../shared/errors';
import { ExamExportDialogComponent } from './exam-export-dialog.component';
import { TopicChipsComponent } from '../shared/topics/topic-chips.component';
import { apiTopics } from '../shared/topics/topic';
import { textSegments } from '../shared/text-segments';
import { RichHtmlComponent } from '../shared/rich-html.component';
import { hasRichContent, richSummary } from '../shared/rich-html';
import { optionLabels, questionLabel } from '../shared/question-labels';
import type { ExamAnalysisDto, ExamDto, ExamGroupDto, ExamResultDto, RosterEntryDto } from '../core/api.models';
import {
  barWidth, choiceBars, filterRows, formatExamDate, formatNumber, formatPercent, mergeRows, QUESTION_TYPE_LABELS,
  rateLevel, ResultFilter, ResultRow, sortByRateAsc, STATUS_LABELS, statusCounts,
} from './exam-detail.logic';

@Component({
  selector: 'app-exam-detail',
  imports: [RouterLink, ExamExportDialogComponent, TopicChipsComponent, RichHtmlComponent],
  template: `
    <div class="container-wide">
      <nav class="breadcrumb" aria-label="Konum">
        <a routerLink="/t">Dersler</a>
        @if (exam(); as e) {
          › <a [routerLink]="['/t/courses', e.courseId]">{{ e.courseCode }}</a> › {{ e.title }}
        }
      </nav>
      @if (error()) { <p class="form-error" role="alert">{{ error() }}</p> }

      @if (exam(); as e) {
        <div class="page-header">
          <div>
            <h1>{{ e.title }}</h1>
            <div class="text-sm text-secondary">
              {{ formatExamDate(e.examDate) }} · {{ e.questions.length }} soru · toplam {{ fmt(e.totalPoints) }} puan
            </div>
            <div class="publish flex-gap">
              <span class="badge" [class.badge-success]="e.published" [class.badge-warning]="!e.published" role="status">
                {{ e.published ? 'Öğrencilere açık' : 'Yayınlanmadı' }}
              </span>
              <button type="button" class="btn btn-sm" [class.btn-primary]="!e.published" [class.btn-outline]="e.published" [disabled]="publishing()" (click)="togglePublish(e)">
                {{ e.published ? 'Yayından kaldır' : 'Öğrencilere yayınla' }}
              </button>
              @if (e.published) {
                <button type="button" class="btn btn-outline btn-sm" (click)="openNotify()" title="Sonuçların açıklandığını öğrencilere e-postayla bildir">✉ Öğrencilere e-posta gönder</button>
                @if (e.notifiedAt) { <span class="text-sm text-secondary">Son gönderim: {{ formatWhen(e.notifiedAt) }} · {{ e.notifiedCount ?? 0 }} öğrenci</span> }
              }
            </div>
            @if (notifyOpen()) {
              <div class="card notify" role="dialog" aria-labelledby="notify-title">
                <h2 id="notify-title" class="panel-title">Öğrencilere e-posta</h2>
                <p class="text-sm text-secondary">Sonucu girilmiş {{ notifyCounts().results }} öğrenciden {{ notifyCounts().withEmail }} tanesinin e-posta adresi var; e-postası olmayanlara gönderilmez.
                  Her öğrenciye puanı ve değerlendirme sayfasının bağlantısı gider.</p>
                <label class="field">Not (isteğe bağlı)
                  <textarea class="form-input" rows="3" maxlength="4000" [value]="notifyMessage()" (input)="notifyMessage.set($any($event.target).value)" placeholder="Örn. Sınav kağıtlarınızı ofis saatinde görebilirsiniz."></textarea>
                </label>
                <label class="toggle"><input type="checkbox" [checked]="notifyReport()" (change)="notifyReport.set($any($event.target).checked)" /> Sınav dökümünü e-postaya ekle (her soru: verilen cevap, doğru cevap, durum, puan)</label>
                @if (notifyResult(); as r) { <p class="text-sm" role="status">{{ r }}</p> }
                <div class="flex-gap">
                  <button type="button" class="btn btn-primary btn-sm" (click)="sendNotify(e)" [disabled]="notifying() || notifyCounts().withEmail === 0">{{ notifying() ? 'Gönderiliyor…' : 'Gönder (' + notifyCounts().withEmail + ')' }}</button>
                  <button type="button" class="btn btn-outline btn-sm" (click)="notifyOpen.set(false)">Kapat</button>
                </div>
              </div>
            }
          </div>
          <div class="flex-gap actions">
            <a class="btn btn-primary btn-sm" [routerLink]="['/t/exams', id(), 'entry']">Cevap gir</a>
            <a class="btn btn-outline btn-sm" [routerLink]="['/t/exams', id(), 'edit']">Düzenle</a>
            <a class="btn btn-outline btn-sm" [routerLink]="['/t/exams', id(), 'sheets']" title="Her öğrenci için soru, verilen cevap, doğru cevap ve puanları içeren yazdırılabilir döküm">Öğrenci dökümleri</a>
            <button type="button" class="btn btn-outline btn-sm" (click)="showExport.set(true)">Not dışa aktar</button>
            <a class="btn btn-outline btn-sm" [routerLink]="['/t/courses', e.courseId, 'learning']">Öğrenme durumu</a>
          </div>
        </div>

        <div class="tabs" role="tablist">
          <button role="tab" class="tab" [class.active]="tab() === 'results'" [attr.aria-selected]="tab() === 'results'" (click)="tab.set('results')">Sonuçlar</button>
          <button role="tab" class="tab" [class.active]="tab() === 'analysis'" [attr.aria-selected]="tab() === 'analysis'" (click)="tab.set('analysis')">Analiz</button>
          <button role="tab" class="tab" [class.active]="tab() === 'questions'" [attr.aria-selected]="tab() === 'questions'" (click)="tab.set('questions')">Sorular</button>
        </div>

        @if (tab() === 'results') {
          <p class="text-sm"><strong>{{ entered() }}</strong> / {{ rows().length }} öğrenci için sonuç girildi.</p>
          <div class="flex-gap controls">
            <input class="form-input search-input" type="search" placeholder="Numara veya ad ara" aria-label="Öğrenci ara"
              [value]="query()" (input)="query.set($any($event.target).value)" />
            <div class="chips" role="group" aria-label="Durum filtresi">
              @for (f of filters; track f.key) {
                <button class="chip" [class.active]="filter() === f.key" [attr.aria-pressed]="filter() === f.key" (click)="filter.set(f.key)">
                  {{ f.label }} ({{ counts()[f.key] }})
                </button>
              }
            </div>
          </div>
          @if (rows().length === 0) {
            <p class="text-secondary">Henüz öğrenci listesi veya sonuç yok.</p>
          } @else {
            <div class="table-wrap">
              <table>
                <thead><tr><th>No</th><th>Ad Soyad</th><th class="num">Toplam</th><th class="num">Yüzde</th><th>Durum</th><th></th></tr></thead>
                <tbody>
                  @for (r of visibleRows(); track r.studentNo) {
                    <tr>
                      <td class="text-mono">{{ r.studentNo }}</td>
                      <td>{{ r.fullName }}</td>
                      <td class="num">{{ r.totalPoints === null ? '—' : fmt(r.totalPoints) }}</td>
                      <td class="num">{{ pct(r.percent) }}</td>
                      <td>
                        <span class="badge" [class.badge-info]="r.status === 'MISSING'" [class.badge-warning]="r.status === 'PARTIAL'" [class.badge-success]="r.status === 'COMPLETE'">{{ statusLabels[r.status] }}</span>
                      </td>
                      <td class="row-actions">
                        <a class="btn btn-outline btn-sm" [routerLink]="['/t/exams', id(), 'entry']" [queryParams]="{ student: r.studentNo }">{{ r.status === 'MISSING' ? 'Gir' : 'Düzenle' }}</a>
                        @if (r.status !== 'MISSING') {
                          <a class="btn btn-outline btn-sm" [routerLink]="['/t/exams', id(), 'sheets']" [queryParams]="{ student: r.studentNo }" title="Öğrencinin sınav dökümü">Döküm</a>
                          <button class="btn-icon text-danger" (click)="deleteResult(r)" [attr.aria-label]="r.studentNo + ' sonucunu sil'" title="Sonucu sil">✕</button>
                        }
                      </td>
                    </tr>
                  } @empty {
                    <tr><td colspan="6" class="text-secondary">Eşleşen öğrenci yok.</td></tr>
                  }
                </tbody>
              </table>
            </div>
          }
        }

        @if (tab() === 'questions') {
          @if (e.questions.length === 0) {
            <p class="text-secondary">Bu sınavda soru yok.</p>
          } @else {
            @if (!anyTopics()) {
              <p class="text-secondary hint">Sorulara henüz konu bağlanmadı. Konular sınav düzenleme ekranında seçilebilir; havuzdan gelen sorular havuz sorusunun konularını da taşır.</p>
            }
            <ol class="q-list">
              @for (q of questionRows(); track q.id) {
                @if (q.groupHead; as g) {
                  <li class="q-group">
                    <div class="q-meta"><strong>{{ g.label }}.</strong> <span class="text-sm text-secondary">Soru grubu · {{ g.questionIds.length }} alt soru</span></div>
                    @if (g.html) { <app-rich-html [html]="g.html" /> }
                    @else { @for (seg of segments(g.text); track $index) {
                      @if (seg.code) { <pre class="q-code"><code>{{ seg.text }}</code></pre> } @else { <div class="q-text">{{ seg.text }}</div> }
                    } }
                  </li>
                }
                <li class="q-item" [class.q-sub]="q.groupId != null">
                  <div class="q-meta">
                    <strong>{{ q.printed }}.</strong>
                    <span class="text-sm text-secondary">{{ typeLabels[q.type] ?? q.type }}@if (q.type === 'CHOICE') { ({{ optionLabels(q).join('/') }}) } · {{ fmt(q.maxPoints) }} puan</span>
                    @if (q.correctChoice) { <span class="badge badge-success">Anahtar: {{ q.correctChoice }}</span> }
                    @if (q.poolQuestionId) { <span class="text-sm text-secondary">Havuz #{{ q.poolQuestionId }}</span> }
                  </div>
                  @if (q.hasContent) {
                    <details class="q-content">
                      <summary>{{ q.summary || 'Soru içeriği' }}</summary>
                      @if (q.hasHtml) { <app-rich-html [html]="q.html" /> }
                      @else { @for (seg of segments(q.text); track $index) {
                        @if (seg.code) { <pre class="q-code"><code>{{ seg.text }}</code></pre> } @else { <div class="q-text">{{ seg.text }}</div> }
                      } }
                      @if (q.options?.length) {
                        <ul class="q-options">
                          @for (o of q.options; track o.letter) {
                            <li [class.key]="o.letter === q.correctChoice">
                              <span class="q-letter">{{ o.letter }}</span>
                              <div class="q-opt-body">
                                <div class="q-text">{{ o.text || '—' }} @if (o.letter === q.correctChoice) { <span class="badge badge-success">doğru</span> }</div>
                                @if (o.rationale) { <div class="q-why">{{ o.rationale }}</div> }
                              </div>
                            </li>
                          }
                        </ul>
                      }
                      @if (q.explanation) { <div class="q-expl"><strong>Cevap açıklaması:</strong> <div class="q-text">{{ q.explanation }}</div></div> }
                    </details>
                  }
                  <app-topic-chips [topics]="q.topicList" />
                </li>
              }
            </ol>
          }
        }

        @if (tab() === 'analysis') {
          @if (analysisError()) { <p class="form-error" role="alert">{{ analysisError() }}</p> }
          @if (analysis(); as a) {
            @if (a.resultCount === 0) {
              <div class="card empty">
                <strong>Henüz analiz edilecek sonuç yok.</strong>
                <p class="text-secondary">Öğrenci cevaplarını girdikten sonra soru, konu ve öğrenci bazında analiz burada görünür.</p>
                <a class="btn btn-primary btn-sm" [routerLink]="['/t/exams', id(), 'entry']">Cevap gir</a>
              </div>
            } @else {
              <div class="tiles">
                @for (t of tiles(); track t.label) {
                  <div class="card tile">
                    <div class="text-sm text-secondary">{{ t.label }}</div>
                    <div class="tile-value">{{ fmt(t.value) }}</div>
                    <div class="text-sm text-secondary">{{ pct(t.value === null || a.totalPoints <= 0 ? null : t.value / a.totalPoints) }}</div>
                  </div>
                }
                <div class="card tile">
                  <div class="text-sm text-secondary">Sonuç sayısı</div>
                  <div class="tile-value">{{ a.resultCount }}</div>
                  <div class="text-sm text-secondary">toplam {{ fmt(a.totalPoints) }} puan</div>
                </div>
              </div>

              <h2>Sorular</h2>
              <div class="table-wrap">
                <table>
                  <thead><tr><th>No</th><th>Tür</th><th>Konu</th><th class="num">Puan</th><th class="num">Ortalama</th><th>Başarı</th><th>Seçenek dağılımı</th></tr></thead>
                  <tbody>
                    @for (q of a.questions; track q.questionId) {
                      <tr>
                        <td>{{ q.label ?? q.position }}</td>
                        <td>{{ typeLabels[q.type] ?? q.type }}</td>
                        <td>{{ q.topic ?? '—' }}</td>
                        <td class="num">{{ fmt(q.maxPoints) }}</td>
                        <td class="num">{{ fmt(q.averagePoints) }}</td>
                        <td>
                          <div class="rate" [attr.data-level]="level(q.successRate)">
                            <div class="bar"><div class="fill" [style.width.%]="bw(q.successRate)"></div></div>
                            <span class="rate-text">{{ pct(q.successRate) }}</span>
                          </div>
                        </td>
                        <td>
                          @if (q.choiceCounts) {
                            <div class="choices">
                              @for (c of choicesFor(q.questionId, q.choiceCounts); track c.key) {
                                <div class="choice" [class.correct]="c.correct">
                                  <span class="choice-label">{{ c.label }}@if (c.correct) { <span class="sr-only"> (doğru cevap)</span> }</span>
                                  <div class="bar small"><div class="fill neutral" [style.width.%]="c.width"></div></div>
                                  <span class="choice-count">{{ c.count }}@if (c.correct) { ✓ }</span>
                                </div>
                              }
                            </div>
                          } @else { <span class="text-secondary">—</span> }
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>

              <h2>Konular</h2>
              @if (a.topics.length === 0) {
                <p class="text-secondary hint">Konu analizi için sorulara konu (kazanım) ekleyin: sınavı düzenleyip her sorunun “Konu” alanını doldurun.</p>
              } @else {
                <div class="table-wrap">
                  <table>
                    <thead><tr><th>Konu</th><th>Başarı</th><th class="num">Soru</th><th class="num">Zayıf öğrenci</th></tr></thead>
                    <tbody>
                      @for (t of topicsSorted(); track t.topic) {
                        <tr [class.weak-row]="level(t.successRate) === 'danger'">
                          <td>{{ t.topic }}@if (level(t.successRate) === 'danger') { <span class="badge badge-danger weak-badge">Zayıf</span> }</td>
                          <td>
                            <div class="rate" [attr.data-level]="level(t.successRate)">
                              <div class="bar"><div class="fill" [style.width.%]="bw(t.successRate)"></div></div>
                              <span class="rate-text">{{ pct(t.successRate) }}</span>
                            </div>
                          </td>
                          <td class="num">{{ t.questionCount }}</td>
                          <td class="num">{{ t.weakStudentCount }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }

              <h2>Öğrenciler</h2>
              <div class="table-wrap">
                <table>
                  <thead><tr><th>No</th><th>Ad Soyad</th><th class="num">Toplam</th><th class="num">Yüzde</th><th>Zayıf konular</th><th></th></tr></thead>
                  <tbody>
                    @for (s of studentsSorted(); track s.studentNo) {
                      <tr>
                        <td class="text-mono">{{ s.studentNo }}</td>
                        <td>{{ s.fullName }}</td>
                        <td class="num">{{ fmt(s.totalPoints) }}</td>
                        <td class="num"><span class="rate-text" [attr.data-level]="level(s.percent)">{{ pct(s.percent) }}</span></td>
                        <td>
                          @for (t of s.weakTopics; track t) { <span class="badge badge-danger weak-badge">{{ t }}</span> }
                          @empty { <span class="text-secondary">—</span> }
                        </td>
                        <td><a [routerLink]="['/t/courses', e.courseId, 'learning']" [queryParams]="{ student: s.studentNo }">Öğrenme durumu</a></td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          } @else if (!analysisError()) {
            <p class="text-secondary">Yükleniyor…</p>
          }
        }
        @if (showExport()) {
          <app-exam-export-dialog [exam]="e" [roster]="roster()" [results]="results()" (closed)="showExport.set(false)" />
        }
      } @else if (!error()) {
        <p class="text-secondary">Yükleniyor…</p>
      }
    </div>
  `,
  styles: [`
    .actions { flex-wrap: wrap; }
    .notify { margin-top: 8px; display: flex; flex-direction: column; gap: 8px; max-width: 640px; }
    .notify .panel-title { margin: 0; font-size: 1rem; }
    .notify .field { display: flex; flex-direction: column; gap: 4px; font-size: 0.875rem; }
    .notify .toggle { display: flex; align-items: center; gap: 8px; font-size: 0.875rem; }
    .publish { flex-wrap: wrap; margin-top: 6px; }
    .q-list { list-style: none; margin: 0; padding: 0; }
    .q-item { border: 1px solid var(--c-border); border-radius: var(--radius-md); background: var(--c-surface); padding: 10px 12px; margin-bottom: 8px; }
    .q-item.q-sub { margin-left: 18px; }
    .q-group { border-left: 4px solid var(--c-primary); background: var(--c-primary-light); border-radius: var(--radius-md); padding: 10px 12px; margin-bottom: 8px; }
    .q-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; }
    .q-text { white-space: pre-wrap; overflow-wrap: anywhere; margin-top: 4px; }
    .q-content summary { cursor: pointer; margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--c-text-secondary); }
    .q-code { white-space: pre-wrap; overflow-wrap: anywhere; font-family: var(--font-mono); font-size: 0.85rem; background: var(--c-bg); border: 1px solid var(--c-border); border-radius: var(--radius-md); padding: 8px; margin: 6px 0; }
    .q-options { list-style: none; padding: 0; margin: 8px 0; }
    .q-options li { display: flex; gap: 8px; padding: 6px 8px; border: 1px solid var(--c-border); border-radius: var(--radius-md); margin-bottom: 4px; }
    .q-options li.key { border-color: var(--c-success); background: var(--c-success-light); }
    .q-letter { font-weight: 700; min-width: 1.2em; }
    .q-opt-body { flex: 1; min-width: 0; }
    .q-why { color: var(--c-text-secondary); font-size: 0.85rem; white-space: pre-wrap; margin-top: 2px; }
    .q-expl { border-left: 3px solid var(--c-primary); background: var(--c-primary-light); padding: 6px 10px; border-radius: 4px; margin-top: 6px; }
    .tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--c-border); margin-bottom: var(--space-md); }
    .tab { background: none; border: none; border-bottom: 3px solid transparent; padding: 8px 16px; cursor: pointer; color: var(--c-text-secondary); font-weight: 500; }
    .tab.active { color: var(--c-primary); border-bottom-color: var(--c-primary); }
    .controls { flex-wrap: wrap; margin-bottom: var(--space-md); }
    .chips { display: flex; flex-wrap: wrap; gap: 6px; }
    .chip { border: 1px solid var(--c-border); background: var(--c-surface); border-radius: 999px; padding: 4px 12px; cursor: pointer; font-size: 0.85rem; }
    .chip.active { background: var(--c-primary); border-color: var(--c-primary); color: #fff; }
    .table-wrap { overflow-x: auto; border: 1px solid var(--c-border); border-radius: var(--radius-md); background: var(--c-surface); margin-bottom: var(--space-md); }
    .table-wrap table { min-width: 560px; }
    .num { text-align: right; white-space: nowrap; }
    .row-actions { white-space: nowrap; text-align: right; }
    .tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: var(--space-md); margin-bottom: var(--space-lg); }
    .tile-value { font-size: 1.6rem; font-weight: 600; }
    .empty { text-align: center; }
    .hint { background: var(--c-warning-light); padding: var(--space-sm) var(--space-md); border-radius: var(--radius-md); }
    .rate { display: flex; align-items: center; gap: 8px; min-width: 130px; }
    .bar { flex: 1; height: 10px; background: var(--c-border); border-radius: 999px; overflow: hidden; min-width: 60px; }
    .bar.small { height: 8px; min-width: 40px; }
    .fill { height: 100%; border-radius: 999px; background: var(--c-text-secondary); }
    .fill.neutral { background: var(--c-primary); }
    [data-level='danger'] .fill { background: var(--c-danger); }
    [data-level='warning'] .fill { background: var(--c-warning); }
    [data-level='success'] .fill { background: var(--c-success); }
    .rate-text { font-weight: 600; white-space: nowrap; min-width: 3em; }
    .rate-text[data-level='danger'], [data-level='danger'] .rate-text { color: var(--c-danger); }
    .rate-text[data-level='success'], [data-level='success'] .rate-text { color: var(--c-success); }
    .weak-row { background: var(--c-danger-light); }
    .weak-badge { margin-left: 4px; text-transform: none; }
    .choices { display: flex; flex-direction: column; gap: 3px; min-width: 170px; }
    .choice { display: flex; align-items: center; gap: 6px; font-size: 0.8rem; }
    .choice-label { width: 2.4em; font-weight: 600; }
    .choice-count { min-width: 2.5em; text-align: right; }
    .choice.correct .choice-label, .choice.correct .choice-count { color: var(--c-success); }
    .choice.correct .fill { background: var(--c-success); }
  `],
})
export class ExamDetailComponent implements OnInit {
  protected readonly api = inject(ApiService);
  readonly id = input.required<string>();

  readonly exam = signal<ExamDto | null>(null);
  readonly roster = signal<RosterEntryDto[]>([]);
  readonly results = signal<ExamResultDto[]>([]);
  readonly analysis = signal<ExamAnalysisDto | null>(null);
  readonly error = signal('');
  readonly analysisError = signal('');
  readonly tab = signal<'results' | 'analysis' | 'questions'>('results');
  readonly publishing = signal(false);
  readonly questionRows = computed(() => {
    const groups = new Map((this.exam()?.groups ?? []).map(g => [g.id, g]));
    let prev: number | null = null;
    return [...(this.exam()?.questions ?? [])].sort((a, b) => a.position - b.position).map(q => {
      const gid = q.groupId ?? null;
      const groupHead: ExamGroupDto | null = gid != null && gid !== prev ? groups.get(gid) ?? null : null;
      prev = gid;
      const hasHtml = hasRichContent(q.html);
      return {
        ...q,
        printed: questionLabel(q),
        groupHead,
        hasHtml,
        topicList: apiTopics(q.topics),
        summary: hasHtml ? richSummary(q.html) : (q.text ?? '').trim().split('\n')[0].slice(0, 160),
        hasContent: hasHtml || !!q.text?.trim() || !!q.explanation?.trim() || (q.options ?? []).some(o => o.text?.trim() || o.rationale?.trim()),
      };
    });
  });
  readonly optionLabels = optionLabels;
  readonly segments = textSegments;
  readonly anyTopics = computed(() => this.questionRows().some(q => q.topicList.length > 0));
  readonly showExport = signal(false);
  readonly notifyOpen = signal(false);
  readonly notifyMessage = signal('');
  readonly notifyReport = signal(true);
  readonly notifying = signal(false);
  readonly notifyResult = signal('');
  /** Results whose student has an e-mail on the roster (the mail goes only to them). */
  readonly notifyCounts = computed(() => {
    const emails = new Map(this.roster().map(r => [r.studentNo, !!r.email?.trim()]));
    const results = this.results();
    return { results: results.length, withEmail: results.filter(r => emails.get(r.studentNo)).length };
  });
  readonly query = signal('');
  readonly filter = signal<ResultFilter>('ALL');

  readonly filters: { key: ResultFilter; label: string }[] = [
    { key: 'ALL', label: 'Tümü' }, { key: 'MISSING', label: 'Girilmedi' }, { key: 'PARTIAL', label: 'Eksik' }, { key: 'COMPLETE', label: 'Tamam' },
  ];
  readonly statusLabels = STATUS_LABELS;
  readonly typeLabels = QUESTION_TYPE_LABELS;

  readonly rows = computed(() => mergeRows(this.roster(), this.results(), this.exam()?.totalPoints ?? 0));
  readonly counts = computed(() => statusCounts(this.rows()));
  readonly visibleRows = computed(() => filterRows(this.rows(), this.filter(), this.query()));
  readonly entered = computed(() => this.rows().filter(r => r.status !== 'MISSING').length);

  readonly tiles = computed(() => {
    const a = this.analysis();
    return a ? [
      { label: 'Ortalama', value: a.average }, { label: 'Ortanca', value: a.median },
      { label: 'En düşük', value: a.min }, { label: 'En yüksek', value: a.max },
    ] : [];
  });
  readonly topicsSorted = computed(() => sortByRateAsc(this.analysis()?.topics ?? [], t => t.successRate));
  readonly studentsSorted = computed(() => sortByRateAsc(this.analysis()?.students ?? [], s => s.percent));
  private readonly correctByQuestion = computed(() => new Map((this.exam()?.questions ?? []).map(q => [q.id, q.correctChoice])));
  private readonly labelsByQuestion = computed(() => new Map((this.exam()?.questions ?? []).map(q => [q.id, optionLabels(q)])));

  readonly formatExamDate = formatExamDate;
  readonly fmt = formatNumber;
  readonly pct = formatPercent;
  readonly bw = barWidth;
  readonly level = rateLevel;

  choicesFor(questionId: number, counts: Record<string, number>) {
    return choiceBars(counts, this.correctByQuestion().get(questionId) ?? null, this.labelsByQuestion().get(questionId) ?? []);
  }

  async ngOnInit() {
    try {
      const exam = await this.api.getExam(this.id());
      this.exam.set(exam);
      const [roster, results] = await Promise.all([this.api.getRoster(exam.courseId), this.api.getExamResults(this.id())]);
      this.roster.set(roster);
      this.results.set(results);
    } catch (e) { this.error.set(errorMessage(e)); }
    await this.loadAnalysis();
  }

  private async loadAnalysis() {
    this.analysisError.set('');
    try { this.analysis.set(await this.api.getExamAnalysis(this.id())); }
    catch (e) { this.analysisError.set(errorMessage(e)); }
  }

  /** Publish: students see their results and the answer key. The exam is read again for the server's state. */
  openNotify() { this.notifyResult.set(''); this.notifyOpen.set(true); }

  formatWhen(iso: string) {
    try { return new Date(iso).toLocaleString('tr-TR', { dateStyle: 'medium', timeStyle: 'short' }); } catch { return iso; }
  }

  async sendNotify(e: ExamDto) {
    const n = this.notifyCounts();
    if (!confirm(`${n.withEmail} öğrenciye "${e.title}" sonuç e-postası gönderilsin mi?`)) return;
    this.notifying.set(true);
    this.notifyResult.set('');
    try {
      const r = await this.api.notifyExam(e.id, { message: this.notifyMessage().trim() || null, includeReport: this.notifyReport() });
      this.notifyResult.set(`${r.sent} öğrenciye gönderildi; ${r.withoutEmail} öğrencinin e-postası yok.`);
      this.exam.set(await this.api.getExam(this.id()));
    } catch (err) { this.notifyResult.set(errorMessage(err)); }
    finally { this.notifying.set(false); }
  }

  async togglePublish(e: ExamDto) {
    const publish = !e.published;
    const question = publish
      ? `"${e.title}" yayınlansın mı? Öğrenciler kendi sonuçlarını, cevaplarını ve cevap anahtarını görebilecek.`
      : `"${e.title}" yayından kaldırılsın mı? Öğrenciler bu sınavın sonuçlarını artık göremeyecek.`;
    if (!confirm(question)) return;
    this.publishing.set(true);
    this.error.set('');
    try {
      await this.api.publishExam(e.id, publish);
      this.exam.set(await this.api.getExam(this.id()));
    } catch (err) { this.error.set(errorMessage(err)); }
    finally { this.publishing.set(false); }
  }

  async deleteResult(r: ResultRow) {
    if (!confirm(`${r.studentNo} — ${r.fullName} öğrencisinin sonucu silinsin mi?`)) return;
    this.error.set('');
    try {
      await this.api.deleteExamResult(this.id(), r.studentNo);
      this.results.set(await this.api.getExamResults(this.id()));
      await this.loadAnalysis();
    } catch (e) { this.error.set(errorMessage(e)); }
  }
}
