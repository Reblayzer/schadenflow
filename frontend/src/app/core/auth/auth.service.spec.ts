import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { Role } from '../models/claim.models';

const STORAGE_KEY = 'schadenflow.auth';

function futureIso(): string {
  return new Date(Date.now() + 3_600_000).toISOString();
}
function pastIso(): string {
  return new Date(Date.now() - 1000).toISOString();
}

describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [AuthService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('starts logged out with empty storage', () => {
    expect(service.isAuthenticated()).toBeFalse();
    expect(service.currentUser()).toBeNull();
  });

  it('login posts credentials and stores the session', () => {
    const user = { token: 't', username: 'admin', role: Role.ADMIN, expiresAt: futureIso() };
    let emitted: unknown;
    service.login('admin', 'password123').subscribe((u) => (emitted = u));

    const req = http.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ username: 'admin', password: 'password123' });
    req.flush({ ok: true, data: user });

    expect(emitted).toEqual(user);
    expect(service.isAuthenticated()).toBeTrue();
    expect(service.role()).toBe(Role.ADMIN);
    expect(service.token()).toBe('t');
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!).username).toBe('admin');
  });

  it('hydrates a valid session from storage on construction', () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ token: 't', username: 'admin', role: Role.ADMIN, expiresAt: futureIso() }),
    );
    const svc = new (AuthService as any)();
    expect(svc.isAuthenticated()).toBeTrue();
  });

  it('treats a session missing required fields (no token) as logged out and clears storage', () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ username: 'admin', role: Role.ADMIN, expiresAt: futureIso() }),
    );
    const svc = new (AuthService as any)();
    expect(svc.isAuthenticated()).toBeFalse();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('treats an expired stored session as logged out and clears it', () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ token: 't', username: 'admin', role: Role.ADMIN, expiresAt: pastIso() }),
    );
    const svc = new (AuthService as any)();
    expect(svc.isAuthenticated()).toBeFalse();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('logout clears storage and signal', () => {
    const user = { token: 't', username: 'admin', role: Role.ADMIN, expiresAt: futureIso() };
    service.login('admin', 'password123').subscribe();
    http.expectOne('/api/auth/login').flush({ ok: true, data: user });

    service.logout();
    expect(service.isAuthenticated()).toBeFalse();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });
});
