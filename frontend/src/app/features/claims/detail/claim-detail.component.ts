import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatListModule } from '@angular/material/list';
import { ClaimsService } from '../data/claims.service';
import { AuthService } from '../../../core/auth/auth.service';
import { NotifyService } from '../../../shared/notify.service';
import { errorMessage, claimStateColor, flagLabel, categoryLabel, ALL_CATEGORIES, roleLabel } from '../../../shared/claim-labels';
import { availableTransitions, TransitionOption } from '../../../shared/transitions';
import { ClaimStatePipe } from '../../../shared/claim-state.pipe';
import { CategoryPipe } from '../../../shared/category.pipe';
import {
  ConfirmDialogComponent, ConfirmDialogData, ConfirmDialogResult,
} from '../../../shared/confirm-dialog.component';
import { Claim, ClaimState, Role, MissingInfoFlag, Category, TriageResult, AuditEntry } from '../../../core/models/claim.models';

@Component({
  selector: 'app-claim-detail',
  standalone: true,
  imports: [
    CurrencyPipe, DatePipe, MatCardModule, MatButtonModule, MatChipsModule,
    ClaimStatePipe, CategoryPipe, RouterLink,
    FormsModule, MatFormFieldModule, MatSelectModule, MatInputModule, MatProgressBarModule, MatListModule,
  ],
  template: `
    @if (claim(); as c) {
      <a routerLink="/claims" style="display:inline-block;margin-bottom:1rem">&larr; Zurück</a>
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
        @if (canTriage()) {
          <mat-card-content class="triage-panel" style="border-top:1px solid rgba(0,0,0,.12);margin-top:1rem">
            <h3>KI-Triage</h3>
            <button mat-stroked-button color="primary" (click)="requestTriage()" [disabled]="triageLoading()">
              KI-Triage anfordern
            </button>
            @if (triageLoading()) { <mat-progress-bar mode="indeterminate" /> }

            @if (triage(); as t) {
              <div class="advisory" data-test="advisory"
                   style="margin-top:1rem;padding:1rem;border:1px dashed #f9a825;background:#fffde7;border-radius:8px">
                <strong>KI-Vorschlag — bitte bestätigen</strong>
                <p><em>Vorgeschlagene Kategorie:</em> {{ categoryText(t.suggestedCategory) }}</p>
                <p><em>Zusammenfassung:</em> {{ t.summary }}</p>
                @if (t.missingInfoFlags.length) {
                  <p><em>Fehlende Angaben:</em></p>
                  <mat-chip-set>
                    @for (f of t.missingInfoFlags; track f) { <mat-chip>{{ flagText(f) }}</mat-chip> }
                  </mat-chip-set>
                }
              </div>

              <div style="margin-top:1rem">
                <mat-form-field appearance="outline" style="width:100%">
                  <mat-label>Kategorie bestätigen</mat-label>
                  <mat-select [value]="selectedCategory()" (valueChange)="selectedCategory.set($event)">
                    @for (c of allCategories; track c) {
                      <mat-option [value]="c">{{ categoryText(c) }}</mat-option>
                    }
                  </mat-select>
                </mat-form-field>
                <mat-form-field appearance="outline" style="width:100%">
                  <mat-label>Zusammenfassung</mat-label>
                  <textarea matInput rows="3" [value]="confirmSummary()"
                            (input)="confirmSummary.set($any($event.target).value)"></textarea>
                </mat-form-field>
                <button mat-raised-button color="accent"
                        [disabled]="!selectedCategory() || confirming()" (click)="confirmCategory()">
                  Kategorie bestätigen
                </button>
              </div>
            }
          </mat-card-content>
        }
      </mat-card>
      <mat-card style="margin-top:1.5rem">
        <mat-card-header><mat-card-title>Verlauf (Audit)</mat-card-title></mat-card-header>
        <mat-card-content>
          @if (auditEntries().length === 0) {
            <p>Noch keine Einträge.</p>
          } @else {
            <mat-list>
              @for (e of auditEntries(); track e.id) {
                <mat-list-item>
                  <span matListItemTitle>
                    {{ e.fromState ? (e.fromState | claimState) : '—' }} → {{ e.toState | claimState }}
                  </span>
                  <span matListItemLine>
                    {{ roleText(e.actorRole) }} · {{ e.occurredAt | date: 'medium' }}
                    @if (e.reason) { · {{ e.reason }} }
                  </span>
                </mat-list-item>
              }
            </mat-list>
          }
        </mat-card-content>
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

  readonly allCategories = ALL_CATEGORIES;
  readonly auditEntries = signal<AuditEntry[]>([]);
  readonly triage = signal<TriageResult | null>(null);
  readonly triageLoading = signal(false);
  readonly confirming = signal(false);
  readonly selectedCategory = signal<Category | null>(null);
  readonly confirmSummary = signal('');

  readonly canTriage = computed(() => {
    const c = this.claim();
    const role = this.auth.role();
    const reviewer = role === Role.SACHBEARBEITER || role === Role.ADMIN;
    const preDecision = c?.state === ClaimState.EINGEREICHT || c?.state === ClaimState.IN_PRUEFUNG;
    return !!c && reviewer && preDecision;
  });

  constructor() {
    this.reload();
    this.loadAudit();
  }

  stateColor(s: ClaimState) {
    return claimStateColor(s);
  }

  roleText(r: Role) {
    return roleLabel(r);
  }

  flagText(f: MissingInfoFlag) {
    return flagLabel(f);
  }
  categoryText(c: Category) {
    return categoryLabel(c);
  }

  requestTriage(): void {
    this.triageLoading.set(true);
    this.claims.triage(this.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (t) => {
        this.triage.set(t);
        this.selectedCategory.set(t.suggestedCategory); // pre-fill, NOT auto-applied
        this.confirmSummary.set(t.summary);
        this.triageLoading.set(false);
      },
      error: (err) => {
        this.triageLoading.set(false);
        this.notify.error(errorMessage(err));
      },
    });
  }

  confirmCategory(): void {
    const category = this.selectedCategory();
    if (!category) {
      return;
    }
    this.confirming.set(true);
    this.claims.confirmCategory(this.id, category, this.confirmSummary() || undefined).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (updated) => {
        this.claim.set(updated);
        this.triage.set(null);
        this.confirming.set(false);
        this.notify.success('Kategorie bestätigt.');
      },
      error: (err) => {
        this.confirming.set(false);
        this.notify.error(errorMessage(err));
      },
    });
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
        .pipe(takeUntilDestroyed(this.destroyRef))
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
        this.loadAudit();
        this.notify.success('Status aktualisiert.');
      },
      error: (err) => this.notify.error(errorMessage(err)),
    });
  }

  private loadAudit(): void {
    this.claims.audit(this.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (entries) => this.auditEntries.set([...entries].reverse()),
      error: () => {},
    });
  }

  private reload(): void {
    this.claims.getById(this.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (c) => this.claim.set(c),
      error: (err) => this.notify.error(errorMessage(err)),
    });
  }
}
