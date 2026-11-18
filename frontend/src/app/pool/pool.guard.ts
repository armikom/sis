import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { PoolContext } from './pool-context.service';

/**
 * Pool screens of a course: signed in, and the course is one of mine with the pool enabled. System admins pass
 * (they are not necessarily enrolled; the server checks). Otherwise → my home page.
 */
export const poolMemberGuard: CanActivateFn = async (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  await auth.ensureInit();
  const user = auth.user();
  if (!user) return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
  if (auth.isAdmin()) return true;
  const courseId = Number(route.paramMap.get('courseId'));
  const course = user.courses.find(c => c.id === courseId);
  return course?.poolEnabled ? true : router.parseUrl(auth.homeUrl());
};

/** Staff screens of the pool: staff of this course (from GET …/me); others go to the pool home. */
export const poolStaffGuard: CanActivateFn = async route => {
  const ctx = inject(PoolContext);
  const router = inject(Router);
  const courseId = Number(route.parent?.paramMap.get('courseId'));
  await ctx.load(courseId);
  return ctx.staff() ? true : router.parseUrl(`/courses/${courseId}/pool`);
};
