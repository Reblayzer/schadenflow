# Schadenflow sub-project 5 (Angular frontend) — Design Spec

**Date:** 2026-06-27
**Status:** Approved (design), pending user review of this document
**Roadmap:** Overall design §9.5 (`docs/superpowers/specs/2026-06-26-schadenflow-design.md`)

## 1. Purpose

Build the Angular frontend for Schadenflow: the user-facing portal over the
existing Spring Boot REST API (SP1–SP4, all merged). It covers the **full
three-role claim lifecycle** end-to-end — claimants submit and track their own
claims; reviewers (Sachbearbeiter/Admin) triage, confirm the AI-suggested
category, and drive the approval workflow with an append-only audit trail. The AI
output is **advisory and human-confirmed**, never auto-applied — the UI makes that
explicit. This is the portfolio showpiece tying the whole stack together.

No backend changes are required; the API is complete and unchanged.

## 2. Scope

**In:**

- Login (JWT) and a role-aware navigation shell.
- Claimant: create a claim; dashboard of own claims; claim detail.
- Reviewer (Sachbearbeiter/Admin): dashboard of all claims with state/claimant
  filters; claim detail with role/state-derived workflow actions; AI-triage
  request + "confirm category" treatment; audit trail.
- Server-side pagination on the dashboard.
- Unit/component tests (Karma + Jasmine), TDD; frontend CI job runs the real
  suite headless.
- Same-origin `/api` proxying for both `ng serve` (dev) and nginx (Docker).

**Out (SP5 non-goals):** NgRx / centralized store; refresh-token or silent-renewal
flow (single JWT + expiry only); i18n framework (German labels inline, matching
the domain convention); real-time/websockets; claim editing beyond create +
category-confirm + transitions; advanced theming beyond a clean Material theme;
an e2e framework (Playwright/Cypress) — deferred. The SP3 production-hardening
items (dev-seed gating, JWT-secret fail-fast) remain a separate backlog.

## 3. Decisions (settled in brainstorming)

| Concern | Decision |
|---|---|
| Functional scope | Full 3-role lifecycle (1:1 with the backend) |
| Architecture | Feature-grouped standalone components + signal-based services (Approach A). No NgRx. |
| Session persistence | JWT in `localStorage`; functional HTTP interceptor + route guards |
| Testing | Karma + Jasmine, TDD; headless Chrome in CI; no e2e framework |
| API wiring | Relative `/api` calls, proxied (dev `proxy.conf.json`; prod nginx `proxy_pass`) |
| UI toolkit | Angular Material (already in the scaffold), Angular 19 standalone + signals |

## 4. Starting point (existing scaffold)

`frontend/` already holds an Angular 19 app (standalone + signals, Angular
Material + CDK) generated in SP1: empty `app.routes.ts`, `app.config.ts` with only
`provideRouter`/zone change detection, and a placeholder `AppComponent`. There is
**no** `HttpClient` provider, auth, routing, or `/api` proxy yet. SP5 builds on
this scaffold; it does not regenerate the project.

## 5. Backend API surface consumed (reference)

Envelope everywhere: `{ "ok": true, "data": ... }` or
`{ "ok": false, "error": { "code", "message" } }`. All `/api/*` except
`/api/health` require `Authorization: Bearer <jwt>`.

| Method & path | Purpose | Notes |
|---|---|---|
| `POST /api/auth/login` | Login | → `{ token, username, role, expiresAt }` |
| `GET /api/claims` | List (paginated) | params `state?`, `claimantId?`, `page=0`, `size=20` (max 100). Claimants are auto-scoped server-side to their own claims. → `Page<ClaimResponse>` |
| `POST /api/claims` | Create | body `{ title, description, amount }`; claimant. → 201 `ClaimResponse` |
| `GET /api/claims/{id}` | Detail | → `ClaimResponse` |
| `POST /api/claims/{id}/transitions` | Workflow transition | body `{ targetState, reason? }`; role/state gated; reason required for `ABGELEHNT` |
| `GET /api/claims/{id}/audit` | Audit trail | → `AuditEntryResponse[]` |
| `POST /api/claims/{id}/triage` | AI triage (advisory) | reviewer only; pre-decision states only; **persists nothing**. → `{ summary, suggestedCategory, missingInfoFlags }` |
| `PATCH /api/claims/{id}` | Confirm category + summary | reviewer only. body `{ category, triageSummary? }` → `ClaimResponse` |

**`ClaimResponse`:** `id, claimantId, title, description, category, amount, state,
triageSummary, createdAt, updatedAt`.
**`AuditEntryResponse`:** `id, claimId, fromState, toState, actorId, actorRole,
reason, occurredAt`.

