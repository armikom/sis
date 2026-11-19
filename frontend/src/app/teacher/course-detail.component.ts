import { Component, inject, signal, computed, input, OnInit } from '@angular/core';
import { RouterLink, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { MSG } from '../shared/messages';
import { errorMessage } from '../shared/errors';
import { AuthService } from '../core/auth.service';
import type { CourseDto, ExamSummaryDto, RosterEntryDto, RosterImportResult, SessionDto, StaffMemberDto } from '../core/api.models';

@Component({
  selector: 'app-course-detail',
  imports: [RouterLink, FormsModule],
  template: `
    <div class="container">
      <nav class="breadcrumb" aria-label="Konum"><a routerLink="/t">{{ msg.nav.courses }}</a> › {{ course()?.code }}</nav>
      <div class="page-header">
        <div>
          <h1>{{ course() ? course()!.code + ' — ' + course()!.name : msg.courses.title }}</h1>
          @if (course(); as c) {
            <div class="text-sm text-secondary">{{ c.term }} · {{ c.language === 'en' ? 'English' : 'Türkçe' }}
              @if (c.myRole) { · {{ msg.staff.roles[c.myRole] }} }
              @if (c.archived) { <span class="badge badge-warning">{{ msg.settings.archived }}</span> }
            </div>
          }
        </div>
        <div class="flex-gap" style="flex-wrap:wrap">
          <button class="btn btn-outline btn-sm" (click)="toggleSettings()">{{ msg.settings.title }}</button>
          @if (course()?.poolEnabled) {
            <a class="btn btn-outline btn-sm" [routerLink]="['/courses', id(), 'pool']">{{ msg.my.pool }}</a>
          }
          <a class="btn btn-primary" [routerLink]="['/t/sessions/new']" [queryParams]="{ courseId: id() }">{{ msg.sessions.create }}</a>
          @if (course()?.owner) {
            <button class="btn btn-outline btn-sm" (click)="toggleArchive()">{{ course()?.archived ? msg.settings.unarchive : msg.settings.archive }}</button>
            <button class="btn btn-outline btn-sm text-danger" (click)="deleteCourse()" [disabled]="sessions().length > 0 || exams().length > 0"
              [title]="sessions().length > 0 ? 'Oturumu olan ders silinemez' : exams().length > 0 ? 'Sınavı olan ders silinemez' : 'Dersi sil'">Sil</button>
          }
        </div>
      </div>
      @if (error()) { <p class="form-error" role="alert">{{ error() }}</p> }

      @if (editing()) {
        <div class="card mb-md">
          <form (ngSubmit)="saveEdit()">
            <div class="form-grid">
              <div class="form-group"><label for="c-code">{{ msg.courses.code }}</label><input id="c-code" class="form-input" [(ngModel)]="edit.code" name="code" required maxlength="32" /></div>
              <div class="form-group"><label for="c-name">{{ msg.courses.name }}</label><input id="c-name" class="form-input" [(ngModel)]="edit.name" name="name" required maxlength="200" /></div>
              <div class="form-group"><label for="c-term">{{ msg.courses.term }}</label><input id="c-term" class="form-input" [(ngModel)]="edit.term" name="term" required maxlength="16" placeholder="2026-FALL" /></div>
              <div class="form-group"><label for="c-lang">{{ msg.settings.language }}</label>
                <select id="c-lang" class="form-input" [(ngModel)]="edit.language" name="language"><option value="tr">Türkçe</option><option value="en">English</option></select>
              </div>
              <div class="form-group"><label for="c-qpc">{{ msg.settings.questionsPerChapter }}</label><input id="c-qpc" class="form-input" type="number" min="0" max="100" [(ngModel)]="edit.questionsPerChapter" name="qpc" /></div>
              <div class="form-group"><label for="c-ai">{{ msg.settings.askAiPerDay }}</label><input id="c-ai" class="form-input" type="number" min="0" max="1000" [(ngModel)]="edit.askAiPerDay" name="ai" /></div>
            </div>
            <div class="flex-gap mb-md" style="flex-wrap:wrap">
              <label class="flex-gap"><input type="checkbox" [(ngModel)]="edit.attendanceEnabled" name="att" /> {{ msg.settings.attendanceEnabled }}</label>
              <label class="flex-gap"><input type="checkbox" [(ngModel)]="edit.poolEnabled" name="pool" /> {{ msg.settings.poolEnabled }}</label>
            </div>
            <div class="form-group"><label for="c-ctx">{{ msg.settings.aiContext }}</label>
              <textarea id="c-ctx" class="form-input" rows="4" [(ngModel)]="edit.aiContext" name="ctx" maxlength="20000"></textarea>
            </div>
            <div class="flex-gap">
              <button class="btn btn-primary btn-sm" type="submit" [disabled]="!edit.code.trim() || !edit.name.trim() || !edit.term.trim()">Kaydet</button>
              <button class="btn btn-outline btn-sm" type="button" (click)="editing.set(false)">İptal</button>
            </div>
          </form>
        </div>
      }

      <h2>{{ msg.courses.roster }} ({{ roster().length }})</h2>
      <div class="flex-gap mb-md" style="flex-wrap:wrap">
        <select class="form-input" [(ngModel)]="importMode" style="width:auto" aria-label="İçe aktarma modu">
          <option value="MERGE">{{ msg.roster.importMerge }}</option>
          <option value="REPLACE">{{ msg.roster.importReplace }}</option>
        </select>
        <label class="btn btn-outline btn-sm" tabindex="0" (keydown.enter)="fileInput.click()" (keydown.space)="fileInput.click()">
          {{ msg.roster.import }}
          <input #fileInput type="file" accept=".csv,.txt,text/csv" (change)="onFileChange($event)" hidden />
        </label>
        <button class="btn btn-outline btn-sm" type="button" (click)="showPaste.set(!showPaste())">{{ msg.roster.paste }}</button>
      </div>
      <p class="text-sm text-secondary">Satır başına: <code>ogrenciNo, Ad Soyad[, e-posta]</code> veya <code>e-posta, Ad Soyad, ogrenciNo</code> (başlık satırı isteğe bağlı, ayırıcı <code>;</code>, <code>,</code> veya sekme)</p>
      @if (showPaste()) {
        <div class="card mb-md">
          <form (ngSubmit)="importPasted()">
            <textarea class="form-input" rows="8" [(ngModel)]="pasteText" name="paste" [placeholder]="msg.roster.pastePlaceholder"
              aria-label="Öğrenci listesi" style="font-family:var(--font-mono);font-size:0.9rem"></textarea>
            <div class="flex-gap mt-md">
              <button class="btn btn-primary btn-sm" type="submit" [disabled]="!pasteText.trim()">{{ msg.roster.pasteImport }}</button>
              <button class="btn btn-outline btn-sm" type="button" (click)="showPaste.set(false)">İptal</button>
            </div>
          </form>
        </div>
      }

      @if (importResult(); as r) {
        <div class="card mb-md text-sm">
          <strong>{{ msg.roster.importResult }}:</strong>
          {{ r.imported }} {{ msg.roster.imported }}@if (r.created) { ({{ r.created }} yeni kişi) }@if (r.removed) {, {{ r.removed }} çıkarıldı }, {{ r.skipped }} {{ msg.roster.skipped }}, {{ r.errors.length }} {{ msg.roster.errors }}
          @if (r.errors.length > 0) {
            <ul class="import-errors">
              @for (e of r.errors.slice(0, 50); track $index) { <li>Satır {{ e.line }}: {{ e.message }}</li> }
            </ul>
          }
        </div>
      }

      <div class="card mb-md">
        <form (ngSubmit)="addStudent()" class="flex-gap" style="flex-wrap:wrap">
          <input class="form-input" [(ngModel)]="newStudentNo" name="sno" [placeholder]="msg.roster.studentNo" inputmode="numeric" style="width:140px" />
          <input class="form-input" [(ngModel)]="newFullName" name="fname" [placeholder]="msg.roster.fullName" style="flex:1;min-width:140px" />
          <input class="form-input" type="email" [(ngModel)]="newEmail" name="femail" [placeholder]="msg.roster.email + ' (isteğe bağlı)'" style="flex:1;min-width:160px" />
          <button class="btn btn-primary btn-sm" type="submit" [disabled]="!newStudentNo.trim() || !newFullName.trim()">{{ msg.roster.addStudent }}</button>
        </form>
      </div>

      @if (roster().length === 0) {
        <p class="text-secondary">{{ msg.roster.noData }}</p>
      } @else {
        @if (roster().length > 10) {
          <input class="form-input search-input mb-md" type="search" placeholder="Numara veya ad ara" aria-label="Öğrenci ara"
            [value]="rosterSearch()" (input)="rosterSearch.set($any($event.target).value)" />
        }
        <div class="roster-table">
          <table>
            <thead><tr><th>{{ msg.roster.studentNo }}</th><th>{{ msg.roster.fullName }}</th>@if (hasEmails()) { <th>{{ msg.roster.email }}</th> }<th></th></tr></thead>
            <tbody>
              @for (r of filteredRoster(); track r.personId ?? r.studentNo) {
                <tr>
                  <td class="text-mono">@if (r.personId) { <a [routerLink]="['/t/courses', id(), 'students', r.personId]">{{ r.studentNo }}</a> } @else { {{ r.studentNo }} }</td>
                  <td>@if (r.personId) { <a [routerLink]="['/t/courses', id(), 'students', r.personId]" [title]="msg.courseStudent.open">{{ r.fullName }}</a> } @else { {{ r.fullName }} }</td>
                  @if (hasEmails()) { <td class="text-sm">{{ r.email }}</td> }
                  <td style="text-align:right"><button class="btn-icon text-danger" (click)="deleteStudent(r)" [attr.aria-label]="r.studentNo + ' öğrencisini sil'">✕</button></td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }

      <h2 class="mt-md">{{ msg.staff.title }} ({{ staff().length }})</h2>
      @if (course()?.owner) {
        <div class="card mb-md">
          <form (ngSubmit)="addStaffMember()" class="flex-gap" style="flex-wrap:wrap">
            <input class="form-input" type="email" [(ngModel)]="staffEmail" name="semail" [placeholder]="msg.staff.email" style="flex:1;min-width:180px" />
            <input class="form-input" [(ngModel)]="staffName" name="sname" [placeholder]="msg.staff.fullName" style="flex:1;min-width:160px" />
            <select class="form-input" [(ngModel)]="staffRole" name="srole" style="width:auto" [attr.aria-label]="msg.staff.role">
              <option value="INSTRUCTOR">{{ msg.staff.roles['INSTRUCTOR'] }}</option>
              <option value="ASSISTANT">{{ msg.staff.roles['ASSISTANT'] }}</option>
              <option value="OWNER">{{ msg.staff.roles['OWNER'] }}</option>
            </select>
            <button class="btn btn-primary btn-sm" type="submit" [disabled]="!staffEmail.trim()">{{ msg.staff.add }}</button>
          </form>
        </div>
      }
      <div class="roster-table mb-md">
        <table>
          <tbody>
            @for (m of staff(); track m.personId) {
              <tr>
                <td>{{ m.fullName }}</td>
                <td class="text-sm">{{ m.email }}</td>
                <td><span class="badge badge-info">{{ msg.staff.roles[m.role] }}</span></td>
                <td style="text-align:right">
                  @if (course()?.owner && m.personId !== auth.user()?.id) {
                    <button class="btn-icon text-danger" (click)="removeStaffMember(m)" [attr.aria-label]="m.fullName + ' personelden çıkar'">✕</button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>

      <div class="exam-head mt-md">
        <h2>Sınavlar ({{ exams().length }})</h2>
        <div class="flex-gap" style="flex-wrap:wrap">
          <a class="btn btn-outline btn-sm" [routerLink]="['/t/courses', id(), 'learning']">Öğrenme durumu</a>
          <a class="btn btn-primary btn-sm" [routerLink]="['/t/exams/new']" [queryParams]="{ courseId: id() }">Yeni sınav</a>
        </div>
      </div>
      @if (exams().length === 0) {
        <p class="text-secondary">Henüz sınav yok.</p>
      }
      @for (x of exams(); track x.id) {
        <a class="card mb-md session-card" [routerLink]="['/t/exams', x.id]">
          <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap">
            <div>
              <strong>{{ x.title }}</strong>
              @if (x.published) { <span class="badge badge-success" style="margin-left:6px">Öğrencilere açık</span> }
              <div class="text-sm text-secondary">{{ x.examDate ? formatExamDate(x.examDate) : 'Tarih yok' }} · {{ x.questionCount }} soru · {{ x.totalPoints }} puan</div>
            </div>
            <div class="text-sm text-secondary" style="text-align:right">
              {{ x.resultCount }} sonuç
              @if (x.averagePoints != null) { <div>Ortalama: {{ x.averagePoints.toFixed(1) }} / {{ x.totalPoints }}</div> }
            </div>
          </div>
        </a>
      }

      <h2 class="mt-md">{{ msg.courses.sessions }} ({{ sessions().length }})</h2>
      @if (sessions().length === 0) {
        <p class="text-secondary">{{ msg.sessions.noData }}</p>
      }
      @for (s of sessions(); track s.id) {
        <a class="card mb-md session-card" [routerLink]="['/t/sessions', s.id]">
          <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap">
            <div>
              <strong>{{ s.title }}</strong>
              <span class="badge" [class.badge-info]="s.status === 'DRAFT'" [class.badge-success]="s.status === 'OPEN'" [class.badge-warning]="s.status === 'CLOSED'">{{ statusLabel(s.status) }}</span>
              <div class="text-sm text-secondary">{{ formatDate(s.openedAt ?? s.createdAt) }}@if (s.classroomName) { · {{ s.classroomName }} }</div>
            </div>
            <div class="text-sm text-secondary" style="text-align:right">
              {{ s.attendanceCount }} katılım
              @if (s.flaggedPendingCount > 0) { <div><span class="badge badge-warning">{{ s.flaggedPendingCount }} inceleme</span></div> }
            </div>
          </div>
        </a>
      }
    </div>
  `,
  styles: [`
    .exam-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
    .exam-head h2 { margin: 0; }
    .session-card { display: block; text-decoration: none; color: inherit; transition: border-color 0.15s; }
    .session-card:hover { text-decoration: none; border-color: var(--c-primary); }
    .roster-table { overflow: auto; max-height: 480px; border: 1px solid var(--c-border); border-radius: var(--radius-md); background: var(--c-surface); }
    .roster-table thead th { position: sticky; top: 0; background: var(--c-surface); }
    .import-errors { margin: 8px 0 0; padding-left: 20px; max-height: 200px; overflow: auto; }
    .form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 0 var(--space-md); }
  `],
})
export class CourseDetailComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  readonly auth = inject(AuthService);
  readonly msg = MSG;
  readonly id = input.required<string>();
  readonly course = signal<CourseDto | null>(null);
  readonly roster = signal<RosterEntryDto[]>([]);
  readonly staff = signal<StaffMemberDto[]>([]);
  readonly hasEmails = computed(() => this.roster().some(r => !!r.email));
  readonly showPaste = signal(false);
  pasteText = '';
  newEmail = '';
  staffEmail = '';
  staffName = '';
  staffRole = 'INSTRUCTOR';
  readonly sessions = signal<SessionDto[]>([]);
  readonly exams = signal<ExamSummaryDto[]>([]);
  readonly importResult = signal<RosterImportResult | null>(null);
  readonly error = signal('');
  readonly rosterSearch = signal('');
  readonly filteredRoster = computed(() => {
    const q = this.rosterSearch().trim().toLocaleLowerCase('tr-TR');
    if (!q) return this.roster();
    return this.roster().filter(r => r.studentNo.includes(q) || r.fullName.toLocaleLowerCase('tr-TR').includes(q)
      || (r.email ?? '').includes(q));
  });
  readonly editing = signal(false);
  edit = { code: '', name: '', term: '', language: 'tr', attendanceEnabled: true, poolEnabled: false,
           questionsPerChapter: 2, askAiPerDay: 10, aiContext: '' };
  importMode = 'MERGE';
  newStudentNo = '';
  newFullName = '';

  async ngOnInit() { await this.run(() => this.load()); }

  async load() {
    const [c, r, s, x, st] = await Promise.all([
      this.api.getCourse(this.id()),
      this.api.getRoster(+this.id()),
      this.api.getSessions(this.id()),
      this.api.getExams(this.id()),
      this.api.getStaff(+this.id()),
    ]);
    this.course.set(c);
    this.roster.set(r);
    this.staff.set(st);
    this.sessions.set(s);
    this.exams.set(x);
  }

  private async run(fn: () => Promise<void>) {
    this.error.set('');
    try { await fn(); } catch (e) { this.error.set(errorMessage(e)); }
  }

  async onFileChange(e: Event) {
    const inputEl = e.target as HTMLInputElement;
    const file = inputEl.files?.[0];
    inputEl.value = '';   // allow re-selecting the same file
    if (!file) return;
    if (this.importMode === 'REPLACE' && this.roster().length > 0
        && !confirm('Dosyada olmayan öğrenciler ders listesinden çıkarılacak. Devam edilsin mi?')) return;
    await this.run(async () => {
      this.importResult.set(await this.api.importRoster(+this.id(), file, this.importMode));
      await this.load();
    });
  }

  importPasted() {
    if (this.importMode === 'REPLACE' && this.roster().length > 0
        && !confirm('Listede olmayan öğrenciler ders listesinden çıkarılacak. Devam edilsin mi?')) return Promise.resolve();
    return this.run(async () => {
      this.importResult.set(await this.api.importRosterText(+this.id(), this.pasteText, this.importMode));
      this.pasteText = '';
      this.showPaste.set(false);
      await this.load();
    });
  }

  addStudent() {
    return this.run(async () => {
      const email = this.newEmail.trim();
      await this.api.addRosterEntry(+this.id(), {
        studentNo: this.newStudentNo.trim(), fullName: this.newFullName.trim(), ...(email ? { email } : {}),
      });
      this.newStudentNo = '';
      this.newFullName = '';
      this.newEmail = '';
      this.roster.set(await this.api.getRoster(+this.id()));
    });
  }

  deleteStudent(r: RosterEntryDto) {
    if (r.personId == null) return Promise.resolve();
    if (!confirm(`${r.studentNo} — ${r.fullName} listeden çıkarılsın mı?`)) return Promise.resolve();
    return this.run(async () => {
      await this.api.deleteRosterEntry(+this.id(), r.personId!);
      this.roster.set(await this.api.getRoster(+this.id()));
    });
  }

  addStaffMember() {
    return this.run(async () => {
      await this.api.addStaff(+this.id(), { email: this.staffEmail.trim(), fullName: this.staffName.trim(), role: this.staffRole });
      this.staffEmail = '';
      this.staffName = '';
      this.staff.set(await this.api.getStaff(+this.id()));
    });
  }

  removeStaffMember(m: StaffMemberDto) {
    if (!confirm(`${m.fullName}: ${this.msg.staff.removeConfirm}`)) return Promise.resolve();
    return this.run(async () => {
      await this.api.removeStaff(+this.id(), m.personId);
      this.staff.set(await this.api.getStaff(+this.id()));
    });
  }

  toggleSettings() {
    const c = this.course();
    if (!c) return;
    if (this.editing()) { this.editing.set(false); return; }
    this.edit = {
      code: c.code, name: c.name, term: c.term, language: c.language, attendanceEnabled: c.attendanceEnabled,
      poolEnabled: c.poolEnabled, questionsPerChapter: c.questionsPerChapter, askAiPerDay: c.askAiPerDay,
      aiContext: c.aiContext ?? '',
    };
    this.editing.set(true);
  }

  saveEdit() {
    return this.run(async () => {
      const e = this.edit;
      await this.api.updateCourse(+this.id(), {
        code: e.code.trim(), name: e.name.trim(), term: e.term.trim(), language: e.language,
        attendanceEnabled: e.attendanceEnabled, poolEnabled: e.poolEnabled,
        questionsPerChapter: Number(e.questionsPerChapter), askAiPerDay: Number(e.askAiPerDay), aiContext: e.aiContext,
      });
      this.editing.set(false);
      await this.load();
    });
  }

  toggleArchive() {
    const c = this.course();
    if (!c) return Promise.resolve();
    return this.run(async () => {
      this.course.set(await this.api.archiveCourse(c.id, !c.archived));
    });
  }

  deleteCourse() {
    if (!confirm(this.msg.courses.deleteConfirm)) return Promise.resolve();
    return this.run(async () => {
      await this.api.deleteCourse(+this.id());
      this.router.navigateByUrl('/t');
    });
  }

  formatExamDate(d: string) {
    try { return new Date(d + 'T00:00:00').toLocaleDateString('tr-TR', { dateStyle: 'medium' }); }
    catch { return d; }
  }

  statusLabel(s: string) { return (MSG.sessions.status as Record<string, string>)[s] ?? s; }

  formatDate(iso: string) {
    try { return new Date(iso).toLocaleString('tr-TR', { dateStyle: 'medium', timeStyle: 'short' }); }
    catch { return iso; }
  }
}
