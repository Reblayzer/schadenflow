import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ClaimsService, ClaimFilters } from '../data/claims.service';
import { AuthService } from '../../../core/auth/auth.service';
import { NotifyService } from '../../../shared/notify.service';
import { errorMessage, claimStateColor, ALL_STATES } from '../../../shared/claim-labels';
import { ClaimStatePipe } from '../../../shared/claim-state.pipe';
import { CategoryPipe } from '../../../shared/category.pipe';
import { Claim, ClaimState, Role } from '../../../core/models/claim.models';
import { ThemePalette } from '@angular/material/core';

@Component({
  selector: 'app-claim-dashboard',
  standalone: true,
  imports: [
    CurrencyPipe, DatePipe, MatTableModule, MatChipsModule, MatPaginatorModule,
    MatFormFieldModule, MatSelectModule, MatProgressBarModule, ClaimStatePipe, CategoryPipe,
  ],
  template: `
    <h1>Schadenfälle</h1>
    @if (isReviewer()) {
      <mat-form-field appearance="outline" data-test="state-filter">
        <mat-label>Status</mat-label>
        <mat-select [value]="state()" (valueChange)="onStateChange($event)">
          <mat-option [value]="null">Alle</mat-option>
          @for (s of allStates; track s) {
            <mat-option [value]="s">{{ s | claimState }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    }

    @if (loading()) { <mat-progress-bar mode="indeterminate" /> }

    @if (!loading() && rows().length === 0) {
      <p>Keine Schadenfälle vorhanden.</p>
    } @else {
      <table mat-table [dataSource]="rows()" class="mat-elevation-z1" style="width:100%">
        <ng-container matColumnDef="title">
          <th mat-header-cell *matHeaderCellDef>Titel</th>
          <td mat-cell *matCellDef="let c">{{ c.title }}</td>
        </ng-container>
        <ng-container matColumnDef="category">
          <th mat-header-cell *matHeaderCellDef>Kategorie</th>
          <td mat-cell *matCellDef="let c">{{ c.category | category }}</td>
        </ng-container>
        <ng-container matColumnDef="amount">
          <th mat-header-cell *matHeaderCellDef>Betrag</th>
          <td mat-cell *matCellDef="let c">{{ c.amount | currency: 'CHF' }}</td>
        </ng-container>
        <ng-container matColumnDef="state">
          <th mat-header-cell *matHeaderCellDef>Status</th>
          <td mat-cell *matCellDef="let c">
            <mat-chip [color]="stateColor(c.state)" highlighted>{{ c.state | claimState }}</mat-chip>
          </td>
        </ng-container>
        <ng-container matColumnDef="updatedAt">
          <th mat-header-cell *matHeaderCellDef>Aktualisiert</th>
          <td mat-cell *matCellDef="let c">{{ c.updatedAt | date: 'short' }}</td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="columns"></tr>
        <tr mat-row *matRowDef="let row; columns: columns" (click)="open(row)" style="cursor:pointer"></tr>
      </table>
      <mat-paginator
        [length]="total()"
        [pageSize]="size"
        [pageIndex]="pageIndex()"
        [pageSizeOptions]="[10, 20, 50]"
        (page)="onPage($event)"
      />
    }
  `,
})
export class ClaimDashboardComponent {
  private readonly claims = inject(ClaimsService);
  private readonly auth = inject(AuthService);
  private readonly notify = inject(NotifyService);
  private readonly router = inject(Router);

  readonly columns = ['title', 'category', 'amount', 'state', 'updatedAt'];
  readonly allStates = ALL_STATES;
  readonly isReviewer = computed(
    () => this.auth.role() === Role.SACHBEARBEITER || this.auth.role() === Role.ADMIN,
  );

  readonly rows = signal<Claim[]>([]);
  readonly total = signal(0);
  readonly pageIndex = signal(0);
  readonly state = signal<ClaimState | null>(null);
  readonly loading = signal(false);
  size = 20;

  constructor() {
    this.load();
  }

  stateColor(s: ClaimState): ThemePalette {
    const c = claimStateColor(s);
    return c === '' ? undefined : c;
  }

  onStateChange(s: ClaimState | null): void {
    this.state.set(s);
    this.pageIndex.set(0);
    this.load();
  }

  onPage(e: PageEvent): void {
    this.pageIndex.set(e.pageIndex);
    this.size = e.pageSize;
    this.load();
  }

  open(c: Claim): void {
    this.router.navigate(['/claims', c.id]);
  }

  private load(): void {
    const filters: ClaimFilters = {};
    if (this.state()) {
      filters.state = this.state()!;
    }
    this.loading.set(true);
    this.claims.list(filters, this.pageIndex(), this.size).subscribe({
      next: (page) => {
        this.rows.set(page.content);
        this.total.set(page.totalElements);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.notify.error(errorMessage(err));
      },
    });
  }
}