**Enums.** `ClaimState`: `EINGEREICHT, IN_PRUEFUNG, GENEHMIGT, ABGELEHNT,
AUSBEZAHLT`. `Category`: `ARZTKOSTEN, MEDIKAMENTE, SPITAL, ZAHNARZT, THERAPIE,
HILFSMITTEL, SONSTIGES`. `Role`: `ANSPRUCHSTELLER, SACHBEARBEITER, ADMIN`.
`MissingInfoFlag`: `MISSING_AMOUNT, VAGUE_DESCRIPTION, MISSING_DATE,
MISSING_PROVIDER`.

**Transition map (mirrored client-side for button derivation; backend is
authoritative):**

| From → To | Allowed roles | Notes |
|---|---|---|
| `EINGEREICHT → IN_PRUEFUNG` | Sachbearbeiter, Admin | |
| `IN_PRUEFUNG → GENEHMIGT` | Sachbearbeiter, Admin | |
| `IN_PRUEFUNG → ABGELEHNT` | Sachbearbeiter, Admin | **reason required** |
| `GENEHMIGT → AUSBEZAHLT` | Admin only | |

**Seed users (synthetic):** `anspruchsteller` / `sachbearbeiter` / `admin`, all
password `password123`.

## 6. Architecture

Feature-grouped standalone components with small, signal-based injectable
services — mirroring the backend's feature grouping and Angular 19 idioms.

```
frontend/src/app/
  core/
    api/        api-response.model.ts (envelope), api-error.ts, error helpers
    auth/       auth.service.ts (signals), auth.interceptor.ts,
                auth.guard.ts, role.guard.ts, auth.models.ts
    models/     claim.models.ts (Claim, AuditEntry, ClaimState, Category, Role, MissingInfoFlag)
  features/
    auth/       login.component (+ .spec)
    claims/
      data/       claims.service.ts (HttpClient + signals)
      dashboard/  claim-dashboard.component
      detail/     claim-detail.component (header, workflow actions, AI-confirm, audit)
      create/     claim-create.component
    shell/      app-shell.component (Material toolbar + role-aware nav)
  shared/       claim-state.pipe.ts (state→German label/colour), confirm-dialog,
                error-snackbar helper
```

`app.config.ts` gains `provideHttpClient(withInterceptors([authInterceptor]))`
and `provideAnimations()` (Material).

### 6.1 Routing

Lazy `loadComponent`, guarded:

| Path | Component | Guard |
|---|---|---|
| `/login` | Login | redirect to `/claims` if already authenticated |
| `/claims` | Dashboard | `authGuard` |
| `/claims/new` | Create | `authGuard` + `roleGuard([ANSPRUCHSTELLER])` |
| `/claims/:id` | Detail | `authGuard` |
| `**` | redirect → `/claims` | |

### 6.2 Navigation shell

Material toolbar wrapping the routed views: shows logged-in username + role and a
logout button. Role-aware — claimants get a "Neuer Schaden" action; reviewers do
not. Default landing after login is `/claims`.

## 7. Core: auth, envelope & errors

**`AuthService`** (signal-based session):

- `login(username, password)` → `POST /api/auth/login`; stores
  `{ token, username, role, expiresAt }` in `localStorage` under one key; exposes
  `currentUser = signal<AuthUser | null>` with computed `isAuthenticated` / `role`.
- On app start, hydrates the signal from `localStorage` and treats an `expiresAt`
  in the past as logged-out (clears it) — *proactive* expiry.
- `logout()` clears storage + signal and navigates to `/login`.

**`authInterceptor`** (functional): attaches `Authorization: Bearer <token>` to
`/api/*` requests when a token exists; exempts `/api/auth/login`. On a `401`
response it clears the session and redirects to `/login` — the *reactive* backstop
for server-side expiry/invalidation.

**`authGuard` / `roleGuard`** (functional `CanActivateFn`): `authGuard` redirects
unauthenticated users to `/login`, preserving the intended URL as a return target.
`roleGuard([roles])` checks `AuthService.role()` and redirects to `/claims` with an
"insufficient permission" snackbar if disallowed. This is UX-layer gating only;
the backend remains the real authority (it already returns 403).

**Envelope & errors:** typed `ApiResponse<T> = { ok; data?; error?: { code; message } }`.
Data services unwrap `res.data` and translate `{ ok:false }` / HTTP errors into a
thrown typed `ApiError(code, message)`. A shared helper surfaces these as Material
snackbars with friendly German messages, e.g. `INVALID_CREDENTIALS` → "Benutzername
oder Passwort ist falsch"; `403`/`FORBIDDEN` → "Keine Berechtigung";
`TRIAGE_UNAVAILABLE` → "KI-Triage ist derzeit nicht verfügbar".

## 8. Claims features

