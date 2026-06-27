import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from '../../core/auth/auth.service';
import { roleLabel } from '../../shared/claim-labels';
import { Role } from '../../core/models/claim.models';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterLink, RouterOutlet, MatToolbarModule, MatButtonModule, MatIconModule],
  template: `
    <mat-toolbar color="primary">
      <a routerLink="/claims" style="color:inherit;text-decoration:none;font-weight:500">Schadenflow</a>
      <span style="flex:1 1 auto"></span>
      @if (isClaimant()) {
        <a mat-button routerLink="/claims/new">Neuer Schaden</a>
      }
      <span style="margin:0 12px">{{ user()?.username }} ({{ roleText() }})</span>
      <button mat-icon-button data-test="logout" (click)="logout()" aria-label="Abmelden">
        <mat-icon>logout</mat-icon>
      </button>
    </mat-toolbar>
    <main style="padding:1.5rem; max-width:1100px; margin:0 auto">
      <router-outlet></router-outlet>
    </main>
  `,
})
export class AppShellComponent {
  private readonly auth = inject(AuthService);
  readonly user = this.auth.currentUser;
  readonly isClaimant = computed(() => this.auth.role() === Role.ANSPRUCHSTELLER);
  readonly roleText = computed(() => {
    const r = this.auth.role();
    return r ? roleLabel(r) : '';
  });

  logout(): void {
    this.auth.logout();
  }
}
