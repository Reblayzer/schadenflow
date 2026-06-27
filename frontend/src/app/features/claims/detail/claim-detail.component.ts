import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { ClaimsService } from '../data/claims.service';
import { AuthService } from '../../../core/auth/auth.service';
import { NotifyService } from '../../../shared/notify.service';
import { errorMessage, claimStateColor } from '../../../shared/claim-labels';
import { availableTransitions, TransitionOption } from '../../../shared/transitions';
import { ClaimStatePipe } from '../../../shared/claim-state.pipe';
import { CategoryPipe } from '../../../shared/category.pipe';
import {
  ConfirmDialogComponent, ConfirmDialogData, ConfirmDialogResult,
} from '../../../shared/confirm-dialog.component';
import { Claim, ClaimState } from '../../../core/models/claim.models';

@Component({
  selector: 'app-claim-detail',
  standalone: true,
  imports: [
    CurrencyPipe, DatePipe, MatCardModule, MatButtonModule, MatChipsModule,
    ClaimStatePipe, CategoryPipe,
  ],
  template: `
    @if (claim(); as c) {
      <a href="/claims" style="display:inline-block;margin-bottom:1rem">&larr; Zurück</a>
      <mat-card>
        <mat-card-header>
          <mat-card-title>{{ c.title }}</mat-card-title>
          <mat-card-subtitle>
            <mat-chip [color]="stateColor(c.state)" highlighted>{{ c.state | claimState }}</mat-chip>
          </mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <p><strong>Betrag:</strong> {{ c.amount | currency: 'CHF' }}</p>
          <p><strong>Kategorie:</strong> {{ c.category | category }}</p>
          <p><strong>Beschreibung:</strong> {{ c.description }}</p>
          @if (c.triageSummary) { <p><strong>Zusammenfassung:</strong> {{ c.triageSummary }}</p> }
          <p style="color:rgba(0,0,0,.54)">Aktualisiert: {{ c.updatedAt | date: 'medium' }}</p>
        </mat-card-content>
        @if (transitions().length) {
          <mat-card-actions>
            @for (t of transitions(); track t.target) {
              <button mat-raised-button color="primary" (click)="runTransition(t)">{{ t.label }}</button>
            }
          </mat-card-actions>
        }
      </mat-card>
    }
  `,
})
export class ClaimDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly claims = inject(ClaimsService);
  private readonly auth = inject(AuthService);
  private readonly notify = inject(NotifyService);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  readonly id = this.route.snapshot.paramMap.get('id')!;
  readonly claim = signal<Claim | null>(null);

  readonly transitions = computed<TransitionOption[]>(() => {
    const c = this.claim();
    const role = this.auth.role();
    if (!c || !role) {
      return [];
    }
    return availableTransitions(c.state, role);
  });

  constructor() {
    this.reload();
  }

  stateColor(s: ClaimState) {
    return claimStateColor(s);
  }

  runTransition(option: TransitionOption): void {
    if (option.requiresReason) {
      const data: ConfirmDialogData = {
        title: option.label,
        message: 'Bitte geben Sie eine Begründung an.',
        confirmLabel: option.label,
        requireReason: true,
      };
      this.dialog
        .open(ConfirmDialogComponent, { data, width: '420px' })
        .afterClosed()
        .subscribe((result: ConfirmDialogResult | undefined) => {
          if (result?.confirmed) {
            this.doTransition(option.target, result.reason);
          }
        });
    } else {
      this.doTransition(option.target, undefined);
    }
  }

  private doTransition(target: ClaimState, reason: string | undefined): void {
    this.claims.transition(this.id, target, reason).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (updated) => {
        this.claim.set(updated);
        this.notify.success('Status aktualisiert.');
      },
      error: (err) => this.notify.error(errorMessage(err)),
    });
  }

  private reload(): void {
    this.claims.getById(this.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (c) => this.claim.set(c),
      error: (err) => this.notify.error(errorMessage(err)),
    });
  }
}
