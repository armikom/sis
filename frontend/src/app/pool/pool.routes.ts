import { Routes } from '@angular/router';
import { poolMemberGuard, poolStaffGuard } from './pool.guard';
import { PoolContext } from './pool-context.service';

/**
 * Question pool of one course: /courses/:courseId/pool… One layout for students and staff; the staff screens check
 * `me.staff` in the shell menu and the server enforces it (requireStaff) on every /staff/ endpoint.
 */
export const POOL_ROUTES: Routes = [
  // Print view: no shell/menu.
  {
    path: 'courses/:courseId/pool/staff/exams/:id/print/:form',
    canActivate: [poolMemberGuard],
    loadComponent: () => import('./staff/exam-print.component').then(m => m.ExamPrintComponent),
  },
  {
    path: 'courses/:courseId/pool',
    canActivate: [poolMemberGuard],
    providers: [PoolContext],
    loadComponent: () => import('./pool-shell.component').then(m => m.PoolShellComponent),
    children: [
      { path: '', loadComponent: () => import('./chapters-home.component').then(m => m.ChaptersHomeComponent) },
      { path: 'chapters/:id', loadComponent: () => import('./chapter.component').then(m => m.ChapterComponent) },
      { path: 'chapters/:chapterId/new', loadComponent: () => import('./question-form.component').then(m => m.QuestionFormComponent) },
      { path: 'questions/:id', loadComponent: () => import('./question-view.component').then(m => m.QuestionViewComponent) },
      { path: 'questions/:id/edit', loadComponent: () => import('./question-form.component').then(m => m.QuestionFormComponent) },
      { path: 'mine', loadComponent: () => import('./mine.component').then(m => m.MineComponent) },
      { path: 'staff', redirectTo: 'staff/review', pathMatch: 'full' },
      { path: 'staff/review', canActivate: [poolStaffGuard], loadComponent: () => import('./staff/review.component').then(m => m.ReviewComponent) },
      { path: 'staff/students', canActivate: [poolStaffGuard], loadComponent: () => import('./staff/students.component').then(m => m.StudentsComponent) },
      { path: 'staff/students/:id', canActivate: [poolStaffGuard], loadComponent: () => import('./staff/student-detail.component').then(m => m.StudentDetailComponent) },
      { path: 'staff/chapters', canActivate: [poolStaffGuard], loadComponent: () => import('./staff/chapters.component').then(m => m.ChaptersAdminComponent) },
      { path: 'staff/flags', canActivate: [poolStaffGuard], loadComponent: () => import('./staff/flags.component').then(m => m.FlagsComponent) },
      { path: 'staff/exams', canActivate: [poolStaffGuard], loadComponent: () => import('./staff/exams.component').then(m => m.ExamsComponent) },
      { path: 'staff/exams/:id', canActivate: [poolStaffGuard], loadComponent: () => import('./staff/exam-detail.component').then(m => m.ExamDetailComponent) },
      { path: '**', redirectTo: '' },
    ],
  },
];
