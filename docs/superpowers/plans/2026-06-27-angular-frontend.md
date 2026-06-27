# Angular Frontend (SP5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Angular frontend over the existing Schadenflow REST API — full three-role claim lifecycle (claimant submit/track; reviewer triage, AI-confirm, workflow + audit).

**Architecture:** Feature-grouped standalone components with small, signal-based injectable services (Approach A — no NgRx). JWT held in `localStorage` with a functional HTTP interceptor and functional route guards. App calls relative `/api/...` URLs, proxied to the backend in dev (`proxy.conf.json`) and in Docker (nginx `proxy_pass`).

**Tech Stack:** Angular 19 (standalone components + signals), Angular Material + CDK (azure-blue prebuilt theme, already configured), RxJS, Karma + Jasmine (headless Chrome in CI).

## Global Constraints

- **No backend changes.** The API (SP1–SP4) is complete and unchanged.
- **Angular 19, standalone + signals.** No NgModules. Functional interceptors (`HttpInterceptorFn`) and guards (`CanActivateFn`). Use `inject()`.
- **No new runtime dependencies.** Use what `frontend/package.json` already has (Angular core/common/forms/router, Material, CDK, RxJS). No NgRx, no e2e framework.
- **Relative API URLs only.** Always call `/api/...`; never hard-code `http://localhost:8080`.
- **API envelope:** every response is `{ ok: boolean, data?: T, error?: { code, message } }` (fields absent when null — backend uses `@JsonInclude(NON_NULL)`).
- **German for domain labels/copy** (state names, button text, error messages); English for code identifiers, file names, and selectors. Component selector prefix `app`.
- **Backend is the security authority.** Client-side role gating is UX only.
- **TDD, frequent commits.** Each task ends with a passing headless test run and a commit. Commit message trailer on every commit:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- **Test command (headless):** `npm test -- --watch=false --browsers=ChromeHeadless` (run from `frontend/`).
- **Component convention:** inline `template`/`styles` in the `@Component` decorator (keeps each unit self-contained). `.spec.ts` beside each unit.

### Reference: backend API & enums (do not re-derive)

Base: all `/api/*` except `/api/health` need `Authorization: Bearer <jwt>`.

| Method & path | Body → Response |
|---|---|
| `POST /api/auth/login` | `{username,password}` → `{token,username,role,expiresAt}` |
| `GET /api/claims?state=&claimantId=&page=0&size=20` | → `Page<ClaimResponse>` (claimants auto-scoped server-side) |
| `POST /api/claims` | `{title,description,amount}` → `ClaimResponse` (201) |
| `GET /api/claims/{id}` | → `ClaimResponse` |
| `POST /api/claims/{id}/transitions` | `{targetState,reason?}` → `ClaimResponse` |
| `GET /api/claims/{id}/audit` | → `AuditEntryResponse[]` |
| `POST /api/claims/{id}/triage` | → `{summary,suggestedCategory,missingInfoFlags}` |
| `PATCH /api/claims/{id}` | `{category,triageSummary?}` → `ClaimResponse` |

`ClaimResponse`: `{ id, claimantId, title, description, category, amount, state, triageSummary, createdAt, updatedAt }`.
`AuditEntryResponse`: `{ id, claimId, fromState, toState, actorId, actorRole, reason, occurredAt }`.
`Page<T>` (Spring): `{ content: T[], totalElements, totalPages, number, size }`.

Enums:
- `ClaimState`: `EINGEREICHT, IN_PRUEFUNG, GENEHMIGT, ABGELEHNT, AUSBEZAHLT`
- `Category`: `ARZTKOSTEN, MEDIKAMENTE, SPITAL, ZAHNARZT, THERAPIE, HILFSMITTEL, SONSTIGES`
- `Role`: `ANSPRUCHSTELLER, SACHBEARBEITER, ADMIN`
- `MissingInfoFlag`: `MISSING_AMOUNT, VAGUE_DESCRIPTION, MISSING_DATE, MISSING_PROVIDER`

Transition map (mirror client-side; backend authoritative):
- `EINGEREICHT → IN_PRUEFUNG` — SACHBEARBEITER, ADMIN
- `IN_PRUEFUNG → GENEHMIGT` — SACHBEARBEITER, ADMIN
- `IN_PRUEFUNG → ABGELEHNT` — SACHBEARBEITER, ADMIN (**reason required**)
- `GENEHMIGT → AUSBEZAHLT` — ADMIN only

Seed users (all password `password123`): `anspruchsteller`, `sachbearbeiter`, `admin`.

---

## Task 1: Foundations — providers, models, API envelope, dev proxy

**Files:**
- Create: `frontend/src/app/core/models/claim.models.ts`
- Create: `frontend/src/app/core/api/api-response.model.ts`
- Create: `frontend/src/app/core/api/api-error.ts`
- Create: `frontend/src/app/core/api/unwrap.ts`
- Create: `frontend/src/app/core/api/unwrap.spec.ts`
- Create: `frontend/proxy.conf.json`
- Modify: `frontend/src/app/app.config.ts`
- Modify: `frontend/angular.json` (serve → development options: `proxyConfig`)

**Interfaces:**
- Produces: enums `ClaimState`, `Category`, `Role`, `MissingInfoFlag`; interfaces `Claim`, `AuditEntry`, `TriageResult`; `ApiResponse<T>`, `Page<T>`, `ApiError` (interface), class `ApiClientError`; operator `unwrap<T>()`; helper `toApiError(err): ApiClientError`.

- [ ] **Step 1: Write domain models**

`frontend/src/app/core/models/claim.models.ts`:

```ts
export enum ClaimState {
  EINGEREICHT = 'EINGEREICHT',
  IN_PRUEFUNG = 'IN_PRUEFUNG',
  GENEHMIGT = 'GENEHMIGT',
  ABGELEHNT = 'ABGELEHNT',
  AUSBEZAHLT = 'AUSBEZAHLT',
}

export enum Category {
  ARZTKOSTEN = 'ARZTKOSTEN',
  MEDIKAMENTE = 'MEDIKAMENTE',
  SPITAL = 'SPITAL',
  ZAHNARZT = 'ZAHNARZT',
  THERAPIE = 'THERAPIE',
  HILFSMITTEL = 'HILFSMITTEL',
  SONSTIGES = 'SONSTIGES',
}

export enum Role {
  ANSPRUCHSTELLER = 'ANSPRUCHSTELLER',
  SACHBEARBEITER = 'SACHBEARBEITER',
  ADMIN = 'ADMIN',
}

export enum MissingInfoFlag {
  MISSING_AMOUNT = 'MISSING_AMOUNT',
  VAGUE_DESCRIPTION = 'VAGUE_DESCRIPTION',
  MISSING_DATE = 'MISSING_DATE',
  MISSING_PROVIDER = 'MISSING_PROVIDER',
}

export interface Claim {
  id: string;
  claimantId: string;
  title: string;
  description: string;
  category: Category | null;
  amount: number;
  state: ClaimState;
  triageSummary: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AuditEntry {
  id: string;
  claimId: string;
  fromState: ClaimState | null;
  toState: ClaimState;
  actorId: string;
  actorRole: Role;
  reason: string | null;
  occurredAt: string;
}

export interface TriageResult {
  summary: string;
  suggestedCategory: Category;
  missingInfoFlags: MissingInfoFlag[];
}
```

- [ ] **Step 2: Write API envelope + error types**

`frontend/src/app/core/api/api-response.model.ts`:

```ts
export interface ApiError {
  code: string;
  message: string;
}

export interface ApiResponse<T> {
  ok: boolean;
  data?: T;
  error?: ApiError;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}
```

`frontend/src/app/core/api/api-error.ts`:

```ts
export class ApiClientError extends Error {
  constructor(public readonly code: string, message: string) {
    super(message);
    this.name = 'ApiClientError';
  }
}
```

- [ ] **Step 3: Write the failing test for `unwrap`**

`frontend/src/app/core/api/unwrap.spec.ts`:

