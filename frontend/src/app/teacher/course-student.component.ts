import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { PoolApiService } from '../pool/pool-api.service';
import { MSG } from '../shared/messages';
import { errorMessage } from '../shared/errors';
import { FLAG_DESCRIPTIONS, FLAG_LABELS } from '../shared/flag-labels';
import type { CourseDto, StaffStudentDto, StudentLearningDto } from '../core/api.models';
import type { StudentDetail } from '../pool/pool.models';
import { barWidth, formatExamDate, formatNumber, formatPercent, rateLevel, sortByRateAsc } from '../exam/exam-detail.logic';
import {
  ATTENDANCE_STATE, POOL_STATUS_TR, attendanceSummary, examOverall, formatDateTime, poolSummary,
} from './course-student.logic';

type Tab = 'attendance' | 'exams' | 'pool';

/** Staff: one student of a course — attendance, exam history and question-pool activity on one page. */
@Component({
  selector: 'app-course-student',
  imports: [RouterLink],
  template: `
    <div class="container-wide">
      <nav class="breadcrumb" aria-label="Konum">
        <a routerLink="/t">{{ msg.nav.courses }}</a>
        › <a [routerLink]="['/t/courses', id()]">{{ course()?.code ?? '…' }}</a>
        › {{ m.title }}
      </nav>
      @if (error()) { <p class="form-error" role="alert">{{ error() }}</p> }

      @if (s(); as s) {
        <header class="profile card">
          <div class="avatar" aria-hidden="true">{{ initials() }}</div>
          <div class="who">
            <h1>{{ s.fullName }}
              @if (!s.active) { <span class="badge badge-warning">{{ m.inactive }}</span> }
            </h1>
            <div class="facts text-sm text-secondary">
              @if (s.studentNo) { <span class="text-mono">{{ s.studentNo }}</span> }
              @if (s.email) { <a [href]="'mailto:' + s.email">{{ s.email }}</a> }
              <span>{{ m.nickname }}: {{ s.nickname }}</span>
              <span>{{ m.lastLogin }}: {{ s.lastLoginAt ? dt(s.lastLoginAt) : m.never }}</span>
            </div>
          </div>
        </header>

        <section class="tiles" [attr.aria-label]="m.summary">
          @if (showAttendance()) {
            <button class="card tile" [class.active]="tab() === 'attendance'" (click)="tab.set('attendance')">
              <span class="tile-label">{{ m.tabs.attendance }}</span>
              <span class="tile-value rate-text" [attr.data-level]="level(att().rate)">{{ pct(att().rate) }}</span>
              <span class="text-sm text-secondary">{{ m.attendanceOf(att().present, att().total) }}@if (att().pending) { · {{ att().pending }} {{ m.pendingShort }} }@if (att().excused) { · {{ att().excused }} {{ m.state.EXCUSED.toLocaleLowerCase('tr-TR') }} }</span>
            </button>
          }
          <button class="card tile" [class.active]="tab() === 'exams'" (click)="tab.set('exams')">
            <span class="tile-label">{{ m.tabs.exams }}</span>
            @if (learning(); as l) {
              <span class="tile-value rate-text" [attr.data-level]="level(examRate())">{{ pct(examRate()) }}</span>
              <span class="text-sm text-secondary">{{ m.examCount(l.exams.length) }}</span>
            } @else {
              <span class="tile-value">—</span><span class="text-sm text-secondary">{{ learningError() || msg.topics.loading }}</span>
            }
          </button>
          @if (course()?.poolEnabled) {
            <button class="card tile" [class.active]="tab() === 'pool'" (click)="tab.set('pool')">
              <span class="tile-label">{{ m.tabs.pool }}</span>
              @if (poolSum(); as p) {
                <span class="tile-value">{{ p.written }} <small class="text-secondary">{{ m.questionsShort }}</small></span>
                <span class="text-sm text-secondary">{{ p.approved }} {{ m.approvedShort }} · {{ m.answered(p.attempts) }} ({{ pct(p.rate) }} {{ m.correctShort }})</span>
              } @else {
                <span class="tile-value">—</span><span class="text-sm text-secondary">{{ poolError() || msg.topics.loading }}</span>
              }
            </button>
          }
        </section>

        <div class="tabs" role="tablist">
          @if (showAttendance()) {
            <button role="tab" [attr.aria-selected]="tab() === 'attendance'" [class.active]="tab() === 'attendance'" (click)="tab.set('attendance')">{{ m.tabs.attendance }}</button>
          }
          <button role="tab" [attr.aria-selected]="tab() === 'exams'" [class.active]="tab() === 'exams'" (click)="tab.set('exams')">{{ m.tabs.exams }}</button>
          @if (course()?.poolEnabled) {
            <button role="tab" [attr.aria-selected]="tab() === 'pool'" [class.active]="tab() === 'pool'" (click)="tab.set('pool')">{{ m.tabs.pool }}</button>
          }
        </div>

        <section role="tabpanel">
          @switch (tab()) {
            @case ('attendance') {
              @if (s.attendance.length === 0) {
                <p class="text-secondary">{{ msg.my.noSessions }}</p>
              } @else {
                <p class="text-sm text-secondary">
                  {{ att().present }} {{ m.state.PRESENT.toLocaleLowerCase('tr-TR') }} ·
                  {{ att().absent }} {{ m.state.ABSENT.toLocaleLowerCase('tr-TR') }}
                  @if (att().pending) { · {{ att().pending }} {{ m.pendingShort }} }
                  @if (att().rejected) { · {{ att().rejected }} {{ m.state.REJECTED.toLocaleLowerCase('tr-TR') }} }
                  @if (att().excused) { · {{ att().excused }} {{ m.state.EXCUSED.toLocaleLowerCase('tr-TR') }} }
                </p>
                <div class="table-wrap"><table>
                  <thead><tr><th>{{ m.date }}</th><th>{{ m.session }}</th><th>{{ m.status }}</th><th>{{ m.source }}</th><th>{{ m.details }}</th></tr></thead>
                  <tbody>
                    @for (a of s.attendance; track a.sessionId) {
                      <tr [class.absent-row]="a.state === 'ABSENT' || a.state === 'REJECTED'">
                        <td class="nowrap">{{ dt(a.date) }}</td>
                        <td><a [routerLink]="['/t/sessions', a.sessionId]">{{ a.title }}</a>
                          @if (a.sessionStatus === 'OPEN') { <span class="badge badge-info">{{ msg.sessions.status.OPEN }}</span> }
                        </td>
                        <td><span class="badge" [class]="'badge ' + stateOf(a.state).badge">{{ stateOf(a.state).label }}</span></td>
                        <td class="text-sm">{{ a.source ? m.sources[a.source] : '—' }}@if (a.checkedInAt) { <div class="text-secondary">{{ time(a.checkedInAt) }}</div> }</td>
                        <td class="text-sm">
                          @for (f of a.flags; track f) { <span class="badge badge-warning flag" [title]="flagDesc[f] ?? ''">{{ flagLabel[f] ?? f }}</span> }
                          @if (a.teacherNote) { <div class="text-secondary note">“{{ a.teacherNote }}”</div> }
                        </td>
                      </tr>
                    }
                  </tbody>
                </table></div>
              }
            }

            @case ('exams') {
              @if (learningError()) { <p class="form-error" role="alert">{{ learningError() }}</p> }
              @if (learning(); as l) {
                <h3>{{ m.examHistory }}</h3>
                @if (l.exams.length === 0) { <p class="text-secondary">{{ m.noExams }}</p> }
                @else {
                  <div class="table-wrap"><table>
                    <thead><tr><th>{{ m.exam }}</th><th>{{ m.date }}</th><th class="num">{{ m.points }}</th><th>{{ m.success }}</th></tr></thead>
                    <tbody>
                      @for (e of l.exams; track e.examId) {
                        <tr>
                          <td><a [routerLink]="['/t/exams', e.examId]">{{ e.title }}</a>@if (!e.complete) { <span class="badge badge-warning">{{ m.incomplete }}</span> }</td>
                          <td class="nowrap">{{ day(e.examDate) }}</td>
                          <td class="num">{{ fmt(e.totalPoints) }} / {{ fmt(e.maxPoints) }}</td>
                          <td>
                            <span class="rate" [attr.data-level]="level(e.percent)">
                              <span class="bar"><span class="fill" [style.width.%]="bw(e.percent)"></span></span>
                              <span class="rate-text">{{ pct(e.percent) }}</span>
                            </span>
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table></div>

                  <h3>{{ m.topics }}</h3>
                  @if (l.topics.length === 0) { <p class="text-secondary text-sm">{{ m.noTopics }}</p> }
                  @else {
                    <div class="table-wrap"><table>
                      <thead><tr><th>{{ m.topic }}</th><th>{{ m.success }}</th><th class="num">{{ m.questions }}</th><th class="num">{{ m.points }}</th></tr></thead>
                      <tbody>
                        @for (t of topicsSorted(); track t.topic) {
                          <tr [class.absent-row]="level(t.successRate) === 'danger'">
                            <td>{{ t.topic }}</td>
                            <td>
                              <span class="rate" [attr.data-level]="level(t.successRate)">
                                <span class="bar"><span class="fill" [style.width.%]="bw(t.successRate)"></span></span>
                                <span class="rate-text">{{ pct(t.successRate) }}</span>
                              </span>
                            </td>
                            <td class="num">{{ t.questionCount }}</td>
                            <td class="num">{{ fmt(t.earnedPoints) }} / {{ fmt(t.maxPoints) }}</td>
                          </tr>
                        }
                      </tbody>
                    </table></div>
                  }

                  <h3>{{ m.missed }} ({{ l.missedQuestions.length }})</h3>
                  @if (l.missedQuestions.length === 0) { <p class="text-secondary text-sm">{{ m.noMissed }}</p> }
                  @else {
                    <div class="table-wrap"><table>
                      <thead><tr><th>{{ m.exam }}</th><th class="num">#</th><th>{{ m.topic }}</th><th>{{ m.text }}</th><th>{{ m.answer }}</th><th>{{ m.key }}</th><th class="num">{{ m.points }}</th></tr></thead>
                      <tbody>
                        @for (q of l.missedQuestions; track q.examId + '-' + q.position) {
                          <tr>
                            <td>{{ q.examTitle }}</td>
                            <td class="num">{{ q.label ?? q.position }}</td>
                            <td>{{ q.topic ?? '—' }}</td>
                            <td class="qtext">{{ q.text ?? '—' }}</td>
                            <td>{{ q.answer || msg.myResult.blank }}</td>
                            <td>{{ q.correctChoice ?? '—' }}</td>
                            <td class="num">{{ fmt(q.points) }} / {{ fmt(q.maxPoints) }}</td>
                          </tr>
                        }
                      </tbody>
                    </table></div>
                  }
                }
              } @else if (!learningError()) {
                <p class="text-secondary">{{ msg.topics.loading }}</p>
              }
            }

            @case ('pool') {
              @if (poolError()) { <p class="form-error" role="alert">{{ poolError() }}</p> }
              @if (pool(); as p) {
                <p class="text-sm"><a [routerLink]="['/courses', id(), 'pool', 'staff', 'students', s.personId]">{{ m.openInPool }}</a></p>

                <h3>{{ m.written }} ({{ p.questions.length }})</h3>
                @if (p.questions.length === 0) { <p class="text-secondary text-sm">{{ m.noWritten }}</p> }
                @else {
                  <div class="table-wrap"><table>
                    <thead><tr><th class="num">#</th><th>{{ m.chapter }}</th><th>{{ m.question }}</th><th>{{ m.status }}</th><th>{{ m.date }}</th></tr></thead>
                    <tbody>
                      @for (q of p.questions; track q.id) {
                        <tr>
                          <td class="num"><a [routerLink]="['/courses', id(), 'pool', 'questions', q.id]">{{ q.id }}</a></td>
                          <td class="num">{{ q.chapter_number }}</td>
                          <td class="qtext"><a [routerLink]="['/courses', id(), 'pool', 'questions', q.id]">{{ q.stem }}</a></td>
                          <td><span class="badge" [class]="'badge ' + (poolStatus[q.status]?.badge ?? '')">{{ poolStatus[q.status]?.label ?? q.status }}</span></td>
                          <td class="nowrap text-sm">{{ dt(q.created_at) }}</td>
                        </tr>
                      }
                    </tbody>
                  </table></div>
                }

                <h3>{{ m.answersByChapter }}</h3>
                @if (p.attempts_by_chapter.length === 0) { <p class="text-secondary text-sm">{{ m.noAnswers }}</p> }
                @else {
                  <div class="table-wrap"><table>
                    <thead><tr><th>{{ m.chapter }}</th><th class="num">{{ m.answeredCol }}</th><th class="num">{{ m.correctCol }}</th><th>{{ m.success }}</th></tr></thead>
                    <tbody>
                      @for (a of p.attempts_by_chapter; track a.chapter_number) {
                        <tr>
                          <td>{{ a.chapter_number }}</td>
                          <td class="num">{{ a.attempts }}</td>
                          <td class="num">{{ a.correct }}</td>
                          <td>
                            <span class="rate" [attr.data-level]="level(ratio(a.correct, a.attempts))">
                              <span class="bar"><span class="fill" [style.width.%]="bw(ratio(a.correct, a.attempts))"></span></span>
                              <span class="rate-text">{{ pct(ratio(a.correct, a.attempts)) }}</span>
                            </span>
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table></div>
                }

                <h3>{{ m.comments }} ({{ p.comments.length }})</h3>
                @if (p.comments.length === 0) { <p class="text-secondary text-sm">{{ m.noComments }}</p> }
                @for (c of p.comments; track c.id) {
                  <div class="card item">
                    <div class="text-sm text-secondary">
                      {{ dt(c.created_at) }} · <a [routerLink]="['/courses', id(), 'pool', 'questions', c.question_id]">#{{ c.question_id }}</a> {{ c.stem }}
                      @if (c.deleted) { <span class="badge badge-danger">{{ m.hidden }}</span> }
                    </div>
                    <div class="pre">{{ c.body }}</div>
                  </div>
                }

                <h3>{{ m.flags }} ({{ p.flags.length }})</h3>
                @if (p.flags.length === 0) { <p class="text-secondary text-sm">{{ m.noFlags }}</p> }
                @for (f of p.flags; track f.id) {
                  <div class="card item">
                    <div class="text-sm text-secondary">{{ dt(f.created_at) }} · <a [routerLink]="['/courses', id(), 'pool', 'questions', f.question_id]">#{{ f.question_id }}</a></div>
                    <div class="pre">{{ f.reason }}</div>
                  </div>
                }
              } @else if (!poolError()) {
                <p class="text-secondary">{{ msg.topics.loading }}</p>
              }
            }
          }
        </section>
      } @else if (!error()) {
        <p class="text-secondary">{{ msg.topics.loading }}</p>
      }
    </div>
  `,
  styles: [`
    .profile { display: flex; gap: var(--space-md); align-items: center; margin-bottom: var(--space-md); }
    .profile h1 { margin: 0 0 4px; display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
    .avatar { flex: 0 0 56px; height: 56px; border-radius: 50%; display: grid; place-items: center; font-weight: 700; font-size: 1.2rem;
      background: var(--c-primary-light); color: var(--c-primary); }
    .facts { display: flex; flex-wrap: wrap; gap: 4px 16px; }
    .tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: var(--space-md); margin-bottom: var(--space-md); }
    .tile { display: flex; flex-direction: column; gap: 4px; text-align: left; cursor: pointer; font: inherit; color: inherit; }
    .tile:hover, .tile.active { border-color: var(--c-primary); }
    .tile-label { font-size: .8rem; text-transform: uppercase; letter-spacing: .04em; color: var(--c-text-secondary); }
    .tile-value { font-size: 1.8rem; font-weight: 700; line-height: 1.1; }
    .tile-value small { font-size: .9rem; font-weight: 400; }
    .tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--c-border); margin-bottom: var(--space-md); overflow-x: auto; }
    .tabs button { background: none; border: none; border-bottom: 2px solid transparent; padding: 8px 14px; cursor: pointer; font: inherit;
      color: var(--c-text-secondary); white-space: nowrap; }
    .tabs button.active { color: var(--c-primary); border-bottom-color: var(--c-primary); font-weight: 600; }
    h3 { margin: var(--space-md) 0 var(--space-sm); font-size: 1rem; }
    .table-wrap { overflow-x: auto; border: 1px solid var(--c-border); border-radius: var(--radius-md); background: var(--c-surface); margin-bottom: var(--space-md); }
    .table-wrap table { min-width: 520px; }
    .num { text-align: right; white-space: nowrap; }
    .nowrap { white-space: nowrap; }
    .qtext { max-width: 360px; min-width: 140px; overflow-wrap: anywhere; }
    .absent-row { background: var(--c-danger-light); }
    .flag { margin: 0 4px 2px 0; text-transform: none; }
    .note { font-style: italic; }
    .item { margin-bottom: var(--space-sm); }
    .pre { white-space: pre-wrap; overflow-wrap: anywhere; margin-top: 4px; }
    .rate { display: inline-flex; align-items: center; gap: 8px; min-width: 150px; }
    .bar { display: inline-block; flex: 1; height: 10px; background: var(--c-border); border-radius: 999px; overflow: hidden; min-width: 70px; }
    .fill { display: block; height: 100%; border-radius: 999px; background: var(--c-text-secondary); }
    [data-level='danger'] .fill { background: var(--c-danger); }
    [data-level='warning'] .fill { background: var(--c-warning); }
    [data-level='success'] .fill { background: var(--c-success); }
    .rate-text { font-weight: 600; white-space: nowrap; min-width: 3em; }
    .rate-text[data-level='danger'], [data-level='danger'] .rate-text { color: var(--c-danger); }
    .rate-text[data-level='success'], [data-level='success'] .rate-text { color: var(--c-success); }
  `],
})
export class CourseStudentComponent {
  private readonly api = inject(ApiService);
  private readonly poolApi = inject(PoolApiService);
  readonly msg = MSG;
  readonly m = MSG.courseStudent;
  readonly flagLabel = FLAG_LABELS;
  readonly flagDesc = FLAG_DESCRIPTIONS;
  readonly poolStatus = POOL_STATUS_TR as Record<string, { label: string; badge: string }>;