**`ClaimsService`** (signals over `HttpClient`): `list(filters, page)`,
`getById(id)`, `create(req)`, `transition(id, target, reason)`, `triage(id)`,
`confirmCategory(id, category, summary)`, `audit(id)`. Holds the current dashboard
page and selected claim as signals; mutations refresh the affected claim.

**Dashboard (`/claims`)** — Material table, role-aware:

- *Claimant:* only their own claims (backend auto-scopes; the claimant filter is
  hidden). Columns: title, category, amount, state chip, updatedAt. "Neuer
  Schaden" button.
- *Reviewer:* all claims; filter controls for **state** (dropdown) and optional
  **claimant**; same columns plus claimant.
- Server-side pagination wired to the Material paginator (`page`/`size` ↔ `Page`
  metadata). State rendered as coloured chips via `ClaimStatePipe` (German
  labels). Row click → detail.

**Create (`/claims/new`, claimant only)** — reactive form: title, description
(textarea), amount; client validation mirroring the backend (`title ≤ 200`,
`description ≤ 5000`, `amount ≥ 0`, 2 decimals). On success → navigate to the new
claim's detail.

**Detail (`/claims/:id`)** — the centrepiece:

1. **Claim header** — title, state chip, amount, category, timestamps,
   description.
2. **Workflow actions** — buttons derived from the client-side mirror of the
   transition map (current state × role): reviewer on `EINGEREICHT` sees "In
   Prüfung nehmen"; on `IN_PRUEFUNG` sees "Genehmigen" / "Ablehnen" (reject opens
   a dialog that **requires a reason**); admin on `GENEHMIGT` sees "Auszahlen".
   Backend stays authoritative — a 409/403 surfaces as a snackbar and the claim
   refreshes. Claimants see no action buttons.
3. **AI-triage / confirm panel** (reviewer only; pre-decision states `EINGEREICHT`/
   `IN_PRUEFUNG`) — "KI-Triage anfordern" calls `/triage`; the result renders in a
   visually distinct advisory card labelled **"KI-Vorschlag — bitte bestätigen"**
   showing suggested category, summary, and missing-info flags as chips. Nothing
   is applied until the reviewer edits/accepts and clicks **"Kategorie
   bestätigen"**, which `PATCH`es category + summary. Human-in-the-loop; never
   auto-applied.
4. **Audit trail** — timeline/list from `/audit`: each row `from → to`, actor
   role, reason, timestamp, newest first.

**Empty / loading / error states** throughout: Material spinners on loads, "keine
Schäden" empty states, snackbars for failures.

## 9. API wiring & infra (proxy)

The app always calls relative `/api/...` URLs (no environment base-URL juggling),
routed same-origin so no backend CORS config is needed:

- **Dev (`ng serve`, 4200):** add `frontend/proxy.conf.json` mapping `/api` →
  `http://localhost:8080`, wired via `angular.json` `serve` options. README dev
  flow updated.
- **Docker (nginx, 4200→80):** extend `infra/frontend-nginx.conf` with
  `location /api/ { proxy_pass http://backend:8080; }` (compose service name
  `backend`), keeping the existing SPA `try_files` fallback for client-side
  routes. `docker compose up` then yields a working end-to-end app — the SP1 goal,
  now actually reachable.

No backend changes.

## 10. Testing strategy

Karma + Jasmine, TDD, headless Chrome in CI. `HttpClientTestingModule` /
`provideHttpClientTesting` for service tests.

- **`AuthService`** — login stores session; hydration from storage; past
  `expiresAt` treated as logged-out; logout clears.
- **`ClaimsService`** — each method hits the correct URL/verb/body, unwraps
  `data`, and throws typed `ApiError` on `{ ok:false }` / HTTP error.
- **`authInterceptor`** — attaches Bearer when token present; skips login; clears +
  redirects on 401.
- **Guards** — `authGuard` redirects when unauthenticated; `roleGuard` blocks
  disallowed roles.
- **Components** — Login (submit, error snackbar); Dashboard (renders rows,
  role-aware filter visibility, paginator wiring); Detail (role/state-derived
  action buttons, reject-requires-reason dialog, AI-confirm panel renders advisory
  card / confirm `PATCH`es / nothing auto-applied); Create (validation).
- **CI** — the existing frontend job runs
  `npm ci && npm test -- --watch=false --browsers=ChromeHeadless` (real suite).

## 11. Integrity notes

- Behaviour and stack only — no invented metrics or "deployed in production"
  claims. Synthetic seed data only.
- AI triage is advisory and human-confirmed; the UI states this explicitly and
  never auto-applies a suggestion.
- Backend remains the security authority; client-side role gating is UX only.
- Built solo with Claude Code; fully explainable in an interview.
