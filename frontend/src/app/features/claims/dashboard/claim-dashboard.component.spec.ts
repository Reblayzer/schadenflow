import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideAnimations } from '@angular/platform-browser/animations';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { ClaimDashboardComponent } from './claim-dashboard.component';
import { ClaimsService } from '../data/claims.service';
import { AuthService } from '../../../core/auth/auth.service';
import { NotifyService } from '../../../shared/notify.service';
import { ClaimState, Role, Category } from '../../../core/models/claim.models';

function page(content: any[]) {
  return { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 };
}

describe('ClaimDashboardComponent', () => {
  let fixture: ComponentFixture<ClaimDashboardComponent>;
  let claims: jasmine.SpyObj<ClaimsService>;

  function setup(role: Role) {
    claims = jasmine.createSpyObj<ClaimsService>('ClaimsService', ['list']);
    claims.list.and.returnValue(
      of(
        page([
          {
            id: '1', claimantId: 'c1', title: 'Zahn', description: 'd',
            category: Category.ZAHNARZT, amount: 100, state: ClaimState.EINGEREICHT,
            triageSummary: null, createdAt: '', updatedAt: '',
          },
        ]),
      ),
    );
    TestBed.configureTestingModule({
      imports: [ClaimDashboardComponent],
      providers: [
        provideRouter([]),
        provideAnimations(),
        { provide: ClaimsService, useValue: claims },
        { provide: AuthService, useValue: { role: signal(role) } },
        { provide: NotifyService, useValue: jasmine.createSpyObj('NotifyService', ['error']) },
      ],
    });
    fixture = TestBed.createComponent(ClaimDashboardComponent);
    fixture.detectChanges();
  }

  it('loads and renders claim rows on init', () => {
    setup(Role.SACHBEARBEITER);
    expect(claims.list).toHaveBeenCalled();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Zahn');
  });

  it('shows the state filter for a reviewer', () => {
    setup(Role.SACHBEARBEITER);
    const el = (fixture.nativeElement as HTMLElement).querySelector('[data-test="state-filter"]');
    expect(el).not.toBeNull();
  });

  it('hides the state filter for a claimant', () => {
    setup(Role.ANSPRUCHSTELLER);
    const el = (fixture.nativeElement as HTMLElement).querySelector('[data-test="state-filter"]');
    expect(el).toBeNull();
  });

  it('re-queries when the state filter changes', () => {
    setup(Role.SACHBEARBEITER);
    claims.list.calls.reset();
    fixture.componentInstance.onStateChange(ClaimState.GENEHMIGT);
    expect(claims.list).toHaveBeenCalledWith(
      jasmine.objectContaining({ state: ClaimState.GENEHMIGT }),
      0,
      jasmine.any(Number),
    );
  });

  it('re-queries with the new page index and size on onPage', () => {
    setup(Role.SACHBEARBEITER);
    claims.list.calls.reset();
    fixture.componentInstance.onPage({ pageIndex: 2, pageSize: 50 } as any);
    expect(claims.list).toHaveBeenCalledWith(jasmine.any(Object), 2, 50);
  });
});
