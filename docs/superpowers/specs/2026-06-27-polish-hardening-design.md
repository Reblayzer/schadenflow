# Schadenflow sub-project 6 (Polish & prod-hardening) — Design Spec

**Date:** 2026-06-27
**Status:** Approved (design), pending user review of this document
**Roadmap:** Overall design §9.6 (`docs/superpowers/specs/2026-06-26-schadenflow-design.md`)

## 1. Purpose

The final sub-project: turn the working SP1–SP5 system into a presentable,
honestly-hardened portfolio repository. Two strands:

1. **Polish** — a README architecture deep-dive (the public repo's front door):
   overview, architecture diagram, the state machine / auth / AI-in-the-loop
   story, run/test instructions, and an explicit security-posture section.
2. **Production hardening** — close the two documented SP3 follow-ups that are
   required before any non-dev deployment: gate the dev seed behind a `dev`
   Spring profile, and fail fast on a weak/default JWT secret outside `dev`.

The repo is **already public** (GitHub `Reblayzer/schadenflow`), so the roadmap's
"make repo public" step is already done and is out of scope here.

## 2. Scope

**In:**

- `dev` Spring profile that gates Flyway seed migrations and the JWT-secret check.
- Fail-fast startup validation of `security.jwt.secret` outside the `dev` profile.
- Wire the `dev` profile into the integration tests and `docker-compose.yml`.
- Root `README.md` architecture deep-dive with a Mermaid diagram and a rewritten
  security-posture / production-hardening section.
- Confirm CI stays green (no workflow change expected).

**Out (SP6 non-goals):**

- No real deployment, hosting, TLS/HTTPS, or secrets-manager integration — the
  hardening makes prod *safe to attempt*; it does not perform a deploy (v1 scope
  remains "no production deployment").
- No new features, endpoints, or UI.
- No `backend/README.md` (root deep-dive chosen); `frontend/README.md` stays as
  written in SP5.
- "Make repo public" — already done.
- No CHANGELOG / LICENSE / CONTRIBUTING additions.

## 3. Decisions (settled in brainstorming)

| Concern | Decision |
|---|---|
| SP6 scope | Polish **and** the two SP3 prod-hardening items |
| Hardening mechanism | A single `dev` Spring profile (Approach A), not per-item boolean flags |
| Default posture | No-profile/default = production: no seed, strong-secret required |
| README depth | Architecture deep-dive with a Mermaid diagram + security-posture section |
| README scope | Root `README.md` only (no per-module READMEs) |
| CI | No workflow change; the SP6 PR is the final green gate |

## 4. Current state (what SP6 changes)

`backend/src/main/resources/application.properties` today:

```
spring.flyway.locations=classpath:db/migration,classpath:db/seed
security.jwt.secret=${SECURITY_JWT_SECRET:dev-only-insecure-secret-change-me-0123456789}
```

So the seed (`db/seed/V100__seed_dev_users.sql`, which inserts `anspruchsteller`/
`sachbearbeiter`/`admin`, all bcrypt `password123`) runs in **every** environment,
and the JWT secret silently falls back to a publicly-known default. The
integration tests (`AuthFlowIT`, `ClaimFlowIT`, `TriageFlowIT`,
`SecurityIntegrationIT`, `*PersistenceIT`, `ApplicationContextIT`) boot the full
context and log in as the seed users with that default secret.

## 5. Hardening design

### 5.1 Seed gating

- `application.properties` (default/prod): `spring.flyway.locations=classpath:db/migration`
  — migrations only, **no seed**.
- New `application-dev.properties`: `spring.flyway.locations=classpath:db/migration,classpath:db/seed`
  — the `dev` profile adds the seed. Profile-specific properties override the base
  single-valued property, so default → migrations-only, `dev` → migrations+seed.

### 5.2 JWT secret fail-fast

- A pure, unit-testable validator (e.g. `auth/JwtSecretValidator` with a static
  `validate(String secret)`) that throws `IllegalStateException` when the secret
  **equals the dev default** (`dev-only-insecure-secret-change-me-0123456789`) OR
  is **shorter than 32 bytes** (UTF-8). Both checks are required: the dev default
  is 44 chars, so the length check alone would not catch it.
- A `@Configuration` annotated `@Profile("!dev")` whose `@PostConstruct` reads
  `@Value("${security.jwt.secret}")` and calls `JwtSecretValidator.validate(...)`.
  Throwing there aborts application startup. Inside the `dev` profile the bean does
  not exist, so the dev default is tolerated.
- The dev-default constant is defined once and shared between the
  `application.properties` fallback intent and the validator (the validator owns
  the canonical constant; the properties file keeps the literal as the env-var
  fallback).

### 5.3 Net behavior

| Profile | Seed users | JWT default secret allowed |
|---|---|---|
| default (none / prod) | no | no — fail-fast at startup |
| `dev` | yes | yes |

Safe-by-default: a production run must simply *not* activate `dev` and *must* set
a strong `SECURITY_JWT_SECRET`.

## 6. Test & compose wiring

- **Integration tests:** add `@ActiveProfiles("dev")` via the shared IT
  base/config (one place) so context-booting ITs keep their seed users and skip
  the JWT fail-fast. Other than the annotation, the ITs are unchanged.
- **New tests:**
  - `JwtSecretValidatorTest` (pure unit, TDD core): throws on the dev default;
    throws on a <32-byte secret; passes on a strong 32+ byte non-default secret.
  - One lightweight wiring test that the context **fails to start** with the
    default secret and no `dev` profile (e.g. `@SpringBootTest(properties=...)`
    expecting startup failure, or an `ApplicationContextRunner`). The pure
    validator test is the authoritative guard; if the context-fail test proves
    heavy/flaky, the plan may trim it to the validator test plus a
    profile-presence assertion.
- **Compose:** `docker-compose.yml` backend service gets
  `SPRING_PROFILES_ACTIVE=dev`, so `docker compose up` keeps the documented seed
  users and tolerates the default dev secret. The README states a non-dev deploy
  must set a strong secret and omit `dev`.

## 7. README architecture deep-dive

Restructure root `README.md` (German domain terms preserved; English prose):

1. **Overview** — what Schadenflow is, synthetic-data/portfolio disclaimer,
   named stack.
2. **Architecture** — a **Mermaid diagram** of the request path (Angular SPA →
   nginx `/api` proxy → Spring Boot controllers → service layer → repositories →
   Postgres; `TriageService` mock|Claude branch; JWT auth). Prose on the three
   layers, the **claim state machine** (`EINGEREICHT → IN_PRUEFUNG →
   GENEHMIGT|ABGELEHNT → AUSBEZAHLT`, role-gated, audited atomically), the **JWT
   auth flow**, and the **AI-in-the-loop** treatment (advisory, never
   auto-applied, human-confirmed).
3. **Repository layout** — keep the `backend/ frontend/ infra/ docs/` table.
4. **Running locally** — `docker compose up` + the frontend dev-server note.
5. **Testing** — backend `mvn verify` (Testcontainers), frontend Karma headless;
   note CI runs both.
6. **Authentication** — login + seeded dev users, now explicitly noting seeds are
   **`dev`-profile only**.
7. **AI Triage** — provider toggle table (keep).
8. **Security posture & production hardening** — rewrite: what is enforced now
   (seed gated behind `dev`; fail-fast on weak/default JWT secret outside `dev`),
   and the remaining real-deploy checklist (strong secret, run without `dev`,
   TLS/termination, etc.).
9. **Project status / roadmap** — SP1–6 complete.

## 8. CI

`.github/workflows/ci.yml` already runs backend `mvn verify` and frontend
`npm ci && build && test` headless on every PR/push to main. SP6 is
CI-compatible: ITs run under `dev` (seed users available under Testcontainers),
and no strict-secret env is needed in CI because tests use `dev`. **No workflow
change required**; the SP6 PR is the final green gate. `mvn verify` and the
frontend suite are verified locally before the PR.

## 9. Integrity notes

- Behaviour and stack only — no invented metrics or "deployed in production"
  claims. Synthetic data only.
- The hardening makes a non-dev run *safe to attempt*; it does not deploy.
- AI triage remains advisory and human-confirmed.
- Built solo with Claude Code; fully explainable in an interview.
