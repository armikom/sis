import { Component, inject, signal, input, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { MSG } from '../shared/messages';
import { errorMessage } from '../shared/errors';
import type { CourseDto, MyResultDto } from '../core/api.models';

/** A course page for a student: my exam results (attendance is staff only); link to the question pool when enabled. */
@Component({
  selector: 'app-my-course',
  imports: [RouterLink],
  template: `
    <div class="container">
      <nav class="breadcrumb" aria-label="Konum"><a routerLink="/courses">{{ msg.my.title }}</a> › {{ course()?.code }}</nav>
      <div class="page-header">
        <h1>{{ course() ? course()!.code + ' — ' + course()!.name : '' }}</h1>
        @if (course()?.poolEnabled) {
          <a class="btn btn-primary" [routerLink]="['/courses', id(), 'pool']">{{ msg.my.pool }}</a>
        }
      </div>
      @if (error()) { <p class="form-error" role="alert">{{ error() }}</p> }

      <h2>{{ msg.my.results }}</h2>
      @if (results().length === 0) {
        <p class="text-secondary">{{ msg.my.noResults }}</p>
      } @else {
        <div class="table-wrap"><table>
          <tbody>
            @for (r of results(); track r.examId) {
              <tr>
                <td>{{ r.examDate ? formatDay(r.examDate) : '' }}</td>
                <td><a [routerLink]="['/courses', id(), 'results', r.examId]">{{ r.title }}</a></td>
                <td style="text-align:right"><strong>{{ r.points }}</strong> / {{ r.maxPoints }}</td>
                <td style="text-align:right"><a class="btn btn-outline btn-sm" [routerLink]="['/courses', id(), 'results', r.examId]">{{ msg.myResult.detail }}</a></td>
              </tr>
            }
          </tbody>
        </table></div>
      }
    </div>
  `,
  styles: [`.table-wrap { overflow-x: auto; }`],
})
export class MyCourseComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly msg = MSG;
  readonly id = input.required<string>();
  readonly course = signal<CourseDto | null>(null);
  readonly results = signal<MyResultDto[]>([]);
  readonly error = signal('');

  async ngOnInit() {
    try {
      const [c, r] = await Promise.all([
        this.api.getCourse(this.id()),
        this.api.getMyResults(this.id()),
      ]);
      this.course.set(c);
      this.results.set(r);
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  formatDay(d: string) {
    try { return new Date(d + 'T00:00:00').toLocaleDateString('tr-TR', { dateStyle: 'medium' }); }
    catch { return d; }
  }
}
