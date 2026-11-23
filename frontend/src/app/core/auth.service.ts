import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { ApiService } from './api.service';
import type { EnrollmentRole, MeDto } from './api.models';
import { EXAM_DRAFT_PREFIX, removeByPrefix } from './storage';

/**
 * The signed-in person, from GET /api/auth/me (person + courses with my role). One session mechanism for
 * everybody: an HttpOnly `sis_session` cookie; mutating requests carry the XSRF-TOKEN cookie as X-XSRF-TOKEN.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  readonly user = signal<MeDto | null>(null);
  readonly loading = signal(true);
  private initPromise: Promise<void> | null = null;

  readonly isSignedIn = computed(() => this.user() !== null);
  readonly isAdmin = computed(() => this.user()?.systemAdmin === true);
  /** Staff of at least one course, or a system admin. */
  readonly isStaff = computed(() => this.user()?.staff === true);

  /** Runs once per page load: makes sure the XSRF cookie exists (06 §2) and loads the current user. */
  ensureInit(): Promise<void> {
    this.initPromise ??= this.init();
    return this.initPromise;
  }

  private async init(): Promise<void> {
    try {
      await this.api.csrf();
    } catch { /* ignore: login retries it */ }
    await this.refresh();
  }

  /** Reloads /api/auth/me (e.g. after creating a course or being added as staff). */
  async refresh(): Promise<void> {
    try {
      this.user.set(await this.api.me());
    } catch {
      this.user.set(null);
    } finally {
      this.loading.set(false);
    }
  }

  async login(username: string, password: string): Promise<MeDto> {
    await this.api.csrf();
    return this.signedIn(await this.api.login(username, password));
  }

  /** Asks for a sign-in link by e-mail; `onRoster` tells whether the address may sign in. */
  async requestLink(email: string): Promise<boolean> {
    await this.api.csrf();
    return (await this.api.requestLink(email)).onRoster;
  }

  /** Completes a mailed link (/login/verify?token=…). */
  async verify(token: string): Promise<MeDto> {
    await this.api.csrf();
    return this.signedIn(await this.api.verifyLink(token));
  }

  async logout(): Promise<void> {
    try { await this.api.logout(); } catch { /* ignore */ }
    this.user.set(null);
    removeByPrefix(EXAM_DRAFT_PREFIX);   // shared computers: unsaved answer sheets stay with the person
    this.router.navigateByUrl('/login');
  }

  /** Where a person lands after signing in: staff → /t, students → /courses. */
  homeUrl(): string {
    return this.isStaff() ? '/t' : '/courses';
  }

  /** My role in a course, or null (system admins are not necessarily enrolled). */
  roleIn(courseId: number): EnrollmentRole | null {
    return this.user()?.courses.find(c => c.id === courseId)?.role ?? null;
  }

  private signedIn(me: MeDto): MeDto {
    this.user.set(me);
    this.loading.set(false);
    this.initPromise = Promise.resolve();
    return me;
  }
}
