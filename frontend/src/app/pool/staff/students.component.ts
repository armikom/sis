import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PoolApiService } from '../pool-api.service';
import { PoolContext } from '../pool-context.service';
import { PM } from '../pool-messages';
import { when } from '../pool-util';
import type { StudentRow } from '../pool.models';
import { errorMessage } from '../../shared/errors';

/** Staff: participation table. The class list itself is managed on the SIS course page. */
@Component({
  selector: 'app-pool-students',
  imports: [RouterLink],
  template: `
    <h1>{{ pm.title }}</h1>
    <p class="small"><a [routerLink]="['/t/courses', ctx.courseId()]">{{ pm.rosterLink }}</a></p>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    <div class="scroll card">
      <table>
        <thead>
          <tr>
            <th>{{ pm.student }}</th><th>{{ pm.no }}</th><th>{{ pm.nickname }}</th>
            <th [title]="pm.questionsTitle">{{ pm.questions }}</th><th [title]="pm.chaptersTitle">{{ pm.chapters }}</th>
            <th [title]="pm.answersTitle">{{ pm.answers }}</th><th>{{ pm.comments }}</th><th>{{ pm.flags }}</th><th>{{ pm.lastLogin }}</th>
          </tr>
        </thead>
        <tbody>
          @for (s of students(); track s.id) {
            <tr>
              <td><a [routerLink]="[ctx.base(), 'staff', 'students', s.id]">{{ s.full_name }}</a></td>
              <td>{{ s.student_no }}</td>
              <td>{{ s.nickname }}</td>
              <td>{{ s.questions }} / {{ s.approved }}</td>
              <td>{{ s.chapters }}</td>
              <td>{{ s.attempts }} / {{ s.correct }}</td>
              <td>{{ s.comments }}</td>
              <td>{{ s.flags }}</td>
              <td>{{ s.last_login_at ? when(s.last_login_at) : pm.never }}</td>
            </tr>
          }
        </tbody>
      </table>
      @if (loaded() && !students().length) { <p class="muted">{{ pm.empty }}</p> }
    </div>
  `,
})
export class StudentsComponent implements OnInit {
  private readonly api = inject(PoolApiService);
  readonly ctx = inject(PoolContext);
  readonly pm = PM.students;
  readonly when = when;
  readonly students = signal<StudentRow[]>([]);
  readonly loaded = signal(false);
  readonly error = signal('');

  async ngOnInit() {
    try {
      this.students.set(await this.api.students(this.ctx.courseId()));
    } catch (e) {
      this.error.set(errorMessage(e));
    }
    this.loaded.set(true);
  }
}
