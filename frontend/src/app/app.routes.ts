import { Routes } from '@angular/router';
import { HomeComponent } from './student/home.component';
import { authGuard, adminGuard, staffGuard } from './core/auth.guard';
import { POOL_ROUTES } from './pool/pool.routes';

/*
 * Adding a route: staff screens go under 't' (staffGuard; menu in teacher/teacher-shell.component.ts),
 * signed-in student screens under 'courses' (authGuard; menu in student/student-shell.component.ts),
 * system-admin screens under 't/admin' with adminGuard. Question-pool screens: pool/pool.routes.ts. Check-in ('', 'c/:code', 'q/:token') stays public.
 */
export const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'c/:code', loadComponent: () => import('./student/checkin-flow.component').then(m => m.CheckinFlowComponent) },
  { path: 'q/:token', loadComponent: () => import('./shared/qr-redirect.component').then(m => m.QrRedirectComponent) },
  { path: 'login', loadComponent: () => import('./auth/login.component').then(m => m.LoginComponent) },
  { path: 'login/verify', loadComponent: () => import('./auth/verify.component').then(m => m.VerifyComponent) },
  { path: 't/login', redirectTo: 'login', pathMatch: 'full' },
  // Question pool (/courses/:courseId/pool…): own layout for students and staff, so it sits before 'courses'.
  ...POOL_ROUTES,
  {
    path: 'agent',
    loadComponent: () => import('./agent/agent-shell.component').then(m => m.AgentShellComponent),
    canActivate: [authGuard],
    children: [
      { path: '', loadComponent: () => import('./agent/agent.component').then(m => m.AgentComponent) },
      { path: 'help', loadComponent: () => import('./agent/agent-help.component').then(m => m.AgentHelpComponent) },
    ],
  },
  {
    path: 'courses',
    loadComponent: () => import('./student/student-shell.component').then(m => m.StudentShellComponent),
    canActivate: [authGuard],
    children: [
      { path: '', loadComponent: () => import('./student/my-courses.component').then(m => m.MyCoursesComponent) },
      { path: ':id', loadComponent: () => import('./student/my-course.component').then(m => m.MyCourseComponent) },
    ],
  },
  // Projection view: full screen, no teacher shell/menu (06 §3).
  {
    path: 't/sessions/:id/display',
    canActivate: [staffGuard],
    loadComponent: () => import('./teacher/display.component').then(m => m.DisplayComponent),
  },
  {
    path: 't',
    loadComponent: () => import('./teacher/teacher-shell.component').then(m => m.TeacherShellComponent),
    canActivate: [staffGuard],
    children: [
      { path: '', loadComponent: () => import('./teacher/courses.component').then(m => m.CoursesComponent) },
      { path: 'courses/:id', loadComponent: () => import('./teacher/course-detail.component').then(m => m.CourseDetailComponent) },
      { path: 'courses/:id/learning', loadComponent: () => import('./exam/course-learning.component').then(m => m.CourseLearningComponent) },
      { path: 'exams/new', loadComponent: () => import('./exam/exam-edit.component').then(m => m.ExamEditComponent) },
      { path: 'exams/:id', loadComponent: () => import('./exam/exam-detail.component').then(m => m.ExamDetailComponent) },
      { path: 'exams/:id/edit', loadComponent: () => import('./exam/exam-edit.component').then(m => m.ExamEditComponent) },
      { path: 'exams/:id/entry', loadComponent: () => import('./exam/exam-entry.component').then(m => m.ExamEntryComponent) },
      { path: 'classrooms', loadComponent: () => import('./teacher/classrooms.component').then(m => m.ClassroomsComponent) },
      { path: 'sessions/new', loadComponent: () => import('./teacher/session-create.component').then(m => m.SessionCreateComponent) },
      { path: 'sessions/:id', loadComponent: () => import('./teacher/session-dashboard.component').then(m => m.SessionDashboardComponent) },
      { path: 'sessions/:id/calibrate', loadComponent: () => import('./teacher/calibrate.component').then(m => m.CalibrateComponent) },
      { path: 'admin/people', canActivate: [adminGuard], loadComponent: () => import('./teacher/admin-people.component').then(m => m.AdminPeopleComponent) },
      { path: 'admin/teachers', redirectTo: 'admin/people', pathMatch: 'full' },
    ],
  },
  { path: '**', loadComponent: () => import('./shared/not-found.component').then(m => m.NotFoundComponent) },
];
