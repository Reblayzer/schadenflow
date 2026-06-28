import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { ClaimsService } from '../data/claims.service';
import { NotifyService } from '../../../shared/notify.service';
import { errorMessage } from '../../../shared/claim-labels';

@Component({
  selector: 'app-claim-create',
  standalone: true,
  imports: [
    ReactiveFormsModule, MatCardModule, MatFormFieldModule, MatInputModule, MatButtonModule,
  ],
  template: `
    <h1>Neuer Schadenfall</h1>
    <mat-card style="max-width:640px">
      <mat-card-content>
        <form [formGroup]="form" (ngSubmit)="submit()">
          <mat-form-field appearance="outline" style="width:100%">
            <mat-label>Titel</mat-label>
            <input matInput formControlName="title" maxlength="200" />
          </mat-form-field>
          <mat-form-field appearance="outline" style="width:100%">
            <mat-label>Beschreibung</mat-label>
            <textarea matInput formControlName="description" rows="5" maxlength="5000"></textarea>
          </mat-form-field>
          <mat-form-field appearance="outline" style="width:100%">
            <mat-label>Betrag (CHF)</mat-label>
            <input matInput type="number" formControlName="amount" min="0" step="0.01" />
          </mat-form-field>
          <button mat-raised-button color="primary" type="submit" [disabled]="saving()">
            Einreichen
          </button>
        </form>
      </mat-card-content>
    </mat-card>
  `,
})
export class ClaimCreateComponent {
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly claims = inject(ClaimsService);
  private readonly notify = inject(NotifyService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly saving = signal(false);
  readonly form = this.fb.group({
    title: ['', [Validators.required, Validators.maxLength(200)]],
    description: ['', [Validators.required, Validators.maxLength(5000)]],
    amount: [null as number | null, [Validators.required, Validators.min(0)]],
  });

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { title, description, amount } = this.form.getRawValue();
    this.saving.set(true);
    this.claims.create({ title, description, amount: amount! })
      .pipe(takeUntilDestroyed(this.destroyRef), finalize(() => this.saving.set(false)))
      .subscribe({
        next: (claim) => { this.notify.success('Schadenfall eingereicht.'); this.router.navigate(['/claims', claim.id]); },
        error: (err) => this.notify.error(errorMessage(err)),
      });
  }
}