```ts
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { ApiResponse } from './api-response.model';
import { ApiClientError } from './api-error';
import { unwrap, toApiError } from './unwrap';

describe('unwrap', () => {
  it('maps data out when ok', (done) => {
    of({ ok: true, data: 42 } as ApiResponse<number>)
      .pipe(unwrap<number>())
      .subscribe((v) => {
        expect(v).toBe(42);
        done();
      });
  });

  it('throws ApiClientError when ok is false', (done) => {
    of({ ok: false, error: { code: 'X', message: 'm' } } as ApiResponse<number>)
      .pipe(unwrap<number>())
      .subscribe({
        error: (e) => {
          expect(e).toBeInstanceOf(ApiClientError);
          expect((e as ApiClientError).code).toBe('X');
          done();
        },
      });
  });

  it('converts an HttpErrorResponse body to ApiClientError', (done) => {
    const httpErr = new HttpErrorResponse({
      status: 401,
      error: { ok: false, error: { code: 'INVALID_CREDENTIALS', message: 'bad' } },
    });
    throwError(() => httpErr)
      .pipe(unwrap<number>())
      .subscribe({
        error: (e) => {
          expect((e as ApiClientError).code).toBe('INVALID_CREDENTIALS');
          done();
        },
      });
  });
});

describe('toApiError', () => {
  it('maps status 0 to NETWORK', () => {
    const e = toApiError(new HttpErrorResponse({ status: 0 }));
    expect(e.code).toBe('NETWORK');
  });
});
```

- [ ] **Step 4: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — cannot find module `./unwrap`.

- [ ] **Step 5: Implement `unwrap`**

`frontend/src/app/core/api/unwrap.ts`:

```ts
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, catchError, map, throwError } from 'rxjs';
import { ApiResponse } from './api-response.model';
import { ApiClientError } from './api-error';

export function unwrap<T>() {
  return (source: Observable<ApiResponse<T>>): Observable<T> =>
    source.pipe(
      map((res) => {
        if (!res.ok || res.data === undefined) {
          throw new ApiClientError(
            res.error?.code ?? 'UNKNOWN',
            res.error?.message ?? 'Unbekannter Fehler',
          );
        }
        return res.data;
      }),
      catchError((err) => throwError(() => toApiError(err))),
    );
}

export function toApiError(err: unknown): ApiClientError {
  if (err instanceof ApiClientError) {
    return err;
  }
  if (err instanceof HttpErrorResponse) {
    const body = err.error as ApiResponse<unknown> | null;
    if (body && body.error) {
      return new ApiClientError(body.error.code, body.error.message);
    }
    if (err.status === 0) {
      return new ApiClientError('NETWORK', 'Netzwerkfehler — Server nicht erreichbar');
    }
    return new ApiClientError(String(err.status), err.message);
  }
  return new ApiClientError('UNKNOWN', 'Unbekannter Fehler');
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS (unwrap + toApiError specs green; existing AppComponent specs still green).

- [ ] **Step 7: Add HttpClient + animations providers**

Replace `frontend/src/app/app.config.ts` with:

```ts
import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimations } from '@angular/platform-browser/animations';

import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([])),
    provideAnimations(),
  ],
};
```

(The interceptor array is filled in Task 3.)

- [ ] **Step 8: Add dev proxy config**

`frontend/proxy.conf.json`:

```json
{
  "/api": {
    "target": "http://localhost:8080",
    "secure": false,
    "changeOrigin": true
  }
}
```

In `frontend/angular.json`, under `projects.frontend.architect.serve.configurations.development`, add the `proxyConfig` option so `ng serve` proxies `/api`:

```json
"development": {
  "buildTarget": "frontend:build:development",
  "proxyConfig": "proxy.conf.json"
}
```

- [ ] **Step 9: Verify build still works**

Run: `cd frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 10: Commit**

```bash
git add frontend/src/app/core frontend/src/app/app.config.ts frontend/proxy.conf.json frontend/angular.json
git commit -m "feat(fe): foundations — models, API envelope, http/animations providers, dev proxy

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: AuthService (signals + localStorage)

**Files:**
- Create: `frontend/src/app/core/auth/auth.models.ts`
- Create: `frontend/src/app/core/auth/auth.service.ts`
- Create: `frontend/src/app/core/auth/auth.service.spec.ts`

**Interfaces:**
- Consumes: `Role` (Task 1), `ApiResponse` + `unwrap` (Task 1).
- Produces: `interface AuthUser { token: string; username: string; role: Role; expiresAt: string }`; `AuthService` with signals `currentUser: Signal<AuthUser | null>`, computed `isAuthenticated: Signal<boolean>`, `role: Signal<Role | null>`; methods `login(username, password): Observable<AuthUser>`, `logout(): void`, `token(): string | null`.

- [ ] **Step 1: Write auth models**

`frontend/src/app/core/auth/auth.models.ts`:

```ts
import { Role } from '../models/claim.models';

export interface AuthUser {
  token: string;
  username: string;
  role: Role;
  expiresAt: string; // ISO instant
}
```

- [ ] **Step 2: Write the failing test**

`frontend/src/app/core/auth/auth.service.spec.ts`:

```ts
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
    const fresh = TestBed.inject(AuthService); // already constructed in beforeEach; re-create:
    const svc = new (AuthService as any)();
    expect(svc.isAuthenticated()).toBeTrue();
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
```

> Note: `new (AuthService as any)()` is used only to re-run the constructor's hydration against freshly-seeded storage within a test; production code always injects the service.

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — cannot find module `./auth.service`.

- [ ] **Step 4: Implement AuthService**

`frontend/src/app/core/auth/auth.service.ts`:

```ts
import { Injectable, computed, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { ApiResponse } from '../api/api-response.model';
import { unwrap } from '../api/unwrap';
import { Role } from '../models/claim.models';
import { AuthUser } from './auth.models';

const STORAGE_KEY = 'schadenflow.auth';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly _currentUser = signal<AuthUser | null>(this.loadValidSession());

  readonly currentUser = this._currentUser.asReadonly();
  readonly isAuthenticated = computed(() => this._currentUser() !== null);
  readonly role = computed(() => this._currentUser()?.role ?? null);

  // HttpClient may be undefined when constructed via `new` in a unit test that
  // only exercises hydration/logout; guard the login() path accordingly.
  constructor(private readonly http?: HttpClient) {}

  login(username: string, password: string): Observable<AuthUser> {
    return this.http!.post<ApiResponse<AuthUser>>('/api/auth/login', { username, password }).pipe(
      unwrap<AuthUser>(),
      tap((user) => this.setSession(user)),
    );
  }

  logout(): void {
    localStorage.removeItem(STORAGE_KEY);
    this._currentUser.set(null);
  }

  token(): string | null {
    return this._currentUser()?.token ?? null;
  }

  private setSession(user: AuthUser): void {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(user));
    this._currentUser.set(user);
  }

  private loadValidSession(): AuthUser | null {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    try {
      const user = JSON.parse(raw) as AuthUser;
      if (!user.expiresAt || new Date(user.expiresAt).getTime() <= Date.now()) {
        localStorage.removeItem(STORAGE_KEY);
        return null;
      }
      return user;
    } catch {
      localStorage.removeItem(STORAGE_KEY);
      return null;
    }
  }
}
```

> Marking `role` as a non-injected optional `Role` enum value: `Role` is imported only as a type here for `AuthUser`; no runtime dependency beyond `AuthUser`.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/core/auth
git commit -m "feat(fe): AuthService — signal session, login, hydrate, expiry, logout

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Auth plumbing — interceptor + guards

**Files:**
- Create: `frontend/src/app/core/auth/auth.interceptor.ts`
- Create: `frontend/src/app/core/auth/auth.interceptor.spec.ts`
- Create: `frontend/src/app/core/auth/auth.guard.ts`
- Create: `frontend/src/app/core/auth/role.guard.ts`
- Create: `frontend/src/app/core/auth/guards.spec.ts`
- Modify: `frontend/src/app/app.config.ts` (register interceptor)

**Interfaces:**
- Consumes: `AuthService` (Task 2), `Role` (Task 1).
- Produces: `authInterceptor: HttpInterceptorFn`; `authGuard: CanActivateFn`; `roleGuard(roles: Role[]): CanActivateFn`.

- [ ] **Step 1: Write the failing interceptor test**

`frontend/src/app/core/auth/auth.interceptor.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { Role } from '../models/claim.models';

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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — cannot find module `./auth.interceptor`.

- [ ] **Step 3: Implement the interceptor**

`frontend/src/app/core/auth/auth.interceptor.ts`:

