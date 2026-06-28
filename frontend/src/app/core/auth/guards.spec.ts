import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { authGuard } from './auth.guard';
import { roleGuard } from './role.guard';
import { AuthService } from './auth.service';
import { Role } from '../models/claim.models';

function run(guardFn: () => unknown): unknown {
  return TestBed.runInInjectionContext(guardFn as any);
}

describe('authGuard', () => {
  let auth: jasmine.SpyObj<AuthService>;
  let router: Router;

  beforeEach(() => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['isAuthenticated', 'role']);
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: auth }],
    });
    router = TestBed.inject(Router);
  });

  it('allows an authenticated user', () => {
    auth.isAuthenticated.and.returnValue(true);
    const result = run(() => authGuard({} as any, { url: '/claims' } as any));
    expect(result).toBeTrue();
  });

  it('redirects an unauthenticated user to /login', () => {
    auth.isAuthenticated.and.returnValue(false);
    const result = run(() => authGuard({} as any, { url: '/claims' } as any));
    expect(result instanceof UrlTree).toBeTrue();
    expect((result as UrlTree).toString()).toContain('/login');
  });
});

describe('roleGuard', () => {
  let auth: jasmine.SpyObj<AuthService>;

  beforeEach(() => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['isAuthenticated', 'role']);
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: auth }],
    });
  });

  it('allows an allowed role', () => {
    auth.role.and.returnValue(Role.ANSPRUCHSTELLER);
    const guard = roleGuard([Role.ANSPRUCHSTELLER]);
    const result = run(() => guard({} as any, {} as any));
    expect(result).toBeTrue();
  });

  it('redirects a disallowed role to /claims', () => {
    auth.role.and.returnValue(Role.SACHBEARBEITER);
    const guard = roleGuard([Role.ANSPRUCHSTELLER]);
    const result = run(() => guard({} as any, {} as any));
    expect(result instanceof UrlTree).toBeTrue();
    expect((result as UrlTree).toString()).toContain('/claims');
  });

  it('redirects a null role (unauthenticated) to /claims', () => {
    auth.role.and.returnValue(null);
    const guard = roleGuard([Role.ANSPRUCHSTELLER]);
    const result = run(() => guard({} as any, {} as any));
    expect(result instanceof UrlTree).toBeTrue();
    expect((result as UrlTree).toString()).toContain('/claims');
  });
});
