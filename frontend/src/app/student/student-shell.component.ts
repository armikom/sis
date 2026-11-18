import { Component, inject } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { MSG } from '../shared/messages';

/** Frame of the signed-in student area (/courses…). */
@Component({
  selector: 'app-student-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="nav-shell">
      <header class="nav-header">
        <a routerLink="/courses" class="nav-title">{{ msg.appTitle }}</a>
        <nav class="nav-links" aria-label="Ana menü">
          <a routerLink="/courses" routerLinkActive="active" [routerLinkActiveOptions]="exact" ariaCurrentWhenActive="page">{{ msg.nav.myCourses }}</a>
          @if (auth.isStaff()) { <a routerLink="/t">{{ msg.nav.staffArea }}</a> }
          <a routerLink="/agent" routerLinkActive="active" ariaCurrentWhenActive="page">{{ msg.nav.agent }}</a>
          @if (auth.user(); as u) { <span class="nav-user" [title]="u.email ?? ''">{{ u.fullName }}</span> }
          <a href="#" (click)="doLogout($event)">{{ msg.nav.logout }}</a>
        </nav>
      </header>
      <main class="nav-content">
        <router-outlet />
      </main>
    </div>
  `,
})
export class StudentShellComponent {
  readonly auth = inject(AuthService);
  readonly msg = MSG;
  readonly exact = { paths: 'exact', queryParams: 'ignored', matrixParams: 'ignored', fragment: 'ignored' } as const;

  doLogout(e: Event) {
    e.preventDefault();
    this.auth.logout();
  }
}
