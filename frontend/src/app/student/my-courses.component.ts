import { Component, inject, computed, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { MSG } from '../shared/messages';

/** "My courses": the courses of the signed-in person, from /api/auth/me. */
@Component({
  selector: 'app-my-courses',
  imports: [RouterLink],
  template: `
    <div class="container">
      <div class="page-header"><h1>{{ msg.my.title }}</h1></div>
      @if (courses().length === 0) {
        <p class="text-secondary">{{ msg.my.noCourses }}</p>
      }
      @for (c of courses(); track c.id) {
        <a class="card mb-md course-card" [routerLink]="['/courses', c.id]">
          <div>
            <strong>{{ c.code }}</strong> — {{ c.name }}
            <div class="text-sm text-secondary">{{ c.term }}@if (c.archived) { · {{ msg.settings.archived }} }</div>
          </div>
          @if (c.role !== 'STUDENT') { <span class="badge badge-info">{{ msg.staff.roles[c.role] }}</span> }
        </a>
      }
    </div>
  `,
  styles: [`
    .course-card { display: flex; justify-content: space-between; align-items: center; gap: 8px; text-decoration: none; color: inherit; }
    .course-card:hover { text-decoration: none; border-color: var(--c-primary); }
  `],
})
export class MyCoursesComponent implements OnInit {
  private readonly auth = inject(AuthService);
  readonly msg = MSG;
  readonly courses = computed(() => this.auth.user()?.courses ?? []);

  async ngOnInit() {
    await this.auth.refresh();
  }
}