```ts
import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const isLogin = req.url.endsWith('/api/auth/login');
  const token = auth.token();
  const authedReq =
    token && !isLogin
      ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : req;

  return next(authedReq).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status === 401 && !isLogin) {
        auth.logout();
        router.navigate(['/login']);
      }
      return throwError(() => err);
    }),
  );
};
```

- [ ] **Step 4: Write the failing guards test**

`frontend/src/app/core/auth/guards.spec.ts`:

```ts
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
});
```

- [ ] **Step 5: Implement the guards**

`frontend/src/app/core/auth/auth.guard.ts`:

```ts
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.isAuthenticated()) {
    return true;
  }
  return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};
```

`frontend/src/app/core/auth/role.guard.ts`:

```ts
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { Role } from '../models/claim.models';

export function roleGuard(roles: Role[]): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    const router = inject(Router);
    const role = auth.role();
    if (role && roles.includes(role)) {
      return true;
    }
    return router.createUrlTree(['/claims']);
  };
}
```

- [ ] **Step 6: Register the interceptor**

In `frontend/src/app/app.config.ts`, update the import and the `withInterceptors` array:

```ts
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { authInterceptor } from './core/auth/auth.interceptor';
// ...
    provideHttpClient(withInterceptors([authInterceptor])),
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS (interceptor + guards specs green).

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/core/auth frontend/src/app/app.config.ts
git commit -m "feat(fe): auth interceptor (Bearer + 401 redirect) and route guards

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: ClaimsService (data layer)

**Files:**
- Create: `frontend/src/app/features/claims/data/claims.service.ts`
- Create: `frontend/src/app/features/claims/data/claims.service.spec.ts`

**Interfaces:**
- Consumes: `Claim`, `AuditEntry`, `TriageResult`, `Category`, `ClaimState` (Task 1); `ApiResponse`, `Page`, `unwrap` (Task 1).
- Produces: `ClaimsService` with `interface ClaimFilters { state?: ClaimState; claimantId?: string }`; methods `list(filters: ClaimFilters, page: number, size: number): Observable<Page<Claim>>`, `getById(id): Observable<Claim>`, `create(req: { title; description; amount }): Observable<Claim>`, `transition(id, targetState, reason?): Observable<Claim>`, `triage(id): Observable<TriageResult>`, `confirmCategory(id, category, triageSummary?): Observable<Claim>`, `audit(id): Observable<AuditEntry[]>`.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/features/claims/data/claims.service.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ClaimsService } from './claims.service';
import { Category, ClaimState } from '../../../core/models/claim.models';

describe('ClaimsService', () => {
  let service: ClaimsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [ClaimsService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ClaimsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('list builds query params and unwraps the page', () => {
    service.list({ state: ClaimState.EINGEREICHT }, 0, 20).subscribe();
    const req = http.expectOne(
      (r) => r.url === '/api/claims' && r.params.get('state') === 'EINGEREICHT' &&
        r.params.get('page') === '0' && r.params.get('size') === '20',
    );
    expect(req.request.method).toBe('GET');
    req.flush({ ok: true, data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } });
  });

  it('create posts the body and unwraps the claim', () => {
    let claim: unknown;
    service.create({ title: 't', description: 'd', amount: 10 }).subscribe((c) => (claim = c));
    const req = http.expectOne('/api/claims');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ title: 't', description: 'd', amount: 10 });
    req.flush({ ok: true, data: { id: '1', title: 't' } });
    expect((claim as any).id).toBe('1');
  });

  it('transition posts targetState and reason', () => {
    service.transition('1', ClaimState.ABGELEHNT, 'nope').subscribe();
    const req = http.expectOne('/api/claims/1/transitions');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ targetState: 'ABGELEHNT', reason: 'nope' });
    req.flush({ ok: true, data: { id: '1' } });
  });

  it('triage posts to the triage endpoint', () => {
    service.triage('1').subscribe();
    const req = http.expectOne('/api/claims/1/triage');
    expect(req.request.method).toBe('POST');
    req.flush({ ok: true, data: { summary: 's', suggestedCategory: 'ZAHNARZT', missingInfoFlags: [] } });
  });

  it('confirmCategory PATCHes category and summary', () => {
    service.confirmCategory('1', Category.ZAHNARZT, 'sum').subscribe();
    const req = http.expectOne('/api/claims/1');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ category: 'ZAHNARZT', triageSummary: 'sum' });
    req.flush({ ok: true, data: { id: '1' } });
  });

  it('audit GETs the audit list', () => {
    service.audit('1').subscribe();
    const req = http.expectOne('/api/claims/1/audit');
    expect(req.request.method).toBe('GET');
    req.flush({ ok: true, data: [] });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — cannot find module `./claims.service`.

- [ ] **Step 3: Implement ClaimsService**

`frontend/src/app/features/claims/data/claims.service.ts`:

```ts
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ApiResponse, Page } from '../../../core/api/api-response.model';
import { unwrap } from '../../../core/api/unwrap';
import { AuditEntry, Category, Claim, ClaimState, TriageResult } from '../../../core/models/claim.models';

export interface ClaimFilters {
  state?: ClaimState;
  claimantId?: string;
}

const BASE = '/api/claims';

@Injectable({ providedIn: 'root' })
export class ClaimsService {
  private readonly http = inject(HttpClient);

  list(filters: ClaimFilters, page: number, size: number): Observable<Page<Claim>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (filters.state) {
      params = params.set('state', filters.state);
    }
    if (filters.claimantId) {
      params = params.set('claimantId', filters.claimantId);
    }
    return this.http.get<ApiResponse<Page<Claim>>>(BASE, { params }).pipe(unwrap<Page<Claim>>());
  }

  getById(id: string): Observable<Claim> {
    return this.http.get<ApiResponse<Claim>>(`${BASE}/${id}`).pipe(unwrap<Claim>());
  }

  create(req: { title: string; description: string; amount: number }): Observable<Claim> {
    return this.http.post<ApiResponse<Claim>>(BASE, req).pipe(unwrap<Claim>());
  }

  transition(id: string, targetState: ClaimState, reason?: string): Observable<Claim> {
    return this.http
      .post<ApiResponse<Claim>>(`${BASE}/${id}/transitions`, { targetState, reason })
      .pipe(unwrap<Claim>());
  }

  triage(id: string): Observable<TriageResult> {
    return this.http.post<ApiResponse<TriageResult>>(`${BASE}/${id}/triage`, {}).pipe(unwrap<TriageResult>());
  }

  confirmCategory(id: string, category: Category, triageSummary?: string): Observable<Claim> {
    return this.http
      .patch<ApiResponse<Claim>>(`${BASE}/${id}`, { category, triageSummary })
      .pipe(unwrap<Claim>());
  }

  audit(id: string): Observable<AuditEntry[]> {
    return this.http.get<ApiResponse<AuditEntry[]>>(`${BASE}/${id}/audit`).pipe(unwrap<AuditEntry[]>());
  }
}
```

> The `transition` test sends `{targetState, reason}`; when `reason` is omitted the body carries `reason: undefined`, which Angular drops from the JSON — matching the backend's optional field.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/claims/data
git commit -m "feat(fe): ClaimsService — list/detail/create/transition/triage/confirm/audit

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Shared UI — label pipes, transitions map, snackbar helper, confirm dialog

**Files:**
- Create: `frontend/src/app/shared/claim-labels.ts`
- Create: `frontend/src/app/shared/claim-labels.spec.ts`
- Create: `frontend/src/app/shared/claim-state.pipe.ts`
- Create: `frontend/src/app/shared/category.pipe.ts`
- Create: `frontend/src/app/shared/transitions.ts`
- Create: `frontend/src/app/shared/notify.service.ts`
- Create: `frontend/src/app/shared/confirm-dialog.component.ts`

**Interfaces:**
- Consumes: `ClaimState`, `Category`, `Role`, `MissingInfoFlag` (Task 1); `ApiClientError` (Task 1).
- Produces: `claimStateLabel(s): string`, `claimStateColor(s): 'primary'|'accent'|'warn'|''`, `categoryLabel(c): string`, `flagLabel(f): string`, `errorMessage(err): string`; `ClaimStatePipe`, `CategoryPipe`; `interface TransitionOption { target: ClaimState; label: string; requiresReason: boolean }`; `availableTransitions(from: ClaimState, role: Role): TransitionOption[]`; `NotifyService` with `error(msg)` / `success(msg)`; `ConfirmDialogComponent` + `interface ConfirmDialogData { title; message; confirmLabel?; requireReason?: boolean }` returning `{ confirmed: boolean; reason?: string }`.

- [ ] **Step 1: Write the failing labels/transitions test**

`frontend/src/app/shared/claim-labels.spec.ts`:

```ts
import { ClaimState, Role, Category } from '../core/models/claim.models';
import { ApiClientError } from '../core/api/api-error';
import { claimStateLabel, categoryLabel, errorMessage } from './claim-labels';
import { availableTransitions } from './transitions';

