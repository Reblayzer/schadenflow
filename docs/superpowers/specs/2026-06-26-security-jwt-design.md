# Schadenflow Sub-project 3 — Security (JWT Auth + DB Users/Roles) — Design Spec

**Date:** 2026-06-26
**Status:** Approved (design), pending user review of this document
**Parent spec:** `docs/superpowers/specs/2026-06-26-schadenflow-design.md` (§9 item 3)
**Builds on:** sub-projects 1 (skeleton) and 2 (claim domain), both merged to `main`.

## 1. Purpose

Add authentication and authorization, and **replace the temporary `X-Actor-Id` /
`X-Actor-Role` headers** introduced in SP2 with a verified JWT principal. This
closes the two trust gaps the SP2 whole-branch review flagged: the actor role was
self-asserted, and claim creation trusted a `claimantId` from the request body.

Backend only. Out of scope (later): AI triage (SP4), Angular UI (SP5).

## 2. Decisions (locked during brainstorming)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| JWT mechanism | **jjwt (io.jsonwebtoken), HS256, custom `OncePerRequestFilter`** | Lightweight, explicit, interview-explainable; no OAuth server machinery needed for a self-issued token. |
| User seeding | **Flyway dev-seed** (separate `db/seed` location) with pre-computed bcrypt hashes | Deterministic, versioned, no startup code; tests + compose get working logins. Synthetic/dev-only. |
| Claimant identity | **Derived from the authenticated user** | Closes the SP2 trust gap; `claimantId` dropped from the request body. |
| Token model | **Single short-lived stateless access token (~1h)** | Simplest; no refresh storage/rotation. |
| Create authorization | **Any authenticated user** (claimant = self) | Lets admins create test claims; transition role rules still govern the workflow. |
| Login rate-limiting | **Deferred** (noted hardening follow-up) | No public deployment; avoids pulling a rate-limiter dependency into v1. |

## 3. Domain (new)