  readonly id = input.required<string>();
  readonly personId = input.required<string>();

  readonly course = signal<CourseDto | null>(null);
  readonly s = signal<StaffStudentDto | null>(null);
  readonly learning = signal<StudentLearningDto | null>(null);
  readonly pool = signal<StudentDetail | null>(null);
  readonly error = signal('');
  readonly learningError = signal('');
  readonly poolError = signal('');
  readonly tab = signal<Tab>('attendance');

  readonly showAttendance = computed(() => this.course()?.attendanceEnabled !== false || (this.s()?.attendance.length ?? 0) > 0);
  readonly att = computed(() => attendanceSummary(this.s()?.attendance ?? []));
  readonly examRate = computed(() => examOverall(this.learning()?.exams ?? []));
  readonly poolSum = computed(() => { const p = this.pool(); return p ? poolSummary(p) : null; });
  readonly topicsSorted = computed(() => sortByRateAsc(this.learning()?.topics ?? [], t => t.successRate));
  readonly initials = computed(() => (this.s()?.fullName ?? '').split(/\s+/).filter(Boolean)
    .map(w => w[0]).slice(0, 2).join('').toLocaleUpperCase('tr-TR'));

  readonly level = rateLevel;
  readonly pct = formatPercent;
  readonly fmt = formatNumber;
  readonly bw = barWidth;
  readonly day = formatExamDate;
  readonly dt = formatDateTime;