describe('claim-labels', () => {
  it('maps states to German labels', () => {
    expect(claimStateLabel(ClaimState.IN_PRUEFUNG)).toBe('In Prüfung');
    expect(claimStateLabel(ClaimState.AUSBEZAHLT)).toBe('Ausbezahlt');
  });

  it('maps categories to labels', () => {
    expect(categoryLabel(Category.ZAHNARZT)).toBe('Zahnarzt');
  });

  it('maps known error codes to friendly messages', () => {
    expect(errorMessage(new ApiClientError('INVALID_CREDENTIALS', 'x'))).toContain('Passwort');
    expect(errorMessage(new ApiClientError('TRIAGE_UNAVAILABLE', 'x'))).toContain('KI-Triage');
  });
});

describe('availableTransitions', () => {
  it('offers In-Prüfung for a reviewer on EINGEREICHT', () => {
    const t = availableTransitions(ClaimState.EINGEREICHT, Role.SACHBEARBEITER);
    expect(t.map((o) => o.target)).toEqual([ClaimState.IN_PRUEFUNG]);
  });

  it('offers approve+reject (reason) for a reviewer on IN_PRUEFUNG', () => {
    const t = availableTransitions(ClaimState.IN_PRUEFUNG, Role.SACHBEARBEITER);
    expect(t.map((o) => o.target).sort()).toEqual([ClaimState.ABGELEHNT, ClaimState.GENEHMIGT].sort());
    expect(t.find((o) => o.target === ClaimState.ABGELEHNT)!.requiresReason).toBeTrue();
  });

  it('offers Auszahlen only for an admin on GENEHMIGT', () => {
    expect(availableTransitions(ClaimState.GENEHMIGT, Role.SACHBEARBEITER)).toEqual([]);
    expect(availableTransitions(ClaimState.GENEHMIGT, Role.ADMIN).map((o) => o.target)).toEqual([
      ClaimState.AUSBEZAHLT,
    ]);
  });

  it('offers nothing to a claimant', () => {
    expect(availableTransitions(ClaimState.EINGEREICHT, Role.ANSPRUCHSTELLER)).toEqual([]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — cannot find module `./claim-labels`.

- [ ] **Step 3: Implement labels + transitions**

`frontend/src/app/shared/claim-labels.ts`:

```ts
import { ApiClientError } from '../core/api/api-error';
import { Category, ClaimState, MissingInfoFlag, Role } from '../core/models/claim.models';

const STATE_LABELS: Record<ClaimState, string> = {
  [ClaimState.EINGEREICHT]: 'Eingereicht',
  [ClaimState.IN_PRUEFUNG]: 'In Prüfung',
  [ClaimState.GENEHMIGT]: 'Genehmigt',
  [ClaimState.ABGELEHNT]: 'Abgelehnt',
  [ClaimState.AUSBEZAHLT]: 'Ausbezahlt',
};

const STATE_COLORS: Record<ClaimState, 'primary' | 'accent' | 'warn' | ''> = {
  [ClaimState.EINGEREICHT]: 'accent',
  [ClaimState.IN_PRUEFUNG]: 'primary',
  [ClaimState.GENEHMIGT]: 'primary',
  [ClaimState.ABGELEHNT]: 'warn',
  [ClaimState.AUSBEZAHLT]: '',
};

const CATEGORY_LABELS: Record<Category, string> = {
  [Category.ARZTKOSTEN]: 'Arztkosten',
  [Category.MEDIKAMENTE]: 'Medikamente',
  [Category.SPITAL]: 'Spital',
  [Category.ZAHNARZT]: 'Zahnarzt',
  [Category.THERAPIE]: 'Therapie',
  [Category.HILFSMITTEL]: 'Hilfsmittel',
  [Category.SONSTIGES]: 'Sonstiges',
};

const FLAG_LABELS: Record<MissingInfoFlag, string> = {
  [MissingInfoFlag.MISSING_AMOUNT]: 'Betrag fehlt',
  [MissingInfoFlag.VAGUE_DESCRIPTION]: 'Vage Beschreibung',
  [MissingInfoFlag.MISSING_DATE]: 'Datum fehlt',
  [MissingInfoFlag.MISSING_PROVIDER]: 'Leistungserbringer fehlt',
};

const ERROR_MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'Benutzername oder Passwort ist falsch.',
  UNAUTHORIZED: 'Bitte melden Sie sich an.',
  FORBIDDEN: 'Keine Berechtigung für diese Aktion.',
  NOT_FOUND: 'Der Schadenfall wurde nicht gefunden.',
  ILLEGAL_TRANSITION: 'Dieser Statuswechsel ist nicht erlaubt.',
  VALIDATION_ERROR: 'Die Eingaben sind ungültig.',
  TRIAGE_UNAVAILABLE: 'Die KI-Triage ist derzeit nicht verfügbar.',
  NETWORK: 'Netzwerkfehler — der Server ist nicht erreichbar.',
};

export const ALL_CATEGORIES: Category[] = Object.values(Category);
export const ALL_STATES: ClaimState[] = Object.values(ClaimState);

export function claimStateLabel(s: ClaimState): string {
  return STATE_LABELS[s] ?? s;
}
export function claimStateColor(s: ClaimState): 'primary' | 'accent' | 'warn' | '' {
  return STATE_COLORS[s] ?? '';
}
export function categoryLabel(c: Category): string {
  return CATEGORY_LABELS[c] ?? c;
}
export function flagLabel(f: MissingInfoFlag): string {
  return FLAG_LABELS[f] ?? f;
}
export function roleLabel(r: Role): string {
  return r.charAt(0) + r.slice(1).toLowerCase();
}
export function errorMessage(err: unknown): string {
  if (err instanceof ApiClientError) {
    return ERROR_MESSAGES[err.code] ?? err.message ?? 'Ein Fehler ist aufgetreten.';
  }
  return 'Ein unerwarteter Fehler ist aufgetreten.';
}
```

`frontend/src/app/shared/transitions.ts`:

```ts
import { ClaimState, Role } from '../core/models/claim.models';
import { claimStateLabel } from './claim-labels';

export interface TransitionOption {
  target: ClaimState;
  label: string;
  requiresReason: boolean;
}

interface Edge {
  from: ClaimState;
  to: ClaimState;
  roles: Role[];
  label: string;
  requiresReason: boolean;
}

const EDGES: Edge[] = [
  { from: ClaimState.EINGEREICHT, to: ClaimState.IN_PRUEFUNG, roles: [Role.SACHBEARBEITER, Role.ADMIN], label: 'In Prüfung nehmen', requiresReason: false },
  { from: ClaimState.IN_PRUEFUNG, to: ClaimState.GENEHMIGT, roles: [Role.SACHBEARBEITER, Role.ADMIN], label: 'Genehmigen', requiresReason: false },
  { from: ClaimState.IN_PRUEFUNG, to: ClaimState.ABGELEHNT, roles: [Role.SACHBEARBEITER, Role.ADMIN], label: 'Ablehnen', requiresReason: true },
  { from: ClaimState.GENEHMIGT, to: ClaimState.AUSBEZAHLT, roles: [Role.ADMIN], label: 'Auszahlen', requiresReason: false },
];

export function availableTransitions(from: ClaimState, role: Role): TransitionOption[] {
  return EDGES.filter((e) => e.from === from && e.roles.includes(role)).map((e) => ({
    target: e.to,
    label: e.label,
    requiresReason: e.requiresReason,
  }));
}

export function transitionLabelFor(target: ClaimState): string {
  return claimStateLabel(target);
}
```

- [ ] **Step 4: Implement the pipes, notify service, and confirm dialog**

`frontend/src/app/shared/claim-state.pipe.ts`:

```ts
import { Pipe, PipeTransform } from '@angular/core';
import { ClaimState } from '../core/models/claim.models';
import { claimStateLabel } from './claim-labels';

@Pipe({ name: 'claimState', standalone: true })
export class ClaimStatePipe implements PipeTransform {
  transform(value: ClaimState): string {
    return claimStateLabel(value);
  }
}
```

`frontend/src/app/shared/category.pipe.ts`:

```ts
import { Pipe, PipeTransform } from '@angular/core';
import { Category } from '../core/models/claim.models';
import { categoryLabel } from './claim-labels';

@Pipe({ name: 'category', standalone: true })
export class CategoryPipe implements PipeTransform {
  transform(value: Category | null): string {
    return value ? categoryLabel(value) : '—';
  }
}
```

`frontend/src/app/shared/notify.service.ts`:

```ts
import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

@Injectable({ providedIn: 'root' })
export class NotifyService {
  private readonly snackBar = inject(MatSnackBar);

  error(message: string): void {
    this.snackBar.open(message, 'OK', { duration: 5000, panelClass: 'snack-error' });
  }

  success(message: string): void {
    this.snackBar.open(message, 'OK', { duration: 3000 });
  }
}
```

`frontend/src/app/shared/confirm-dialog.component.ts`:

```ts
import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

export interface ConfirmDialogData {
  title: string;
  message: string;
  confirmLabel?: string;
  requireReason?: boolean;
}

export interface ConfirmDialogResult {
  confirmed: boolean;
  reason?: string;
}

@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
      @if (data.requireReason) {
        <mat-form-field appearance="outline" style="width: 100%">
          <mat-label>Begründung</mat-label>
          <textarea matInput [(ngModel)]="reason" rows="3"></textarea>
        </mat-form-field>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="cancel()">Abbrechen</button>
      <button
        mat-raised-button
        color="primary"
        [disabled]="data.requireReason && !reason.trim()"
        (click)="confirm()"
      >
        {{ data.confirmLabel ?? 'Bestätigen' }}
      </button>
    </mat-dialog-actions>
  `,
})
export class ConfirmDialogComponent {
  readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<ConfirmDialogComponent, ConfirmDialogResult>);
  reason = '';

  confirm(): void {
    this.ref.close({ confirmed: true, reason: this.reason.trim() || undefined });
  }

  cancel(): void {
    this.ref.close({ confirmed: false });
  }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/shared
git commit -m "feat(fe): shared UI — label pipes, transition map, notify service, confirm dialog

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Login + routing + AppComponent shell host

**Files:**
- Create: `frontend/src/app/features/auth/login.component.ts`
- Create: `frontend/src/app/features/auth/login.component.spec.ts`
- Create: `frontend/src/app/features/claims/dashboard/claim-dashboard.component.ts` (minimal placeholder; fleshed out in Task 8)
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/app.component.ts`
- Modify: `frontend/src/app/app.component.html`
- Modify: `frontend/src/app/app.component.spec.ts` (existing spec asserts old placeholder markup — must be updated)
- Delete: `frontend/src/app/app.component.scss` usage of placeholder styles (leave file, may be empty)

**Interfaces:**
- Consumes: `AuthService` (Task 2), `NotifyService` + `errorMessage` (Task 5), `authGuard`/`roleGuard` (Task 3), `Role` (Task 1).
- Produces: `LoginComponent` (route `/login`); `routes` array; placeholder `ClaimDashboardComponent` (route `/claims`).

- [ ] **Step 1: Write the failing login test**

`frontend/src/app/features/auth/login.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
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
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — cannot find module `./login.component`.

- [ ] **Step 3: Implement LoginComponent**

`frontend/src/app/features/auth/login.component.ts`:

```ts
import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { AuthService } from '../../core/auth/auth.service';
import { NotifyService } from '../../shared/notify.service';
import { errorMessage } from '../../shared/claim-labels';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [
    ReactiveFormsModule, RouterLink, MatCardModule, MatFormFieldModule,
    MatInputModule, MatButtonModule, MatProgressBarModule,
  ],
  template: `
    <div class="login-wrap">
      <mat-card class="login-card">
        <mat-card-header><mat-card-title>Schadenflow — Anmeldung</mat-card-title></mat-card-header>
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
        <mat-card-content>
          <form [formGroup]="form" (ngSubmit)="submit()">
            <mat-form-field appearance="outline" style="width:100%">
              <mat-label>Benutzername</mat-label>
              <input matInput formControlName="username" autocomplete="username" />
            </mat-form-field>
            <mat-form-field appearance="outline" style="width:100%">
              <mat-label>Passwort</mat-label>
              <input matInput type="password" formControlName="password" autocomplete="current-password" />
            </mat-form-field>
            <button mat-raised-button color="primary" type="submit" [disabled]="loading()" style="width:100%">
              Anmelden
            </button>
          </form>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .login-wrap { display:flex; justify-content:center; align-items:center; min-height:80vh; padding:1rem; }
    .login-card { width:100%; max-width:380px; }
  `],
})
export class LoginComponent {
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly auth = inject(AuthService);
  private readonly notify = inject(NotifyService);
  private readonly router = inject(Router);

  readonly loading = signal(false);
  readonly form = this.fb.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { username, password } = this.form.getRawValue();
    this.loading.set(true);
    this.auth.login(username, password).subscribe({
      next: () => {
        this.loading.set(false);
        this.router.navigateByUrl('/claims');
      },
      error: (err) => {
        this.loading.set(false);
        this.notify.error(errorMessage(err));
      },
    });
  }
}
```

> `RouterLink` is imported for consistency though the template has no link yet; remove it if your linter flags it as unused.

- [ ] **Step 4: Add a minimal dashboard placeholder (real version in Task 8)**

`frontend/src/app/features/claims/dashboard/claim-dashboard.component.ts`:

```ts
import { Component } from '@angular/core';

@Component({
  selector: 'app-claim-dashboard',
  standalone: true,
  template: `<p>Dashboard</p>`,
})
export class ClaimDashboardComponent {}
```

- [ ] **Step 5: Wire routes**

Replace `frontend/src/app/app.routes.ts`:

```ts
import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { roleGuard } from './core/auth/role.guard';
import { Role } from './core/models/claim.models';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'claims',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/claims/dashboard/claim-dashboard.component').then(
        (m) => m.ClaimDashboardComponent,
      ),
  },
  {
    path: 'claims/new',
    canActivate: [authGuard, roleGuard([Role.ANSPRUCHSTELLER])],
    loadComponent: () =>
      import('./features/claims/dashboard/claim-dashboard.component').then(
        (m) => m.ClaimDashboardComponent,
      ),
  },
  {
    path: 'claims/:id',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/claims/dashboard/claim-dashboard.component').then(
        (m) => m.ClaimDashboardComponent,
      ),
  },
  { path: '', pathMatch: 'full', redirectTo: 'claims' },
  { path: '**', redirectTo: 'claims' },
];
```

> `claims/new` and `claims/:id` temporarily load the dashboard placeholder; Tasks 7–9 replace them with the create and detail components.

- [ ] **Step 6: Replace AppComponent to host the router outlet**

`frontend/src/app/app.component.ts`:

```ts
import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent {
  title = 'schadenflow';
}
```

`frontend/src/app/app.component.html`:

```html
<router-outlet></router-outlet>
```

- [ ] **Step 7: Update the existing AppComponent spec**

The scaffolded spec asserts the old `<h1>schadenflow</h1>` markup, which no longer exists. Replace `frontend/src/app/app.component.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app.component';

describe('AppComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideRouter([])],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders a router outlet', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('router-outlet')).not.toBeNull();
  });
});
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS (login + app specs green; full suite green).

- [ ] **Step 9: Commit**

```bash
git add frontend/src/app/features/auth frontend/src/app/features/claims/dashboard \
  frontend/src/app/app.routes.ts frontend/src/app/app.component.ts \
  frontend/src/app/app.component.html frontend/src/app/app.component.spec.ts
git commit -m "feat(fe): login screen, guarded routes, router-outlet host

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: App shell (toolbar + role-aware nav + logout)

**Files:**
- Create: `frontend/src/app/features/shell/app-shell.component.ts`
- Create: `frontend/src/app/features/shell/app-shell.component.spec.ts`
- Modify: `frontend/src/app/app.routes.ts` (wrap authenticated routes in the shell as a layout route)

**Interfaces:**
- Consumes: `AuthService` (Task 2), `roleLabel` (Task 5), `Role` (Task 1).
- Produces: `AppShellComponent` (layout component with a nested `<router-outlet>`).

- [ ] **Step 1: Write the failing shell test**

`frontend/src/app/features/shell/app-shell.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { AppShellComponent } from './app-shell.component';
import { AuthService } from '../../core/auth/auth.service';
import { Role } from '../../core/models/claim.models';

describe('AppShellComponent', () => {
  let fixture: ComponentFixture<AppShellComponent>;
  let auth: Partial<AuthService>;

  function setup(role: Role) {
    auth = {
      currentUser: signal({ token: 't', username: 'u', role, expiresAt: '' }) as any,
      role: signal(role) as any,
      logout: jasmine.createSpy('logout'),
    };
    TestBed.configureTestingModule({
      imports: [AppShellComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    fixture = TestBed.createComponent(AppShellComponent);
    fixture.detectChanges();
  }

  it('shows the "Neuer Schaden" action for a claimant', () => {
    setup(Role.ANSPRUCHSTELLER);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Neuer Schaden');
  });

  it('hides the "Neuer Schaden" action for a reviewer', () => {
    setup(Role.SACHBEARBEITER);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('Neuer Schaden');
  });

  it('calls logout when the logout button is clicked', () => {
    setup(Role.ADMIN);
    const btn = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-test="logout"]',
    ) as HTMLButtonElement;
    btn.click();
    expect(auth.logout).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — cannot find module `./app-shell.component`.

- [ ] **Step 3: Implement AppShellComponent**

`frontend/src/app/features/shell/app-shell.component.ts`:

```ts
import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from '../../core/auth/auth.service';
import { roleLabel } from '../../shared/claim-labels';
import { Role } from '../../core/models/claim.models';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterLink, RouterOutlet, MatToolbarModule, MatButtonModule, MatIconModule],
  template: `
    <mat-toolbar color="primary">
      <a routerLink="/claims" style="color:inherit;text-decoration:none;font-weight:500">Schadenflow</a>
      <span style="flex:1 1 auto"></span>
      @if (isClaimant()) {
        <a mat-button routerLink="/claims/new">Neuer Schaden</a>
      }
      <span style="margin:0 12px">{{ user()?.username }} ({{ roleText() }})</span>
      <button mat-icon-button data-test="logout" (click)="logout()" aria-label="Abmelden">
        <mat-icon>logout</mat-icon>
      </button>
    </mat-toolbar>
    <main style="padding:1.5rem; max-width:1100px; margin:0 auto">
      <router-outlet></router-outlet>
    </main>
  `,
})
export class AppShellComponent {
  private readonly auth = inject(AuthService);
  readonly user = this.auth.currentUser;
  readonly isClaimant = computed(() => this.auth.role() === Role.ANSPRUCHSTELLER);
  readonly roleText = computed(() => {
    const r = this.auth.role();
    return r ? roleLabel(r) : '';
  });

