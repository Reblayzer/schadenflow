import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { roleGuard } from './core/auth/role.guard';
import { Role } from './core/models/claim.models';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login.component').then((m) => m.LoginComponent),
  },
  {
    path: '',
    loadComponent: () =>
      import('./features/shell/app-shell.component').then((m) => m.AppShellComponent),
    canActivate: [authGuard],
    children: [
      {
        path: 'claims',
        loadComponent: () =>
          import('./features/claims/dashboard/claim-dashboard.component').then(
            (m) => m.ClaimDashboardComponent,
          ),
      },
      {
        path: 'claims/new',
        canActivate: [roleGuard([Role.ANSPRUCHSTELLER])],
        loadComponent: () =>
          import('./features/claims/dashboard/claim-dashboard.component').then(
            (m) => m.ClaimDashboardComponent,
          ),
      },
      {
        path: 'claims/:id',
        loadComponent: () =>
          import('./features/claims/dashboard/claim-dashboard.component').then(
            (m) => m.ClaimDashboardComponent,
          ),
      },
      { path: '', pathMatch: 'full', redirectTo: 'claims' },
    ],
  },
  { path: '**', redirectTo: 'claims' },
];
