import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { provideAnimations } from '@angular/platform-browser/animations';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { MatDialog } from '@angular/material/dialog';
import { ClaimDetailComponent } from './claim-detail.component';
import { ClaimsService } from '../data/claims.service';
import { AuthService } from '../../../core/auth/auth.service';
import { NotifyService } from '../../../shared/notify.service';
import { ClaimState, Role, Category } from '../../../core/models/claim.models';

function claim(state: ClaimState) {
  return {
    id: '1', claimantId: 'c1', title: 'Zahn', description: 'd', category: Category.ZAHNARZT,
    amount: 100, state, triageSummary: null, createdAt: '', updatedAt: '',
  };
}

describe('ClaimDetailComponent', () => {
  let fixture: ComponentFixture<ClaimDetailComponent>;
  let claims: jasmine.SpyObj<ClaimsService>;
  let dialog: jasmine.SpyObj<MatDialog>;

  function setup(role: Role, state: ClaimState) {
    TestBed.resetTestingModule();
    claims = jasmine.createSpyObj<ClaimsService>('ClaimsService', [
      'getById', 'transition', 'audit', 'triage', 'confirmCategory',
    ]);
    claims.getById.and.returnValue(of(claim(state)));
    claims.audit.and.returnValue(of([]));
    claims.transition.and.returnValue(of(claim(ClaimState.IN_PRUEFUNG)));
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    TestBed.configureTestingModule({
      imports: [ClaimDetailComponent],
      providers: [
        provideRouter([]),
        provideAnimations(),
        { provide: ClaimsService, useValue: claims },
        { provide: AuthService, useValue: { role: signal(role) } },
        { provide: NotifyService, useValue: jasmine.createSpyObj('NotifyService', ['error', 'success']) },
        { provide: MatDialog, useValue: dialog },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => '1' } } } },
      ],
    });
    fixture = TestBed.createComponent(ClaimDetailComponent);
    fixture.detectChanges();
  }

  it('loads the claim on init', () => {
    setup(Role.SACHBEARBEITER, ClaimState.EINGEREICHT);
    expect(claims.getById).toHaveBeenCalledWith('1');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Zahn');
  });

  it('shows the "In Prüfung nehmen" action for a reviewer on EINGEREICHT', () => {
    setup(Role.SACHBEARBEITER, ClaimState.EINGEREICHT);
    expect(fixture.componentInstance.transitions().map((t) => t.label)).toContain('In Prüfung nehmen');
  });

  it('shows no workflow actions for a claimant', () => {
    setup(Role.ANSPRUCHSTELLER, ClaimState.EINGEREICHT);
    expect(fixture.componentInstance.transitions()).toEqual([]);
  });

  it('runs a non-reason transition directly', () => {
    setup(Role.SACHBEARBEITER, ClaimState.EINGEREICHT);
    const opt = fixture.componentInstance.transitions().find((t) => t.target === ClaimState.IN_PRUEFUNG)!;
    fixture.componentInstance.runTransition(opt);
    expect(claims.transition).toHaveBeenCalledWith('1', ClaimState.IN_PRUEFUNG, undefined);
  });

  it('opens the reason dialog for a reject and passes the reason through', () => {
    setup(Role.SACHBEARBEITER, ClaimState.IN_PRUEFUNG);
    dialog.open.and.returnValue({ afterClosed: () => of({ confirmed: true, reason: 'nope' }) } as any);
    const reject = fixture.componentInstance.transitions().find((t) => t.target === ClaimState.ABGELEHNT)!;
    fixture.componentInstance.runTransition(reject);
    expect(dialog.open).toHaveBeenCalled();
    expect(claims.transition).toHaveBeenCalledWith('1', ClaimState.ABGELEHNT, 'nope');
  });

  it('exposes triage only for a reviewer on a pre-decision state', () => {
    setup(Role.SACHBEARBEITER, ClaimState.EINGEREICHT);
    expect(fixture.componentInstance.canTriage()).toBeTrue();
    setup(Role.SACHBEARBEITER, ClaimState.GENEHMIGT);
    expect(fixture.componentInstance.canTriage()).toBeFalse();
    setup(Role.ANSPRUCHSTELLER, ClaimState.EINGEREICHT);
    expect(fixture.componentInstance.canTriage()).toBeFalse();
  });

  it('canTriage is also true for ADMIN on IN_PRUEFUNG', () => {
    setup(Role.ADMIN, ClaimState.IN_PRUEFUNG);
    expect(fixture.componentInstance.canTriage()).toBeTrue();
  });

  it('requesting triage stores the advisory result without applying it', () => {
    setup(Role.SACHBEARBEITER, ClaimState.EINGEREICHT);
    claims.triage.and.returnValue(
      of({ summary: 'Zahn-Summary', suggestedCategory: Category.SPITAL, missingInfoFlags: [] }),
    );
    fixture.componentInstance.requestTriage();
    expect(claims.triage).toHaveBeenCalledWith('1');
    expect(fixture.componentInstance.triage()!.summary).toBe('Zahn-Summary');
    // advisory result is held:
    expect(fixture.componentInstance.triage()!.suggestedCategory).toBe(Category.SPITAL);
    // pre-filled into the editable field, but not applied to the claim:
    expect(fixture.componentInstance.selectedCategory()).toBe(Category.SPITAL);
    // nothing persisted yet:
    expect(claims.confirmCategory).not.toHaveBeenCalled();
    expect(fixture.componentInstance.claim()!.category).toBe(Category.ZAHNARZT); // unchanged seed value — now meaningful since suggestion differs
  });

  it('confirming category PATCHes the chosen values and updates the claim', () => {
    setup(Role.SACHBEARBEITER, ClaimState.EINGEREICHT);
    claims.confirmCategory.and.returnValue(
      of({ ...claim(ClaimState.EINGEREICHT), category: Category.SPITAL, triageSummary: 'final' }),
    );
    fixture.componentInstance.selectedCategory.set(Category.SPITAL);
    fixture.componentInstance.confirmSummary.set('final');
    fixture.componentInstance.confirmCategory();
    expect(claims.confirmCategory).toHaveBeenCalledWith('1', Category.SPITAL, 'final');
    expect(fixture.componentInstance.claim()!.category).toBe(Category.SPITAL);
  });

  it('loads and renders the audit trail', () => {
    setup(Role.SACHBEARBEITER, ClaimState.IN_PRUEFUNG);
    claims.audit.calls.reset();
    claims.audit.and.returnValue(
      of([
        {
          id: 'a1', claimId: '1', fromState: ClaimState.EINGEREICHT, toState: ClaimState.IN_PRUEFUNG,
          actorId: 'r1', actorRole: Role.SACHBEARBEITER, reason: null, occurredAt: '2026-06-27T10:00:00Z',
        },
      ]),
    );
    fixture.componentInstance['loadAudit']();
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Eingereicht');
    expect(text).toContain('In Prüfung');
  });
});
