import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let auth: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['token', 'logout']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: router },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('attaches the Bearer header when a token exists', () => {
    auth.token.and.returnValue('abc');
    http.get('/api/claims').subscribe();
    const req = httpMock.expectOne('/api/claims');
    expect(req.request.headers.get('Authorization')).toBe('Bearer abc');
    req.flush({ ok: true, data: {} });
  });

  it('does not attach a header to the login request', () => {
    auth.token.and.returnValue('abc');
    http.post('/api/auth/login', {}).subscribe();
    const req = httpMock.expectOne('/api/auth/login');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({ ok: true, data: {} });
  });

  it('does not logout or redirect on 401 from the login endpoint', () => {
    auth.token.and.returnValue(null);
    http.post('/api/auth/login', {}).subscribe({ error: () => {} });
    httpMock.expectOne('/api/auth/login').flush(
      { ok: false, error: { code: 'INVALID_CREDENTIALS', message: 'x' } },
      { status: 401, statusText: 'Unauthorized' },
    );
    expect(auth.logout).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('sends no Authorization header when token is null', () => {
    auth.token.and.returnValue(null);
    http.get('/api/claims').subscribe();
    const req = httpMock.expectOne('/api/claims');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({ ok: true, data: {} });
  });

  it('logs out and redirects on 401', () => {
    auth.token.and.returnValue('abc');
    http.get('/api/claims').subscribe({ error: () => {} });
    httpMock.expectOne('/api/claims').flush(
      { ok: false, error: { code: 'UNAUTHORIZED', message: 'x' } },
      { status: 401, statusText: 'Unauthorized' },
    );
    expect(auth.logout).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });
});