  constructor() {
    effect(() => {
      const courseId = this.id();
      const personId = this.personId();
      untracked(() => this.load(courseId, personId));
    });
  }

  stateOf(state: keyof typeof ATTENDANCE_STATE) { return ATTENDANCE_STATE[state]; }

  ratio(a: number, b: number): number | null { return Number(b) ? Number(a) / Number(b) : null; }

  time(iso: string) {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? '' : d.toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' });
  }

  private async load(courseId: string, personId: string) {
    this.error.set(''); this.learningError.set(''); this.poolError.set('');
    this.s.set(null); this.learning.set(null); this.pool.set(null);
    let course: CourseDto;
    let student: StaffStudentDto;
    try {
      [course, student] = await Promise.all([this.api.getCourse(courseId), this.api.getCourseStudent(courseId, personId)]);
    } catch (e) {
      this.error.set(errorMessage(e));
      return;
    }
    this.course.set(course);
    this.s.set(student);
    if (!this.showAttendance()) this.tab.set('exams');

    const tasks: Promise<void>[] = [];
    if (student.studentNo) {
      tasks.push(this.api.getStudentLearning(courseId, student.studentNo)
        .then(l => this.learning.set(l))
        .catch(e => this.learningError.set(errorMessage(e))));
    } else {
      this.learning.set({ courseId: course.id, studentNo: '', fullName: student.fullName, exams: [], topics: [], missedQuestions: [] });
    }
    if (course.poolEnabled) {
      tasks.push(this.poolApi.student(courseId, personId)
        .then(p => this.pool.set(p))
        .catch(e => this.poolError.set(errorMessage(e))));
    }
    await Promise.all(tasks);
  }
}
