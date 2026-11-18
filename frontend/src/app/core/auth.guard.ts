import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';
import { AuthService } from './auth.service';

/** Any signed-in person; otherwise /login?returnUrl=… */
export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  await auth.ensureInit();
  if (auth.user()) return true;
  return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

/** Staff of at least one course, or a system admin (the /t area). Students go to /courses. */
export const staffGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  await auth.ensureInit();
  if (!auth.user()) return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
  return auth.isStaff() ? true : router.parseUrl('/courses');
};

/** System administrators only. */
export const adminGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  await auth.ensureInit();
  if (auth.isAdmin()) return true;
  return router.parseUrl(auth.user() ? auth.homeUrl() : '/login');
};