Schema via Flyway (continuing SP2's migrations).

### User — `V3__create_users.sql`
| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | PK |
| `username` | VARCHAR(100) | UNIQUE, NOT NULL |
| `password_hash` | VARCHAR(100) | bcrypt, NOT NULL |
| `role` | VARCHAR(30) | NOT NULL; one of the `Role` enum values |
| `created_at` | TIMESTAMPTZ | NOT NULL |

### Dev seed — `db/seed/V100__seed_dev_users.sql`
Inserts three synthetic users (one per role), each with a **pre-computed bcrypt
hash** of a documented dev password:

| username | role | password (dev only) |
|----------|------|---------------------|
| `anspruchsteller` | ANSPRUCHSTELLER | `password123` |
| `sachbearbeiter` | SACHBEARBEITER | `password123` |
| `admin` | ADMIN | `password123` |

The seed lives in a **separate Flyway location** `classpath:db/seed`, added to
`spring.flyway.locations` (default `classpath:db/migration,classpath:db/seed`).
It is clearly labelled synthetic/dev-only; no real PII, no production deployment.

## 4. Auth components (`auth/` + `user/` packages)

```
user/
  User.java                 @Entity
  UserRepository.java        findByUsername(...)
auth/
  AuthController.java        POST /api/auth/login
  dto/LoginRequest.java      { username, password } (validated)
  dto/LoginResponse.java     { token, username, role, expiresAt }
  JwtService.java            issue(user) / parse(token) -> claims; HS256
  JwtAuthenticationFilter.java  OncePerRequestFilter -> SecurityContext
  SecurityConfig.java        SecurityFilterChain, PasswordEncoder, AuthEntryPoint
  AuthenticatedUser.java     principal record { userId: UUID, username, role: Role }
```

- **`POST /api/auth/login`** `{username, password}`: look up the user, verify with
  `BCryptPasswordEncoder.matches`. On success issue a JWT (`sub` = userId, claims
  `role`, `username`, `iat`, `exp` ≈ now+1h) signed HS256 with `SECURITY_JWT_SECRET`
  (env, dev default). Response `ApiResponse<LoginResponse>`. On failure → **401**
  `INVALID_CREDENTIALS` (generic message; do not reveal whether the username exists).
- **`JwtService`**: `String issue(User user)` and `AuthenticatedUser parse(String token)`
  (throws on bad signature / expiry / malformed). Secret + expiry injected via config.
- **`JwtAuthenticationFilter`**: reads `Authorization: Bearer <token>`; if present and
  valid, builds a `UsernamePasswordAuthenticationToken` with principal
  `AuthenticatedUser` and authority `ROLE_<role>`, sets the `SecurityContext`. Invalid
  token → clear context (request proceeds and is rejected by the chain as 401). No
  token on a protected route → 401 via the entry point.
- **`SecurityConfig`**: stateless (`SessionCreationPolicy.STATELESS`), CSRF disabled
  (token API, no cookies), `PasswordEncoder` = bcrypt bean, the filter registered
  before `UsernamePasswordAuthenticationFilter`. Public matchers: `POST /api/auth/login`,
  `GET /api/health`. All other routes: authenticated.

## 5. Authorization rules

- **Per-transition role rules** (from SP2's `ClaimStateMachine`) are unchanged, now fed
  the **trusted** role from the JWT principal.
- **Resource ownership** (CLAUDE: "verify the user owns the resource"): a caller whose
  role is `ANSPRUCHSTELLER` may only read/audit/transition claims **they own** (claim's
  `claimantId == userId`); `GET /api/claims` returns only their own claims (the
  `claimantId` filter is forced to the caller's id; any `claimantId` query param
  they pass is overridden, not honored).
  `SACHBEARBEITER` and `ADMIN` (caseworkers) may access all claims. An ownership
  violation → **403** `FORBIDDEN`.
- **Create:** any authenticated user; `claimantId` is set to the caller's `userId`.

## 6. Claims refactor (replace headers with principal)

- Remove `@RequestHeader("X-Actor-Id"/"X-Actor-Role")` from `ClaimController`; obtain
  the `AuthenticatedUser` from the `SecurityContext` (via a small resolver or
  `@AuthenticationPrincipal`) and build the existing `ActorContext(actorId, actorRole)`
  from it.
- Drop `claimantId` from `CreateClaimRequest`; `ClaimService.createClaim` receives the
  claimant id from the principal.
- Add the ownership checks (§5) in `ClaimService` (get / list / audit / transition).
- **Update the SP2 controller/service/flow tests** to authenticate (issue a real test
  JWT via `JwtService`, or a Spring-Security test setup) instead of sending `X-Actor-*`
  headers. All previously-green SP2 tests must pass under the new auth.

## 7. Error mapping (extends SP2's advice)

The single `@RestControllerAdvice` and Spring Security are made consistent:
- `401 UNAUTHORIZED` `INVALID_CREDENTIALS` (bad login) and `UNAUTHORIZED` (missing/
  invalid/expired token) — the security entry point returns the standard envelope.
- `403 FORBIDDEN` `FORBIDDEN` (authenticated but not permitted: role rule or ownership)
  — the access-denied handler / advice returns the envelope.
- Existing SP2 mappings (400/404/409/422/500) unchanged.

## 8. Configuration

`application.properties` additions:
```
spring.flyway.locations=classpath:db/migration,classpath:db/seed
security.jwt.secret=${SECURITY_JWT_SECRET:dev-only-insecure-secret-change-me-0123456789}
security.jwt.expiration-minutes=${SECURITY_JWT_EXPIRATION_MINUTES:60}
```
The secret is read from the environment with an explicit dev-only default (never a real
secret in source). `docker-compose.yml` may pass `SECURITY_JWT_SECRET` to the backend.

## 9. Testing strategy (TDD)

- **`JwtService`** (unit): issue→parse round-trip returns the right userId/role; a
  token with a tampered signature, a wrong secret, an expired `exp`, and a malformed
  string are each rejected.
- **Password encoding** (unit): the seed hashes verify against the documented password;
  a wrong password fails.
- **`AuthController`** (`@WebMvcTest` + security): login success returns a token + 200
  envelope; bad password → 401; unknown user → 401 (same message).
- **Security integration**: a protected endpoint returns 401 without a token and 200
  with a valid token; an `ANSPRUCHSTELLER` token is 403 on another user's claim and on a
  disallowed transition; a `SACHBEARBEITER` token succeeds.
- **Testcontainers IT**: real Flyway (incl. seed) on Postgres → `POST /api/auth/login`
  with a seeded user → use the returned token to create a claim (claimant = that user)
  → transition with an appropriately-roled token → assert the audit trail. Refactor the
  SP2 `ClaimFlowIT` to drive through login/token rather than `X-Actor-*` headers.

## 10. Security notes

- Passwords hashed with **bcrypt**; never logged. JWT secret from env; never committed.
- Generic auth errors to clients; details logged server-side only.
- Stateless tokens; logout is client-side token discard (no server session). Token
  revocation/refresh is out of scope for v1.
- Synthetic users only; documented dev password; no production deployment.

## 11. Out of scope (deferred)

- Refresh tokens / rotation / server-side revocation.
- Login rate-limiting / lockout (hardening follow-up).
- Per-user registration/admin user management UI.
- AI triage (SP4), Angular UI (SP5).

## 12. Definition of done

- `V3` + dev seed create and populate `users`; three seeded logins work.
- `POST /api/auth/login` issues a verifiable HS256 token; bad creds → 401.
- Protected endpoints require a valid token (401 otherwise); the JWT role + identity
  drive the existing transition role rules and the new ownership checks (403 on
  violation).
- `X-Actor-*` headers are gone; `claimantId` is derived from the principal; all SP2
  behaviour preserved with updated (authenticated) tests.
- `mvn verify` green (unit + Testcontainers ITs); `docker compose up` healthy with
  login working.
