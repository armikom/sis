import { Component, inject, signal, OnInit } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { MSG } from '../shared/messages';

/** Target of the mailed link: /login/verify?token=… — exchanges the token for a session cookie. */
@Component({
  selector: 'app-login-verify',
  imports: [RouterLink],
  template: `
    <div class="verify-page">
      <div class="card verify-card">
        @if (failed()) {
          <p class="form-error" role="alert">{{ msg.login.verifyFailed }}</p>
          <a routerLink="/login" class="btn btn-primary">{{ msg.login.newLink }}</a>
        } @else {
          <p role="status">{{ msg.login.verifying }}</p>
        }
      </div>
    </div>
  `,
  styles: [`
    .verify-page { display: flex; align-items: center; justify-content: center; min-height: 100vh; padding: 1rem; }
    .verify-card { width: 100%; max-width: 380px; text-align: center; }
  `],
})
export class VerifyComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly msg = MSG;
  readonly failed = signal(false);

  async ngOnInit() {
    const token = this.route.snapshot.queryParamMap.get('token');
    if (!token) { this.failed.set(true); return; }
    try {
      await this.auth.verify(token);
      // Drop the token from the address bar and history.
      this.router.navigateByUrl(this.auth.homeUrl(), { replaceUrl: true });
    } catch {
      this.failed.set(true);
    }
  }
}
