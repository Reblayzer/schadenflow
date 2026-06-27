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
});
