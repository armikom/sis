import { Component, effect, inject, input, untracked } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { MSG } from '../shared/messages';
import { PoolContext } from './pool-context.service';
import { PM } from './pool-messages';

/**
 * Frame of the question pool of one course (/courses/:courseId/pool…), for students and staff alike: staff of the
 * course get the instructor entries. Styles: src/styles/_pool.scss (scoped under .pool-app).
 */
@Component({
  selector: 'app-pool-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="nav-shell pool-app">
      <header class="nav-header pool-topbar">
        <a [routerLink]="ctx.base()" class="nav-title">{{ msg.appTitle }} <span class="pool-brand">{{ ctx.courseLabel() }} {{ pm.appName }}</span></a>
        <nav class="nav-links" aria-label="Question pool">
          <a [routerLink]="ctx.base()" routerLinkActive="active" [routerLinkActiveOptions]="exact" ariaCurrentWhenActive="page">{{ pm.nav.chapters }}</a>
          @if (ctx.me(); as me) {
            @if (!me.staff) {
              <a [routerLink]="[ctx.base(), 'mine']" routerLinkActive="active" ariaCurrentWhenActive="page">{{ pm.nav.mine }}</a>
            } @else {
              <a [routerLink]="[ctx.base(), 'staff', 'review']" routerLinkActive="active" ariaCurrentWhenActive="page">{{ pm.nav.review }}</a>
              <a [routerLink]="[ctx.base(), 'staff', 'students']" routerLinkActive="active" ariaCurrentWhenActive="page">{{ pm.nav.students }}</a>
              <a [routerLink]="[ctx.base(), 'staff', 'flags']" routerLinkActive="active" ariaCurrentWhenActive="page">{{ pm.nav.flags }}</a>
              <a [routerLink]="[ctx.base(), 'staff', 'exams']" routerLinkActive="active" ariaCurrentWhenActive="page">{{ pm.nav.exams }}</a>
              <a [routerLink]="[ctx.base(), 'staff', 'chapters']" routerLinkActive="active" ariaCurrentWhenActive="page">{{ pm.nav.chapterTexts }}</a>
            }
          }
          <a routerLink="/agent" [title]="pm.nav.agentTitle">{{ pm.nav.agent }}</a>
          <a [routerLink]="courseUrl()">{{ pm.nav.backToCourse }}</a>
          @if (ctx.me(); as me) { <span class="nav-user" [title]="pm.nav.nicknameTitle">{{ me.nickname }}</span> }
          <a href="#" (click)="doLogout($event)">{{ pm.nav.signOut }}</a>
        </nav>
      </header>
      <main class="nav-content pool-main">
        @if (ctx.me()) {
          <router-outlet />
        } @else if (ctx.error()) {
          <p class="error" role="alert">{{ ctx.error() }}</p>
          <p><a [routerLink]="auth.homeUrl()">{{ pm.nav.home }}</a></p>
        } @else {
          <p class="muted">{{ pm.loading }}</p>
        }
      </main>
    </div>
  `,
})
export class PoolShellComponent {
  readonly auth = inject(AuthService);
  readonly ctx = inject(PoolContext);
  readonly msg = MSG;
  readonly pm = PM;
  readonly courseId = input.required<string>();
  readonly exact = { paths: 'exact', queryParams: 'ignored', matrixParams: 'ignored', fragment: 'ignored' } as const;

  constructor() {
    effect(() => {
      const id = Number(this.courseId());
      untracked(() => this.ctx.load(id));
    });
  }

  /** Staff go back to the course management page, students to their course page. */
  courseUrl(): string {
    const id = this.ctx.courseId();
    return this.ctx.staff() ? `/t/courses/${id}` : `/courses/${id}`;
  }

  doLogout(e: Event) {
    e.preventDefault();
    this.auth.logout();
  }
}
