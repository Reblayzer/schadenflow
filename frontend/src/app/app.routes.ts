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
    path: 'claims',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/claims/dashboard/claim-dashboard.component').then(
        (m) => m.ClaimDashboardComponent,
      ),
  },
  {
    path: 'claims/new',
    canActivate: [authGuard, roleGuard([Role.ANSPRUCHSTELLER])],
    loadComponent: () =>
      import('./features/claims/dashboard/claim-dashboard.component').then(
        (m) => m.ClaimDashboardComponent,
      ),
  },
  {
    path: 'claims/:id',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/claims/dashboard/claim-dashboard.component').then(
        (m) => m.ClaimDashboardComponent,
      ),
  },
  { path: '', pathMatch: 'full', redirectTo: 'claims' },
  { path: '**', redirectTo: 'claims' },
];
