import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { MSG } from '../shared/messages';
import { PM } from '../pool/pool-messages';

/** Frame of the AI-agent (MCP) pages, which are not tied to one course: /agent, /agent/help. */
@Component({
  selector: 'app-agent-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="nav-shell pool-app">
      <header class="nav-header">
        <a [routerLink]="auth.homeUrl()" class="nav-title">{{ msg.appTitle }}</a>
        <nav class="nav-links" aria-label="Ana menü">
          <a [routerLink]="auth.homeUrl()">{{ auth.isStaff() ? msg.nav.courses : msg.nav.myCourses }}</a>
          <a routerLink="/agent" routerLinkActive="active" [routerLinkActiveOptions]="exact" ariaCurrentWhenActive="page">{{ pm.nav.agent }}</a>
          <a routerLink="/agent/help" routerLinkActive="active" ariaCurrentWhenActive="page">Guide / Rehber</a>
          @if (auth.user(); as u) { <span class="nav-user" [title]="pm.nav.nicknameTitle">{{ u.nickname || u.fullName }}</span> }
          <a href="#" (click)="doLogout($event)">{{ msg.nav.logout }}</a>
        </nav>
      </header>
      <main class="nav-content pool-main">
        <router-outlet />
      </main>
    </div>
  `,
})
export class AgentShellComponent {
  readonly auth = inject(AuthService);
  readonly msg = MSG;
  readonly pm = PM;
  readonly exact = { paths: 'exact', queryParams: 'ignored', matrixParams: 'ignored', fragment: 'ignored' } as const;

  doLogout(e: Event) {
    e.preventDefault();
    this.auth.logout();
  }
}
