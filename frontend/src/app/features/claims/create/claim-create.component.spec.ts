import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { ClaimCreateComponent } from './claim-create.component';
import { ClaimsService } from '../data/claims.service';
import { NotifyService } from '../../../shared/notify.service';
import { ApiClientError } from '../../../core/api/api-error';

describe('ClaimCreateComponent', () => {
  let fixture: ComponentFixture<ClaimCreateComponent>;
  let component: ClaimCreateComponent;
  let claims: jasmine.SpyObj<ClaimsService>;
  let notify: jasmine.SpyObj<NotifyService>;
  let router: Router;

  beforeEach(async () => {
    claims = jasmine.createSpyObj<ClaimsService>('ClaimsService', ['create']);
    notify = jasmine.createSpyObj<NotifyService>('NotifyService', ['error', 'success']);
    await TestBed.configureTestingModule({
      imports: [ClaimCreateComponent],
      providers: [
        provideRouter([]),
        provideAnimations(),
        { provide: ClaimsService, useValue: claims },
        { provide: NotifyService, useValue: notify },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ClaimCreateComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    fixture.detectChanges();
  });

  it('does not submit an invalid form', () => {
    component.form.setValue({ title: '', description: '', amount: null });
    component.submit();
    expect(claims.create).not.toHaveBeenCalled();
  });

  it('creates and navigates to the new claim detail on success', () => {
    const nav = spyOn(router, 'navigate');
    claims.create.and.returnValue(of({ id: '42' } as any));
    component.form.setValue({ title: 'T', description: 'Beschreibung', amount: 50 });
    component.submit();
    expect(claims.create).toHaveBeenCalledWith({ title: 'T', description: 'Beschreibung', amount: 50 });
    expect(nav).toHaveBeenCalledWith(['/claims', '42']);
    expect(notify.success).toHaveBeenCalledWith('Schadenfall eingereicht.');
  });

  it('shows an error on failed create', () => {
    claims.create.and.returnValue(throwError(() => new ApiClientError('X', 'y')));
    component.form.setValue({ title: 'T', description: 'Beschreibung', amount: 50 });
    component.submit();
    expect(notify.error).toHaveBeenCalledWith(jasmine.any(String));
  });
});