  logout(): void {
    this.auth.logout();
  }
}
```

- [ ] **Step 4: Nest authenticated routes under the shell**

Replace `frontend/src/app/app.routes.ts` so `/claims*` render inside the shell (login stays outside it):

```ts
import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { roleGuard } from './core/auth/role.guard';
import { Role } from './core/models/claim.models';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login.component').then((m) => m.LoginComponent),
  },
  {
    path: '',
    loadComponent: () =>
      import('./features/shell/app-shell.component').then((m) => m.AppShellComponent),
    canActivate: [authGuard],
    children: [
      {
        path: 'claims',
        loadComponent: () =>
          import('./features/claims/dashboard/claim-dashboard.component').then(
            (m) => m.ClaimDashboardComponent,
          ),
      },
      {
        path: 'claims/new',
        canActivate: [roleGuard([Role.ANSPRUCHSTELLER])],
        loadComponent: () =>
          import('./features/claims/dashboard/claim-dashboard.component').then(
            (m) => m.ClaimDashboardComponent,
          ),
      },
      {
        path: 'claims/:id',
        loadComponent: () =>
          import('./features/claims/dashboard/claim-dashboard.component').then(
            (m) => m.ClaimDashboardComponent,
          ),
      },
      { path: '', pathMatch: 'full', redirectTo: 'claims' },
    ],
  },
  { path: '**', redirectTo: 'claims' },
];
```

> `claims/new` and `claims/:id` still point at the dashboard placeholder; Tasks 8–9 swap in the real components.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/features/shell frontend/src/app/app.routes.ts
git commit -m "feat(fe): app shell — toolbar, role-aware nav, logout

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Claim dashboard (table, filters, pagination)

**Files:**
- Modify: `frontend/src/app/features/claims/dashboard/claim-dashboard.component.ts` (replace placeholder)
- Create: `frontend/src/app/features/claims/dashboard/claim-dashboard.component.spec.ts`

**Interfaces:**
- Consumes: `ClaimsService` (Task 4), `AuthService` (Task 2), `NotifyService`/`errorMessage`/`ClaimStatePipe`/`CategoryPipe`/`claimStateColor`/`ALL_STATES` (Task 5), `Role`, `ClaimState`, `Page`, `Claim` (Task 1).
- Produces: `ClaimDashboardComponent` (full table). No new exported API.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/features/claims/dashboard/claim-dashboard.component.spec.ts`:

```ts
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
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — assertions about rows/filters fail against the placeholder.

- [ ] **Step 3: Implement the dashboard**

Replace `frontend/src/app/features/claims/dashboard/claim-dashboard.component.ts`:

```ts
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

  stateColor(s: ClaimState) {
    return claimStateColor(s);
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/claims/dashboard
git commit -m "feat(fe): claim dashboard — table, role-aware state filter, pagination

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Claim create (claimant form)

**Files:**
- Create: `frontend/src/app/features/claims/create/claim-create.component.ts`
- Create: `frontend/src/app/features/claims/create/claim-create.component.spec.ts`
- Modify: `frontend/src/app/app.routes.ts` (point `claims/new` at the create component)

**Interfaces:**
- Consumes: `ClaimsService` (Task 4), `NotifyService`/`errorMessage` (Task 5).
- Produces: `ClaimCreateComponent` (route `/claims/new`).

- [ ] **Step 1: Write the failing test**

`frontend/src/app/features/claims/create/claim-create.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ClaimCreateComponent } from './claim-create.component';
import { ClaimsService } from '../data/claims.service';
import { NotifyService } from '../../../shared/notify.service';

describe('ClaimCreateComponent', () => {
  let fixture: ComponentFixture<ClaimCreateComponent>;
  let component: ClaimCreateComponent;
  let claims: jasmine.SpyObj<ClaimsService>;
  let router: Router;

  beforeEach(async () => {
    claims = jasmine.createSpyObj<ClaimsService>('ClaimsService', ['create']);
    await TestBed.configureTestingModule({
      imports: [ClaimCreateComponent],
      providers: [
        provideRouter([]),
        provideAnimations(),
        { provide: ClaimsService, useValue: claims },
        { provide: NotifyService, useValue: jasmine.createSpyObj('NotifyService', ['error', 'success']) },
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
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — cannot find module `./claim-create.component`.

- [ ] **Step 3: Implement the create component**

`frontend/src/app/features/claims/create/claim-create.component.ts`:

```ts
import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { ClaimsService } from '../data/claims.service';
import { NotifyService } from '../../../shared/notify.service';
import { errorMessage } from '../../../shared/claim-labels';

@Component({
  selector: 'app-claim-create',
  standalone: true,
  imports: [
    ReactiveFormsModule, MatCardModule, MatFormFieldModule, MatInputModule, MatButtonModule,
  ],
  template: `
    <h1>Neuer Schadenfall</h1>
    <mat-card style="max-width:640px">
      <mat-card-content>
        <form [formGroup]="form" (ngSubmit)="submit()">
          <mat-form-field appearance="outline" style="width:100%">
            <mat-label>Titel</mat-label>
            <input matInput formControlName="title" maxlength="200" />
          </mat-form-field>
          <mat-form-field appearance="outline" style="width:100%">
            <mat-label>Beschreibung</mat-label>
            <textarea matInput formControlName="description" rows="5" maxlength="5000"></textarea>
          </mat-form-field>
          <mat-form-field appearance="outline" style="width:100%">
            <mat-label>Betrag (CHF)</mat-label>
            <input matInput type="number" formControlName="amount" min="0" step="0.01" />
          </mat-form-field>
          <button mat-raised-button color="primary" type="submit" [disabled]="saving()">
            Einreichen
          </button>
        </form>
      </mat-card-content>
    </mat-card>
  `,
})
export class ClaimCreateComponent {
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly claims = inject(ClaimsService);
  private readonly notify = inject(NotifyService);
  private readonly router = inject(Router);

  readonly saving = signal(false);
  readonly form = this.fb.group({
    title: ['', [Validators.required, Validators.maxLength(200)]],
    description: ['', [Validators.required, Validators.maxLength(5000)]],
    amount: [null as number | null, [Validators.required, Validators.min(0)]],
  });

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { title, description, amount } = this.form.getRawValue();
    this.saving.set(true);
    this.claims.create({ title, description, amount: amount! }).subscribe({
      next: (claim) => {
        this.saving.set(false);
        this.notify.success('Schadenfall eingereicht.');
        this.router.navigate(['/claims', claim.id]);
      },
      error: (err) => {
        this.saving.set(false);
        this.notify.error(errorMessage(err));
      },
    });
  }
}
```

- [ ] **Step 4: Point the route at the create component**

In `frontend/src/app/app.routes.ts`, change the `claims/new` child's `loadComponent` to:

```ts
        loadComponent: () =>
          import('./features/claims/create/claim-create.component').then(
            (m) => m.ClaimCreateComponent,
          ),
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/features/claims/create frontend/src/app/app.routes.ts
git commit -m "feat(fe): claim create form (claimant)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: Claim detail — header + workflow actions + reject dialog

