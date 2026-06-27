import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { LoginComponent } from './login.component';
import { AuthService } from '../../core/auth/auth.service';
import { NotifyService } from '../../shared/notify.service';
import { ApiClientError } from '../../core/api/api-error';
import { Role } from '../../core/models/claim.models';
import { provideAnimations } from '@angular/platform-browser/animations';

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let component: LoginComponent;
  let auth: jasmine.SpyObj<AuthService>;
  let notify: jasmine.SpyObj<NotifyService>;
  let router: Router;

  beforeEach(async () => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['login']);
    notify = jasmine.createSpyObj<NotifyService>('NotifyService', ['error']);
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        provideAnimations(),
        { provide: AuthService, useValue: auth },
        { provide: NotifyService, useValue: notify },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    fixture.detectChanges();
  });

  it('navigates to /claims on successful login', () => {
    const navSpy = spyOn(router, 'navigateByUrl');
    auth.login.and.returnValue(
      of({ token: 't', username: 'admin', role: Role.ADMIN, expiresAt: '' }),
    );
    component.form.setValue({ username: 'admin', password: 'password123' });
    component.submit();
    expect(auth.login).toHaveBeenCalledWith('admin', 'password123');
    expect(navSpy).toHaveBeenCalledWith('/claims');
  });

  it('shows an error snackbar on failed login', () => {
    auth.login.and.returnValue(throwError(() => new ApiClientError('INVALID_CREDENTIALS', 'x')));
    component.form.setValue({ username: 'admin', password: 'wrong' });
    component.submit();
    expect(notify.error).toHaveBeenCalled();
  });

  it('does not submit an invalid form', () => {
    component.form.setValue({ username: '', password: '' });
    component.submit();
    expect(auth.login).not.toHaveBeenCalled();
  });

  it('navigates to returnUrl when present in query params', async () => {
    await TestBed.resetTestingModule();
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['login']);
    notify = jasmine.createSpyObj<NotifyService>('NotifyService', ['error']);
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        provideAnimations(),
        { provide: AuthService, useValue: auth },
        { provide: NotifyService, useValue: notify },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: convertToParamMap({ returnUrl: '/claims/42' }) },
          },
        },
      ],
    }).compileComponents();
    const localFixture = TestBed.createComponent(LoginComponent);
    const localComponent = localFixture.componentInstance;
    const localRouter = TestBed.inject(Router);
    localFixture.detectChanges();
    const navSpy = spyOn(localRouter, 'navigateByUrl');
    auth.login.and.returnValue(
      of({ token: 't', username: 'admin', role: Role.ADMIN, expiresAt: '' }),
    );
    localComponent.form.setValue({ username: 'admin', password: 'password123' });
    localComponent.submit();
    expect(navSpy).toHaveBeenCalledWith('/claims/42');
  });
});
