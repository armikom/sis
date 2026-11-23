import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../shared/errors';
import { PoolApiService } from '../pool/pool-api.service';
import { TopicChipsComponent } from '../shared/topics/topic-chips.component';
import { TopicPickerComponent } from '../shared/topics/topic-picker.component';
import { HtmlEditorComponent } from '../shared/html-editor.component';
import { apiTopics, poolTopics, Topic } from '../shared/topics/topic';
import { LABEL_PRESETS, describeLabels, normalizeLabels } from '../shared/question-labels';
import type { ExamDto, QuestionType } from '../core/api.models';
import {
  MAX_QUESTIONS, TYPE_LABELS, QuestionDraft, GroupDraft, applyKey, bulkRows, buildRequest, destructiveChanges, fromDto,
  gatherGroup, groupStarts, groupsFromDto, hasContent, inheritedTopics, keyString, labelsOk, lettersOf, MAX_EXPLANATION,
  MAX_OPTION_TEXT, MAX_RATIONALE, MAX_SUB_LABEL, MAX_TEXT, newGroupKey, optionRows, pruneGroups, rowLabels, setOption,
  splitGroups, moveRow, newRow, topicsOf, totalPoints, validate, validLabels, withLabels,
} from './exam-edit.logic';

@Component({
  selector: 'app-exam-edit',
  imports: [RouterLink, TopicChipsComponent, TopicPickerComponent, HtmlEditorComponent],
  template: `
    <div class="container">
      <nav class="breadcrumb" aria-label="Konum">
        <a routerLink="/t">Dersler</a> ›
        @if (courseIdValue(); as cid) { <a [routerLink]="['/t/courses', cid]">{{ courseLabel() || 'Ders' }}</a> › }
        @if (isEdit()) { <a [routerLink]="['/t/exams', id()]">{{ title() || 'Sınav' }}</a> › Düzenle } @else { Yeni sınav }
      </nav>
      <div class="page-header">
        <h1>{{ isEdit() ? 'Sınavı düzenle' : 'Yeni sınav' }}</h1>
        <div class="flex-gap">
          @if (isEdit()) { <button class="btn btn-outline text-danger" type="button" (click)="deleteExam()" [disabled]="busy()">Sınavı sil</button> }
          <button class="btn btn-primary" type="button" (click)="save()" [disabled]="busy() || !loaded()">{{ busy() ? 'Kaydediliyor…' : 'Kaydet' }}</button>
        </div>
      </div>
      @if (error()) { <p class="form-error" role="alert">{{ error() }}</p> }
      @if (problems().length > 0 && showProblems()) {
        <div class="card mb-md" role="alert">
          <strong class="text-danger">Lütfen düzeltin:</strong>
          <ul class="problems">@for (p of problems(); track $index) { <li>{{ p }}</li> }</ul>
        </div>
      }

      <div class="card mb-md meta">
        <div class="form-group">
          <label for="exam-title">Başlık</label>
          <input id="exam-title" class="form-input" maxlength="200" [value]="title()" (input)="title.set($any($event.target).value)" placeholder="Örn. Vize" />
        </div>
        <div class="form-group">
          <label for="exam-date">Sınav tarihi</label>
          <input id="exam-date" class="form-input" type="date" [value]="examDate()" (input)="examDate.set($any($event.target).value)" />
        </div>
      </div>

      <div class="card mb-md">
        <h2 class="panel-title">Toplu ekle</h2>
        <div class="bulk">
          <label>Adet
            <input class="form-input" type="number" min="1" [max]="maxQuestions" [value]="bulkCount()" (input)="bulkCount.set(+$any($event.target).value)" />
          </label>
          <label>Tür
            <select class="form-input" [value]="bulkType()" (change)="bulkType.set($any($event.target).value)">
              @for (t of types; track t) { <option [value]="t" [selected]="bulkType() === t">{{ labels[t] }}</option> }
            </select>
          </label>
          @if (bulkType() === 'CHOICE') {
            <label>Seçenekler
              <select class="form-input" [value]="bulkLabels()" (change)="bulkLabels.set($any($event.target).value)">
                @for (p of presets; track p.labels) { <option [value]="p.labels" [selected]="bulkLabels() === p.labels">{{ p.name }}</option> }
              </select>
            </label>
          }
          <label>Soru başı puan
            <input class="form-input" type="number" min="0" step="any" [value]="bulkPoints()" (input)="bulkPoints.set(+$any($event.target).value)" />
          </label>
          <label>Konu (isteğe bağlı)
            <input class="form-input" list="exam-topics" maxlength="120" [value]="bulkTopic()" (input)="bulkTopic.set($any($event.target).value)" />
          </label>
          <button class="btn btn-outline" type="button" (click)="addBulk()">Ekle</button>
        </div>
      </div>

      <div class="card mb-md">
        <h2 class="panel-title">Cevap anahtarı</h2>
        <label class="key-label" for="key-input">Seçenek etiketlerini sırayla yazın veya yapıştırın (örn. ABCDA… ya da DYYD, 1011). Boşluk ve noktalama yok sayılır.</label>
        <div class="flex-gap" style="flex-wrap:wrap">
          <input id="key-input" class="form-input text-mono" style="flex:1;min-width:200px" autocomplete="off" spellcheck="false"
            [value]="keyInput()" (input)="keyInput.set($any($event.target).value)" placeholder="ABCDABCD…" />
          <button class="btn btn-outline" type="button" (click)="applyKeyInput()" [disabled]="keyPreview().letters === 0 || keyPreview().choiceTotal === 0">Uygula</button>
        </div>
        @if (keyInput().trim()) {
          <p class="text-sm" [class.text-danger]="keyPreview().letters !== keyPreview().choiceTotal || keyPreview().outOfRange.length > 0">
            {{ keyPreview().letters }} harf girildi, {{ keyPreview().choiceTotal }} seçenekli soru var.
            @if (keyPreview().letters < keyPreview().choiceTotal) { Kalan {{ keyPreview().choiceTotal - keyPreview().letters }} sorunun anahtarı değişmeyecek. }
            @if (keyPreview().extra > 0) { Fazla {{ keyPreview().extra }} harf yok sayılacak. }
            @if (keyPreview().outOfRange.length > 0) { Seçenek aralığı dışında kalan harf: soru {{ keyPreview().outOfRange.join(', ') }}. }
          </p>
        }
        @if (keyApplied()) { <p class="text-sm text-secondary" role="status">{{ keyApplied() }}</p> }
        <p class="text-sm text-secondary">Mevcut anahtar ({{ answeredKeys() }}/{{ choiceCount() }}):
          <code class="text-mono key-current">{{ currentKey() || '—' }}</code> <span>(· = girilmemiş)</span></p>
      </div>

      <div class="summary text-secondary" aria-live="polite">
        <strong>{{ rows().length }}</strong> soru · toplam <strong>{{ total() }}</strong> puan
        <button class="btn btn-outline btn-sm" type="button" (click)="addRow()" [disabled]="rows().length >= maxQuestions">+ Soru ekle</button>
        <button class="btn btn-outline btn-sm" type="button" (click)="addGroup()" [disabled]="rows().length >= maxQuestions - 1" title="Ortak kökü olan alt sorular (5a, 5b, …)">+ Soru grubu ekle</button>
        @if (rows().length > 0) { <button class="btn btn-outline btn-sm text-danger" type="button" (click)="clearRows()">Tümünü sil</button> }
      </div>
      <p class="text-sm text-secondary help">Bir sorunun <strong>Grup</strong> sütunundan onu bir gruba alabilirsiniz; grup sırayla gelen soruları kapsar ve kağıtta tek numara alır (5a, 5b…).
        Seçenekler sütunu her sorunun şıklarını belirler: A–D gibi harfler ya da D/Y, T/F, 1/0.</p>
      <datalist id="exam-topics">@for (t of topics(); track t) { <option [value]="t"></option> }</datalist>

      @if (rows().length === 0) {
        <p class="text-secondary">Henüz soru yok. "Toplu ekle" veya "Soru ekle" ile başlayın.</p>
      } @else {
        <div class="q-table">
          <table>
            <thead>
              <tr><th>#</th><th>Grup</th><th>Tür</th><th>Konu</th>@if (hasTopics()) { <th>Bölüm konuları</th> }<th>Puan</th><th>Seçenekler</th><th>Doğru</th><th><span class="sr-only">İşlemler</span></th></tr>
            </thead>
            <tbody>
              @for (r of rows(); track r.key; let i = $index) {
                @if (r.groupKey && starts().get(r.groupKey) === i) {
                  @let g = groupOf(r.groupKey);
                  <tr class="group-row">
                    <td class="text-mono group-no">{{ groupNumber(i) }}</td>
                    <td [attr.colspan]="hasTopics() ? 8 : 7">
                      <div class="group-head">
                        <strong>Soru grubu {{ groupNumber(i) }}</strong>
                        <span class="text-sm text-secondary">· {{ memberCount(r.groupKey) }} alt soru · ortak kök aşağıda</span>
                        <span class="grow"></span>
                        <button class="btn btn-outline btn-sm" type="button" (click)="toggleGroupEditor(r.groupKey)" [attr.aria-expanded]="groupOpen().has(r.groupKey)">{{ groupOpen().has(r.groupKey) ? 'Kökü gizle' : 'Grup kökü (metin / HTML)' }}</button>
                        <button class="btn btn-outline btn-sm" type="button" (click)="addToGroup(r.groupKey)" [disabled]="rows().length >= maxQuestions">+ Alt soru</button>
                        <button class="btn btn-outline btn-sm text-danger" type="button" (click)="dissolveGroup(r.groupKey)" title="Alt sorular bağımsız soru olur; cevaplar silinmez">Grubu dağıt</button>
                      </div>
                      @if (isSplit(r.groupKey)) {
                        <p class="text-sm text-danger">Bu grubun soruları ardışık değil. <button type="button" class="link" (click)="gather(r.groupKey)">Bir araya getir</button></p>
                      }
                      @if (g && groupOpen().has(r.groupKey)) {
                        <div class="content-editor group-editor">
                          <label class="field">Ortak kök (düz metin)
                            <textarea class="form-input mono" rows="3" [attr.maxlength]="maxText" spellcheck="false" [attr.aria-label]="'Grup ' + groupNumber(i) + ' metni'"
                              placeholder="Alt soruların ortak metni (isteğe bağlı)" [value]="g.text" (input)="patchGroup(g.key, { text: $any($event.target).value })"></textarea>
                          </label>
                          <div class="field">
                            <span>Zengin içerik (HTML; resim, tablo, biçim)</span>
                            <app-html-editor [value]="g.html" [label]="'Grup ' + groupNumber(i) + ' içeriği'" (valueChange)="patchGroup(g.key, { html: $event })" />
                          </div>
                        </div>
                      }
                    </td>
                  </tr>
                }
                <tr [class.in-group]="!!r.groupKey">
                  <td class="text-mono">{{ labelOf(i) }}</td>
                  <td>
                    <div class="group-cell">
                      <select class="form-input group-select" [attr.aria-label]="'Soru ' + labelOf(i) + ' grubu'" (change)="setGroup(r.key, $any($event.target).value)">
                        <option value="" [selected]="!r.groupKey">—</option>
                        @for (gk of groupKeys(); track gk) { <option [value]="gk" [selected]="r.groupKey === gk">Grup {{ groupNumberOf(gk) }}</option> }
                        <option value="__new" [selected]="false">+ Yeni grup</option>
                      </select>
                      @if (r.groupKey) {
                        <input class="form-input sub" [attr.maxlength]="maxSubLabel" [attr.aria-label]="'Soru ' + labelOf(i) + ' alt etiketi'" placeholder="a" title="Alt soru etiketi (boş = sırayla a, b, c…)"
                          [value]="r.subLabel" (input)="patch(r.key, { subLabel: $any($event.target).value })" />
                      }
                    </div>
                  </td>
                  <td>
                    <select class="form-input" [attr.aria-label]="'Soru ' + labelOf(i) + ' türü'" (change)="setType(r.key, $any($event.target).value)">
                      @for (t of types; track t) { <option [value]="t" [selected]="r.type === t">{{ labels[t] }}</option> }
                    </select>
                  </td>
                  <td><input class="form-input" list="exam-topics" maxlength="120" [attr.aria-label]="'Soru ' + labelOf(i) + ' konusu'" [value]="r.topic" (input)="patch(r.key, { topic: $any($event.target).value })" /></td>
                  @if (hasTopics()) {
                    <td>
                      <button class="btn btn-outline btn-sm topics-btn" type="button" (click)="toggleTopics(r.key)" [attr.aria-expanded]="topicsOpen().has(r.key)"
                        [attr.aria-label]="'Soru ' + labelOf(i) + ' bölüm konuları'">{{ r.topicIds.length + inherited(r).length }} konu</button>
                    </td>
                  }
                  <td><input class="form-input num" type="number" min="0" step="any" [attr.aria-label]="'Soru ' + labelOf(i) + ' puanı'" [value]="r.maxPoints ?? ''" (input)="setPoints(r.key, $any($event.target).value)" /></td>
                  <td>
                    @if (r.type === 'CHOICE') {
                      <div class="labels-cell">
                        <select class="form-input labels-select" [attr.aria-label]="'Soru ' + labelOf(i) + ' seçenekleri'" (change)="setLabelsPreset(r.key, $any($event.target).value)">
                          @for (p of presets; track p.labels) { <option [value]="p.labels" [selected]="validLabelsOf(r) === p.labels">{{ p.name }}</option> }
                          <option value="__custom" [selected]="!isPreset(r)">Özel: {{ isPreset(r) ? '…' : describe(r) }}</option>
                        </select>
                        @if (custom().has(r.key)) {
                          <input class="form-input custom-labels text-mono" [attr.aria-label]="'Soru ' + labelOf(i) + ' özel seçenek etiketleri'" placeholder="örn. DY, 10, ABCDEFGH"
                            [class.invalid]="!labelsOk(r.labels)" [value]="r.labels" (input)="setLabels(r.key, $any($event.target).value)" />
                        }
                      </div>
                    } @else { <span class="text-secondary">—</span> }
                  </td>
                  <td>
                    @if (r.type === 'CHOICE') {
                      <select class="form-input num" [attr.aria-label]="'Soru ' + labelOf(i) + ' doğru cevabı'" (change)="patch(r.key, { correctChoice: $any($event.target).value || null })">
                        <option value="" [selected]="!r.correctChoice">—</option>
                        @for (l of lettersFor(r); track l) { <option [value]="l" [selected]="r.correctChoice === l">{{ l }}</option> }
                      </select>
                    } @else { <span class="text-secondary">—</span> }
                  </td>
                  <td class="actions">
                    <button class="btn-icon content-btn" type="button" [class.has-content]="contentOf(r)" (click)="toggleText(r.key)" [attr.aria-expanded]="expanded().has(r.key)"
                      [attr.aria-label]="'Soru ' + labelOf(i) + ' içeriğini ' + (expanded().has(r.key) ? 'gizle' : 'göster') + (contentOf(r) ? ' (içerik var)' : '')"
                      [title]="contentOf(r) ? 'Soru içeriği (kayıtlı)' : 'Soru içeriği'">{{ contentOf(r) ? '✎' : '¶' }}</button>
                    <button class="btn-icon" type="button" (click)="move(i, -1)" [disabled]="i === 0" [attr.aria-label]="'Soru ' + labelOf(i) + ' yukarı taşı'">↑</button>
                    <button class="btn-icon" type="button" (click)="move(i, 1)" [disabled]="i === rows().length - 1" [attr.aria-label]="'Soru ' + labelOf(i) + ' aşağı taşı'">↓</button>
                    <button class="btn-icon text-danger" type="button" (click)="remove(r.key)" [attr.aria-label]="'Soru ' + labelOf(i) + ' sil'">✕</button>
                  </td>
                </tr>
                @if (hasTopics() && topicsOpen().has(r.key)) {
                  <tr class="text-row">
                    <td></td>
                    <td [attr.colspan]="8">
                      @if (inherited(r).length) {
                        <div class="text-sm text-secondary">Havuz sorusundan gelen konular:</div>
                        <app-topic-chips [topics]="inheritedList(r)" />
                      }
                      <app-topic-picker [topics]="courseTopics()" [selected]="r.topicIds" (selectedChange)="patch(r.key, { topicIds: $event })"
                        [label]="'Soru ' + labelOf(i) + ' bölüm konuları'" />
                    </td>
                  </tr>
                }
                @if (expanded().has(r.key)) {
                  <tr class="text-row">
                    <td></td>
                    <td [attr.colspan]="hasTopics() ? 8 : 7">
                      <div class="content-editor">
                        <h3 class="content-title">Soru {{ labelOf(i) }} içeriği</h3>
                        <label class="field">Soru metni (düz metin)
                          <textarea class="form-input mono" rows="4" [attr.maxlength]="maxText" spellcheck="false" [attr.aria-label]="'Soru ' + labelOf(i) + ' metni'"
                            placeholder="Soru metni (isteğe bağlı; kod blokları olduğu gibi saklanır)" [value]="r.text" (input)="patch(r.key, { text: $any($event.target).value })"></textarea>
                        </label>
                        <div class="field">
                          <span>Zengin içerik (HTML; resim, tablo, biçim) — varsa öğrenciye düz metin yerine bu gösterilir</span>
                          <app-html-editor [value]="r.html" [label]="'Soru ' + labelOf(i) + ' HTML içeriği'" (valueChange)="patch(r.key, { html: $event, contentEdited: true })" />
                        </div>
                        @if (r.type === 'CHOICE') {
                          @for (o of optionsOf(r); track o.letter) {
                            <div class="opt" [class.key]="r.correctChoice === o.letter">
                              <div class="opt-letter">{{ o.letter }}@if (r.correctChoice === o.letter) { <span class="badge badge-success">doğru</span> }</div>
                              <div class="opt-fields">
                                <textarea class="form-input mono" rows="1" [attr.maxlength]="maxOption" [attr.aria-label]="'Soru ' + labelOf(i) + ' ' + o.letter + ' seçeneği'"
                                  [placeholder]="o.letter + ' seçeneğinin metni'" [value]="o.text" (input)="setOptionField(r, o.letter, 'text', $any($event.target).value)"></textarea>
                                <textarea class="form-input" rows="1" [attr.maxlength]="maxRationale" [attr.aria-label]="'Soru ' + labelOf(i) + ' ' + o.letter + ' açıklaması'"
                                  placeholder="Açıklama (neden doğru/yanlış)" [value]="o.rationale" (input)="setOptionField(r, o.letter, 'rationale', $any($event.target).value)"></textarea>
                              </div>
                            </div>
                          }
                        }
                        <label class="field">Cevap açıklaması
                          <textarea class="form-input" rows="3" [attr.maxlength]="maxExplanation" [attr.aria-label]="'Soru ' + labelOf(i) + ' cevap açıklaması'"
                            placeholder="Doğru cevabın genel açıklaması (isteğe bağlı)" [value]="r.explanation" (input)="patch(r.key, { explanation: $any($event.target).value })"></textarea>
                        </label>
                      </div>
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
      }

      <div class="flex-gap mt-md">
        <button class="btn btn-primary" type="button" (click)="save()" [disabled]="busy() || !loaded()">{{ busy() ? 'Kaydediliyor…' : 'Kaydet' }}</button>
        <a class="btn btn-outline" [routerLink]="isEdit() ? ['/t/exams', id()] : cancelLink()">Vazgeç</a>
      </div>
    </div>
  `,
  styles: [`
    .meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
    .meta .form-group { margin: 0; }
    .panel-title { margin: 0 0 8px; font-size: 1rem; }
    .bulk { display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end; }
    .bulk label, .key-label { display: flex; flex-direction: column; gap: 4px; font-size: 0.875rem; }
    .bulk .form-input { width: auto; min-width: 90px; }
    .key-label { margin-bottom: 6px; }
    .key-current { word-break: break-all; }
    .summary { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; }
    .problems { margin: 6px 0 0; padding-left: 20px; }
    .q-table { overflow-x: auto; border: 1px solid var(--c-border); border-radius: var(--radius-md); background: var(--c-surface); }
    .q-table table { min-width: 640px; }
    .q-table .form-input { padding: 6px 8px; }
    .q-table .num { width: 76px; }
    .q-table .actions { white-space: nowrap; text-align: right; }
    .text-row textarea { width: 100%; }
    .content-btn.has-content { color: var(--c-primary); font-weight: 700; }
    .content-editor { display: flex; flex-direction: column; gap: 8px; }
    .content-title { margin: 0; font-size: 0.95rem; }
    .field { display: flex; flex-direction: column; gap: 4px; font-size: 0.875rem; }
    .mono { font-family: var(--font-mono); font-size: 0.85rem; white-space: pre-wrap; tab-size: 4; }
    .opt { display: flex; gap: 8px; align-items: flex-start; padding: 6px; border: 1px solid var(--c-border); border-radius: var(--radius-md); }
    .opt.key { border-color: var(--c-success); background: var(--c-success-light); }
    .opt-letter { font-weight: 700; min-width: 2.2em; display: flex; flex-direction: column; gap: 2px; align-items: flex-start; }
    .opt-fields { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
    .topics-btn { white-space: nowrap; }
    .help { margin: 0 0 8px; }
    .group-row td { background: var(--c-primary-light); border-top: 2px solid var(--c-primary); }
    .group-no { font-weight: 700; color: var(--c-primary); }
    .group-head { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; }
    .group-head .grow { flex: 1; }
    .group-editor { margin-top: 8px; }
    .in-group > td:first-child { padding-left: 18px; color: var(--c-primary); }
    .group-cell { display: flex; gap: 4px; align-items: center; }
    .group-select { width: auto; min-width: 7em; }
    .sub { width: 3.4em; text-align: center; }
    .labels-cell { display: flex; flex-direction: column; gap: 4px; }
    .labels-select { width: auto; min-width: 6.5em; }
    .custom-labels { width: 9em; }
    .custom-labels.invalid { border-color: var(--c-danger); }
    .link { background: none; border: 0; padding: 0; color: var(--c-primary); text-decoration: underline; cursor: pointer; font: inherit; }
    .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
  `],
})
export class ExamEditComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly poolApi = inject(PoolApiService);

  readonly id = input<string>();
  readonly courseId = input<string>();

  readonly types: QuestionType[] = ['CHOICE', 'OPEN', 'FREE'];
  readonly labels = TYPE_LABELS;
  readonly presets = LABEL_PRESETS;
  readonly maxQuestions = MAX_QUESTIONS;
  readonly maxSubLabel = MAX_SUB_LABEL;

  readonly exam = signal<ExamDto | null>(null);
  readonly courseLabel = signal('');
  readonly loaded = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly showProblems = signal(false);

  readonly title = signal('');
  readonly examDate = signal('');
  readonly rows = signal<QuestionDraft[]>([]);
  readonly groups = signal<GroupDraft[]>([]);
  readonly expanded = signal<ReadonlySet<number>>(new Set());
  readonly topicsOpen = signal<ReadonlySet<number>>(new Set());
  /** Rows whose "custom labels" box is open (preset "Özel" chosen). */
  readonly custom = signal<ReadonlySet<number>>(new Set());
  readonly groupOpen = signal<ReadonlySet<string>>(new Set());
  /** Chapter topics of the course; empty (and the topic column hidden) when the course has no pool chapters. */
  readonly courseTopics = signal<Topic[]>([]);
  readonly hasTopics = computed(() => this.courseTopics().length > 0);

  readonly bulkCount = signal(25);
  readonly bulkType = signal<QuestionType>('CHOICE');
  readonly bulkLabels = signal('ABCD');
  readonly bulkPoints = signal(4);
  readonly bulkTopic = signal('');

  readonly keyInput = signal('');
  readonly keyApplied = signal('');

  readonly isEdit = computed(() => !!this.id());
  readonly courseIdValue = computed(() => this.exam()?.courseId ?? this.courseId() ?? null);
  readonly total = computed(() => totalPoints(this.rows()));
  readonly topics = computed(() => topicsOf(this.rows()));
  readonly problems = computed(() => validate(this.title(), this.rows(), this.groups()));
  readonly rowLabelList = computed(() => rowLabels(this.rows()));
  readonly starts = computed(() => groupStarts(this.rows()));
  readonly groupKeys = computed(() => [...this.starts().keys()]);
  readonly split = computed(() => new Set(splitGroups(this.rows())));
  readonly keyPreview = computed(() => applyKey(this.rows(), this.keyInput()));
  readonly currentKey = computed(() => keyString(this.rows()));
  readonly choiceCount = computed(() => this.rows().filter(r => r.type === 'CHOICE').length);
  readonly answeredKeys = computed(() => this.rows().filter(r => r.type === 'CHOICE' && r.correctChoice).length);
  readonly cancelLink = computed(() => (this.courseIdValue() ? ['/t/courses', this.courseIdValue()] : ['/t']));

  async ngOnInit() {
    try {
      if (this.id()) {
        const e = await this.api.getExam(this.id()!);
        this.exam.set(e);
        this.title.set(e.title);
        this.examDate.set(e.examDate ?? '');
        this.rows.set(e.questions.map(fromDto));
        this.groups.set(groupsFromDto(e));
        this.courseLabel.set(`${e.courseCode} — ${e.courseName}`);
      } else if (this.courseId()) {
        const c = await this.api.getCourse(this.courseId()!);
        this.courseLabel.set(`${c.code} — ${c.name}`);
      } else {
        this.error.set('Ders belirtilmedi. Sınavı bir ders sayfasından oluşturun.');
        return;
      }
      this.loaded.set(true);
      this.loadTopics();
    } catch (e) { this.error.set(errorMessage(e)); }
  }

  /** Quietly: courses without a pool (404/409) or without chapters just have no topic column. */
  private loadTopics() {
    const cid = this.courseIdValue();
    if (!cid) return;
    this.poolApi.courseTopics(cid).then(list => this.courseTopics.set(poolTopics(list)), () => this.courseTopics.set([]));
  }

  inherited(r: QuestionDraft) { return inheritedTopics(r); }
  inheritedList(r: QuestionDraft) { return apiTopics(inheritedTopics(r)); }

  toggleTopics(key: number) {
    this.topicsOpen.update(s => { const n = new Set(s); if (n.has(key)) n.delete(key); else n.add(key); return n; });
  }

  lettersFor(r: QuestionDraft) { return lettersOf(r); }
  labelOf(i: number) { return this.rowLabelList()[i] ?? String(i + 1); }
  validLabelsOf(r: QuestionDraft) { return validLabels(r.labels); }
  labelsOk(raw: string) { return labelsOk(raw); }
  isPreset(r: QuestionDraft) { return !this.custom().has(r.key) && this.presets.some(p => p.labels === validLabels(r.labels)); }
  describe(r: QuestionDraft) { return describeLabels(normalizeLabels(r.labels) ?? r.labels); }

  patch(key: number, p: Partial<QuestionDraft>) {
    this.rows.update(rs => rs.map(r => (r.key === key ? { ...r, ...p } : r)));
  }

  setType(key: number, type: QuestionType) {
    this.patch(key, type === 'CHOICE' ? { type } : { type, correctChoice: null });
  }

  setPoints(key: number, raw: string) {
    const v = raw.trim() === '' ? null : Number(raw);
    this.patch(key, { maxPoints: v != null && Number.isFinite(v) ? v : null });
  }

  /** Preset chosen from the list; "Özel" opens the free-text box with the current labels. */
  setLabelsPreset(key: number, value: string) {
    if (value === '__custom') {
      this.custom.update(s => new Set([...s, key]));
      return;
    }
    this.custom.update(s => { const n = new Set(s); n.delete(key); return n; });
    this.setLabels(key, value);
  }

  setLabels(key: number, raw: string) {
    this.rows.update(rs => rs.map(r => (r.key === key ? withLabels(r, raw) : r)));
  }

  /* ---------- groups ---------- */

  groupOf(key: string | null): GroupDraft | null { return key ? this.groups().find(g => g.key === key) ?? null : null; }
  groupNumber(i: number) { return this.labelOf(i).replace(/[a-z].*$/i, ''); }
  groupNumberOf(groupKey: string) { const i = this.starts().get(groupKey); return i == null ? '?' : this.groupNumber(i); }
  memberCount(groupKey: string) { return this.rows().filter(r => r.groupKey === groupKey).length; }
  isSplit(groupKey: string) { return this.split().has(groupKey); }

  patchGroup(key: string, p: Partial<GroupDraft>) {
    this.groups.update(gs => gs.map(g => (g.key === key ? { ...g, ...p } : g)));
  }

  toggleGroupEditor(key: string) {
    this.groupOpen.update(s => { const n = new Set(s); if (n.has(key)) n.delete(key); else n.add(key); return n; });
  }

  /** "Grup" column: none, an existing group, or a new group holding only this row. */
  setGroup(key: number, value: string) {
    let groupKey: string | null = value || null;
    if (value === '__new') {
      groupKey = newGroupKey();
      this.groups.update(gs => [...gs, { key: groupKey!, id: null, text: '', html: '' }]);
      this.groupOpen.update(s => new Set([...s, groupKey!]));
    }
    this.patch(key, { groupKey, subLabel: '' });
    this.groups.update(gs => pruneGroups(this.rows(), gs));
  }

  /** New group with two sub-questions appended at the end (copying the last row's settings). */
  addGroup() {
    const groupKey = newGroupKey();
    const last = this.rows().at(-1);
    const base = last ? { type: last.type, labels: last.labels, maxPoints: last.maxPoints, topic: last.topic } : {};
    this.groups.update(gs => [...gs, { key: groupKey, id: null, text: '', html: '' }]);
    this.rows.update(rs => [...rs, newRow({ ...base, groupKey }), newRow({ ...base, groupKey })].slice(0, MAX_QUESTIONS));
    this.groupOpen.update(s => new Set([...s, groupKey]));
  }

  /** Appends a sub-question right after the group's last row. */
  addToGroup(groupKey: string) {
    if (this.rows().length >= MAX_QUESTIONS) return;
    this.rows.update(rs => {
      let last = -1;
      rs.forEach((r, i) => { if (r.groupKey === groupKey) last = i; });
      if (last < 0) return rs;
      const src = rs[last];
      const row = newRow({ type: src.type, labels: src.labels, maxPoints: src.maxPoints, topic: src.topic, groupKey });
      return [...rs.slice(0, last + 1), row, ...rs.slice(last + 1)];
    });
  }

  dissolveGroup(groupKey: string) {
    this.rows.update(rs => rs.map(r => (r.groupKey === groupKey ? { ...r, groupKey: null, subLabel: '' } : r)));
    this.groups.update(gs => gs.filter(g => g.key !== groupKey));
  }

  gather(groupKey: string) { this.rows.update(rs => gatherGroup(rs, groupKey)); }

  readonly maxText = MAX_TEXT;
  readonly maxOption = MAX_OPTION_TEXT;
  readonly maxRationale = MAX_RATIONALE;
  readonly maxExplanation = MAX_EXPLANATION;

  contentOf(r: QuestionDraft) { return hasContent(r); }
  optionsOf(r: QuestionDraft) { return optionRows(r); }

  setOptionField(r: QuestionDraft, letter: string, field: 'text' | 'rationale', value: string) {
    this.rows.update(rs => rs.map(x => (x.key === r.key ? { ...x, options: setOption(x.options, letter, { [field]: value }), contentEdited: true } : x)));
  }

  /** Opening the content editor marks the row so its options/explanation are sent on save. */
  toggleText(key: number) {
    if (!this.expanded().has(key)) this.patch(key, { contentEdited: true });
    this.expanded.update(s => { const n = new Set(s); if (n.has(key)) n.delete(key); else n.add(key); return n; });
  }

  addRow() {
    if (this.rows().length >= MAX_QUESTIONS) return;
    const last = this.rows().at(-1);
    this.rows.update(rs => [...rs, newRow(last ? { type: last.type, labels: last.labels, maxPoints: last.maxPoints, topic: last.topic } : {})]);
  }

  addBulk() {
    const room = MAX_QUESTIONS - this.rows().length;
    const added = bulkRows({
      count: Math.min(this.bulkCount(), room), type: this.bulkType(), labels: this.bulkLabels(),
      points: this.bulkPoints(), topic: this.bulkTopic(),
    });
    if (added.length === 0) return;
    this.rows.update(rs => [...rs, ...added]);
  }

  move(i: number, d: -1 | 1) { this.rows.update(rs => moveRow(rs, i, d)); }
  remove(key: number) {
    this.rows.update(rs => rs.filter(r => r.key !== key));
    this.groups.update(gs => pruneGroups(this.rows(), gs));
  }
  clearRows() { if (confirm('Tüm sorular listeden silinsin mi?')) { this.rows.set([]); this.groups.set([]); } }

  applyKeyInput() {
    const r = applyKey(this.rows(), this.keyInput());
    this.rows.set(r.rows);
    this.keyApplied.set(`${r.applied} / ${r.choiceTotal} seçenekli soruya cevap anahtarı uygulandı.`);
  }

  async save() {
    this.error.set('');
    this.showProblems.set(true);
    if (this.problems().length > 0) return;
    const exam = this.exam();
    if (exam && exam.resultCount > 0) {
      const d = destructiveChanges(exam.questions, this.rows());
      if (d.removed + d.typeChanged > 0
        && !confirm(`${d.removed} soru silinecek, ${d.typeChanged} sorunun türü değişecek. Bu sorulara girilmiş cevaplar silinecek. Devam edilsin mi?`)) return;
    }
    this.busy.set(true);
    try {
      const req = buildRequest(this.title(), this.examDate(), this.rows(), this.groups());
      const saved = exam ? await this.api.updateExam(exam.id, req) : await this.api.createExam(this.courseId()!, req);
      await this.router.navigateByUrl(`/t/exams/${saved.id}`);
    } catch (e) { this.error.set(errorMessage(e)); }
    finally { this.busy.set(false); }
  }

  async deleteExam() {
    const exam = this.exam();
    if (!exam) return;
    const extra = exam.resultCount > 0 ? ` ${exam.resultCount} öğrencinin sonucu da silinecek.` : '';
    if (!confirm(`"${exam.title}" sınavı silinsin mi?${extra} Bu işlem geri alınamaz.`)) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await this.api.deleteExam(exam.id);
      await this.router.navigateByUrl(`/t/courses/${exam.courseId}`);
    } catch (e) { this.error.set(errorMessage(e)); }
    finally { this.busy.set(false); }
  }
}