**Files:**
- Create: `frontend/src/app/features/claims/detail/claim-detail.component.ts`
- Create: `frontend/src/app/features/claims/detail/claim-detail.component.spec.ts`
- Modify: `frontend/src/app/app.routes.ts` (point `claims/:id` at the detail component)

**Interfaces:**
- Consumes: `ClaimsService` (Task 4), `AuthService` (Task 2), `NotifyService`/`errorMessage`/`availableTransitions`/`TransitionOption`/`ClaimStatePipe`/`CategoryPipe`/`claimStateColor` (Task 5), `ConfirmDialogComponent` (Task 5), `MatDialog`, `Claim`, `ClaimState`, `Role` (Task 1), `ActivatedRoute`.
- Produces: `ClaimDetailComponent` (route `/claims/:id`). Loads the claim into a `claim` signal; exposes `transitions()` and `runTransition(option)`. Tasks 11–12 extend this component.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/features/claims/detail/claim-detail.component.spec.ts`:

```ts
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — cannot find module `./claim-detail.component`.

- [ ] **Step 3: Implement the detail component (header + workflow actions)**

`frontend/src/app/features/claims/detail/claim-detail.component.ts`:

```ts
import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { ClaimsService } from '../data/claims.service';
import { AuthService } from '../../../core/auth/auth.service';
import { NotifyService } from '../../../shared/notify.service';
import { errorMessage, claimStateColor } from '../../../shared/claim-labels';
import { availableTransitions, TransitionOption } from '../../../shared/transitions';
import { ClaimStatePipe } from '../../../shared/claim-state.pipe';
import { CategoryPipe } from '../../../shared/category.pipe';
import {
  ConfirmDialogComponent, ConfirmDialogData, ConfirmDialogResult,
} from '../../../shared/confirm-dialog.component';
import { Claim, ClaimState } from '../../../core/models/claim.models';

@Component({
  selector: 'app-claim-detail',
  standalone: true,
  imports: [
    CurrencyPipe, DatePipe, MatCardModule, MatButtonModule, MatChipsModule, MatDialogModule,
    ClaimStatePipe, CategoryPipe,
  ],
  template: `
    @if (claim(); as c) {
      <a href="/claims" style="display:inline-block;margin-bottom:1rem">&larr; Zurück</a>
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

  constructor() {
    this.reload();
  }

  stateColor(s: ClaimState) {
    return claimStateColor(s);
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
    this.claims.transition(this.id, target, reason).subscribe({
      next: (updated) => {
        this.claim.set(updated);
        this.notify.success('Status aktualisiert.');
      },
      error: (err) => this.notify.error(errorMessage(err)),
    });
  }

  private reload(): void {
    this.claims.getById(this.id).subscribe({
      next: (c) => this.claim.set(c),
      error: (err) => this.notify.error(errorMessage(err)),
    });
  }
}
```

> The reject dialog reuses `ConfirmDialogComponent` with `requireReason: true`; its confirm button stays disabled until a reason is typed (enforced in the dialog, Task 5).

- [ ] **Step 4: Point the route at the detail component**

In `frontend/src/app/app.routes.ts`, change the `claims/:id` child's `loadComponent` to:

```ts
        loadComponent: () =>
          import('./features/claims/detail/claim-detail.component').then(
            (m) => m.ClaimDetailComponent,
          ),
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/features/claims/detail frontend/src/app/app.routes.ts
git commit -m "feat(fe): claim detail — header + role/state workflow actions + reject reason dialog

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: Claim detail — AI-triage / confirm panel

**Files:**
- Modify: `frontend/src/app/features/claims/detail/claim-detail.component.ts`
- Modify: `frontend/src/app/features/claims/detail/claim-detail.component.spec.ts` (add cases)

**Interfaces:**
- Consumes: existing detail component; `ClaimsService.triage`/`confirmCategory` (Task 4); `flagLabel`/`categoryLabel`/`ALL_CATEGORIES` (Task 5); `TriageResult`, `Category` (Task 1).
- Produces: panel behaviour `requestTriage()`, `confirmCategory()`, signals `triage`, `selectedCategory`, `confirmSummary`; computed `canTriage()` (reviewer + pre-decision state).

- [ ] **Step 1: Add failing tests for the triage/confirm panel**

Append to `frontend/src/app/features/claims/detail/claim-detail.component.spec.ts` (inside the top-level `describe`):

