import { Component, inject, signal, OnInit } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../core/auth.service';
import { MSG } from '../shared/messages';
import { errorMessage } from '../shared/errors';

/** One login page for everybody: e-mail magic link (primary) and staff username + password. */
@Component({
  selector: 'app-login',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="login-page">
      <div class="login-wrap">
        <h1>{{ msg.appTitle }}</h1>
        <p class="subtitle">{{ msg.appSubtitle }}</p>

        <div class="card login-card">
          <h2>{{ msg.login.emailTitle }}</h2>
          <form (ngSubmit)="sendLink()">
            <div class="form-group">
              <label for="email">{{ msg.login.email }}</label>
              <input id="email" class="form-input" type="email" [(ngModel)]="email" name="email" autocomplete="email" autofocus required />
            </div>
            @if (linkInfo()) { <p class="form-info" role="status">{{ linkInfo() }}</p> }
            @if (linkError()) { <p class="form-error" role="alert">{{ linkError() }}</p> }
            <button class="btn btn-primary" style="width:100%" [disabled]="sending() || !email.trim()">{{ msg.login.sendLink }}</button>
          </form>
        </div>

        <details class="card login-card" [open]="staffOpen">
          <summary>{{ msg.login.staffTitle }}</summary>
          <form (ngSubmit)="doLogin()" class="mt-md">
            <div class="form-group">
              <label for="username">{{ msg.login.username }}</label>
              <input id="username" class="form-input" [(ngModel)]="username" name="username" autocomplete="username" />
            </div>
            <div class="form-group">
              <label for="password">{{ msg.login.password }}</label>
              <input id="password" class="form-input" type="password" [(ngModel)]="password" name="password" autocomplete="current-password" />
            </div>
            @if (error()) { <p class="form-error" role="alert">{{ error() }}</p> }
            <button class="btn btn-outline" style="width:100%" [disabled]="loading() || !username.trim() || !password">{{ msg.login.submit }}</button>
          </form>
        </details>

        <a routerLink="/" class="link">{{ msg.login.checkin }}</a>
      </div>
    </div>
  `,
  styles: [`
    .login-page { display: flex; align-items: center; justify-content: center; min-height: 100vh; min-height: 100dvh; padding: 1rem; }
    .login-wrap { width: 100%; max-width: 400px; display: flex; flex-direction: column; gap: var(--space-md); text-align: center; }
    h1 { margin: 0; font-size: 2rem; }
    .subtitle { margin: 0; color: var(--c-text-secondary); }
    .login-card { text-align: left; }
    h2 { margin: 0 0 var(--space-md); font-size: 1.1rem; }
    summary { cursor: pointer; font-weight: 600; }
    .form-info { color: var(--c-success); font-size: 0.9rem; }
    .link { color: var(--c-primary); }
  `],
})
export class LoginComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly msg = MSG;
  email = '';
  username = '';
  password = '';
  staffOpen = false;
  readonly error = signal('');
  readonly loading = signal(false);
  readonly sending = signal(false);
  readonly linkInfo = signal('');
  readonly linkError = signal('');

  async ngOnInit() {
    this.staffOpen = this.route.snapshot.queryParamMap.get('staff') === '1';
    await this.auth.ensureInit();
    if (this.auth.user()) this.go();
  }

  async sendLink() {
    this.linkInfo.set('');
    this.linkError.set('');
    this.sending.set(true);
    try {
      const onRoster = await this.auth.requestLink(this.email.trim());
      if (onRoster) this.linkInfo.set(this.msg.login.linkSent);
      else this.linkError.set(this.msg.login.notOnRoster);
    } catch (e) {
      this.linkError.set(errorMessage(e));
    } finally {
      this.sending.set(false);
    }
  }

  async doLogin() {
    this.error.set('');
    this.loading.set(true);
    try {
      await this.auth.login(this.username.trim(), this.password);
      this.go();
    } catch (e: unknown) {
      const code = (e as { code?: string })?.code;
      this.error.set(code === 'UNAUTHORIZED' || code === 'VALIDATION_ERROR' ? this.msg.login.error : errorMessage(e));
    } finally {
      this.loading.set(false);
    }
  }

  private go() {
    const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
    // Only same-app paths: never an absolute URL from the query string.
    const safe = returnUrl && returnUrl.startsWith('/') && !returnUrl.startsWith('//') ? returnUrl : null;
    this.router.navigateByUrl(safe ?? this.auth.homeUrl());
  }
}