```ts
  it('exposes triage only for a reviewer on a pre-decision state', () => {
    setup(Role.SACHBEARBEITER, ClaimState.EINGEREICHT);
    expect(fixture.componentInstance.canTriage()).toBeTrue();
    setup(Role.SACHBEARBEITER, ClaimState.GENEHMIGT);
    expect(fixture.componentInstance.canTriage()).toBeFalse();
    setup(Role.ANSPRUCHSTELLER, ClaimState.EINGEREICHT);
    expect(fixture.componentInstance.canTriage()).toBeFalse();
  });

  it('requesting triage stores the advisory result without applying it', () => {
    setup(Role.SACHBEARBEITER, ClaimState.EINGEREICHT);
    claims.triage.and.returnValue(
      of({ summary: 'Zahn-Summary', suggestedCategory: Category.ZAHNARZT, missingInfoFlags: [] }),
    );
    fixture.componentInstance.requestTriage();
    expect(claims.triage).toHaveBeenCalledWith('1');
    expect(fixture.componentInstance.triage()!.summary).toBe('Zahn-Summary');
    // nothing persisted yet:
    expect(claims.confirmCategory).not.toHaveBeenCalled();
    expect(fixture.componentInstance.claim()!.category).toBe(Category.ZAHNARZT); // unchanged seed value
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — `canTriage`/`requestTriage`/`confirmCategory` not defined.

- [ ] **Step 3: Extend the detail component**

In `frontend/src/app/features/claims/detail/claim-detail.component.ts`:

Add imports:

```ts
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { flagLabel, categoryLabel, ALL_CATEGORIES } from '../../../shared/claim-labels';
import { Category, TriageResult } from '../../../core/models/claim.models';
```

Add these to the `imports` array of the `@Component`:
`FormsModule, MatFormFieldModule, MatSelectModule, MatInputModule, MatProgressBarModule`.

Add the panel to the template, immediately after the workflow `@if (transitions().length) { ... }` block and before the closing `</mat-card>`:

```html
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
```

Add to the class body:

```ts
  readonly allCategories = ALL_CATEGORIES;
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

  flagText(f: MissingInfoFlag) {
    return flagLabel(f);
  }
  categoryText(c: Category) {
    return categoryLabel(c);
  }

  requestTriage(): void {
    this.triageLoading.set(true);
    this.claims.triage(this.id).subscribe({
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
    this.claims.confirmCategory(this.id, category, this.confirmSummary() || undefined).subscribe({
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
```

Update the `Role`/`ClaimState` import line and add `MissingInfoFlag`:

```ts
import { Claim, ClaimState, Role, MissingInfoFlag, Category } from '../../../core/models/claim.models';
```

(Remove the now-duplicate `Category, TriageResult` import added above if your linter flags duplicates — keep a single import line per module.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/claims/detail
git commit -m "feat(fe): claim detail — AI-triage advisory panel + human confirm (never auto-applied)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: Claim detail — audit trail

**Files:**
- Modify: `frontend/src/app/features/claims/detail/claim-detail.component.ts`
- Modify: `frontend/src/app/features/claims/detail/claim-detail.component.spec.ts` (add a case)

**Interfaces:**
- Consumes: `ClaimsService.audit` (Task 4); `ClaimStatePipe` (Task 5); `roleLabel` (Task 5); `AuditEntry` (Task 1).
- Produces: `auditEntries` signal rendered as a newest-first list; refreshed after each transition.

- [ ] **Step 1: Add a failing audit test**

Append inside the top-level `describe` of `claim-detail.component.spec.ts`:

```ts
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
```

> Note: `audit` is already called once on init in `setup` (returns `[]`); this test resets the spy, supplies entries, and calls the private `loadAudit()` to render them.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — `loadAudit` not defined / audit markup absent.

- [ ] **Step 3: Add the audit trail to the detail component**

In `frontend/src/app/features/claims/detail/claim-detail.component.ts`:

Add imports:

```ts
import { MatListModule } from '@angular/material/list';
import { roleLabel } from '../../../shared/claim-labels';
import { AuditEntry } from '../../../core/models/claim.models';
```

Add `MatListModule` to the `imports` array.

Add an `auditEntries` signal and load it on init and after transitions. Add to the class:

```ts
  readonly auditEntries = signal<AuditEntry[]>([]);

  roleText(r: Role) {
    return roleLabel(r);
  }

  private loadAudit(): void {
    this.claims.audit(this.id).subscribe({
      next: (entries) => this.auditEntries.set([...entries].reverse()),
      error: () => {},
    });
  }
```

Call `this.loadAudit()` from the constructor (after `this.reload()`) and inside the `doTransition` success handler (after `this.claim.set(updated)`):

```ts
  constructor() {
    this.reload();
    this.loadAudit();
  }
```

```ts
      next: (updated) => {
        this.claim.set(updated);
        this.loadAudit();
        this.notify.success('Status aktualisiert.');
      },
```

Add the audit section to the template, after the closing `</mat-card>` of the claim card:

```html
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/claims/detail
git commit -m "feat(fe): claim detail — append-only audit trail (newest first)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: Infra — nginx /api proxy + docs

**Files:**
- Modify: `infra/frontend-nginx.conf`
- Modify: `README.md`
- Modify: `frontend/README.md`

**Interfaces:**
- Consumes: nothing (infra/docs only).
- Produces: a working dockerized end-to-end app (`docker compose up`) and updated run/dev docs.

- [ ] **Step 1: Add the /api proxy to nginx**

Replace `infra/frontend-nginx.conf`:

```nginx
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;

    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

> `backend` is the compose service name (see `docker-compose.yml`). The SPA calls `/api/...` same-origin; nginx forwards to the API container.

- [ ] **Step 2: Verify the frontend image builds**

Run: `docker compose build frontend`
Expected: build succeeds (the nginx config copies in without error).

- [ ] **Step 3: Bring the stack up and smoke-test the proxy**

Run: `docker compose up --build -d`
Then:
- Open `http://localhost:4200` → the login screen renders.
- Run:
  `curl -s -X POST http://localhost:4200/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"password123"}'`
  Expected: a JSON envelope `{"ok":true,"data":{"token":"...","role":"ADMIN",...}}` — confirming nginx proxies `/api` to the backend.
- Log in through the UI as `sachbearbeiter` / `password123`; confirm the dashboard lists claims.

Tear down: `docker compose down`.

- [ ] **Step 4: Update the README run/dev sections**

In `README.md`, under "Running locally", add a dev note:

```markdown
### Frontend dev server

For live frontend development against a locally-running API:

```bash
cd frontend
npm ci
npm start          # ng serve on http://localhost:4200, proxies /api -> http://localhost:8080
```

Start the backend separately (`docker compose up backend db`, or run the Spring
Boot app). The Angular dev server proxies `/api` via `frontend/proxy.conf.json`.
```

Also update the "Status" line at the bottom of `README.md`:

```markdown
## Status

Sub-projects 1–5 complete: infra & skeleton, claim domain + state machine,
security (JWT), AI triage, and the Angular frontend (login, dashboard, claim
detail + workflow, AI-confirm, audit). See `docs/superpowers/specs/` for the
design and roadmap.
```

In `frontend/README.md`, replace the default Angular CLI boilerplate intro with a
short project-specific note (keep the build/test/serve command sections):

```markdown
# Schadenflow frontend

Angular 19 (standalone + signals) + Angular Material SPA over the Schadenflow REST
API. Covers login, the role-aware claim dashboard, claim detail with the workflow
state machine, the advisory AI-triage "confirm" treatment, and the audit trail.

API calls are relative (`/api/...`): in dev, `ng serve` proxies them to
`http://localhost:8080` via `proxy.conf.json`; in Docker, nginx proxies them to
the `backend` service.
```

- [ ] **Step 5: Final full-suite verification**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless && npm run build`
Expected: all specs PASS and production build succeeds.

- [ ] **Step 6: Commit**

```bash
git add infra/frontend-nginx.conf README.md frontend/README.md
git commit -m "feat(fe): nginx /api proxy for dockerized run + docs

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage** (against `2026-06-27-angular-frontend-design.md`):
- §2 scope — login (T6), claimant create+dashboard (T8, T9), reviewer dashboard/detail/workflow/triage-confirm/audit (T8, T10–T12), pagination (T8), unit tests (every task), `/api` proxy dev+nginx (T1, T13). ✓
- §6 architecture/structure — `core/`, `features/`, `shared/` laid down across T1–T7. ✓
- §6.1 routing + §6.2 shell — T6, T7. ✓
- §7 auth/interceptor/guards/envelope/errors — T1 (envelope), T2 (auth), T3 (interceptor+guards), T5 (error messages). ✓
- §8 features incl. AI-confirm advisory treatment + role/state buttons — T8, T10, T11, T12. ✓
- §9 proxy — T1 (dev), T13 (nginx). ✓
- §10 testing — every task is TDD; CI already runs `npm ci && npm run build && npm test` headless (verified in `.github/workflows/ci.yml`), so no CI change is needed. ✓
- §11 integrity (advisory/human-confirmed, backend authoritative) — T11 pre-fills but never auto-applies; T3/T10 note backend authority. ✓

**Placeholder scan:** the only literal "placeholder" is the intentional minimal dashboard in T6, explicitly replaced in T8 (and `claims/new`/`:id` route targets swapped in T9/T10). No TODO/TBD; every code step shows complete code. ✓

**Type consistency:** `unwrap<T>()`, `ApiClientError(code,message)`, `AuthUser`, `ClaimFilters`, `availableTransitions`/`TransitionOption`, `ConfirmDialogResult{confirmed,reason}`, `ClaimsService` method signatures, and signal names (`claim`, `triage`, `selectedCategory`, `confirmSummary`, `auditEntries`) are used identically across tasks. ✓

**Note for the implementer:** Angular Material `MatChip` `[color]` accepts `'primary'|'accent'|'warn'|undefined`; the `''` returned by `claimStateColor` for `AUSBEZAHLT` renders a default chip (acceptable). If strict template typing rejects `''`, change `claimStateColor`'s fallback/`AUSBEZAHLT` value to `undefined` and the return type to `ThemePalette`.
