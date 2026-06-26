# Schadenflow v1 — Design Spec

**Date:** 2026-06-26
**Status:** Approved (high-level), pending user review of this document
**Source brainstorm:** `PROJECT_BRAINSTORM.md`

## 1. Purpose

A small health-insurance claims-management portal, tailored as portfolio
evidence for a Junior Software Engineer (Java) posting at Sumex SA. A claim moves
through a stewarded approval workflow with an append-only audit trail and
role-based access, plus an AI triage step that summarises a free-text claim and
suggests a category. The AI output is **advisory and human-confirmed**, never
auto-applied.

The project exercises the posting's named stack (Java, Spring Boot, Angular,
TypeScript, REST, PostgreSQL, Docker, CI/CD, AI tooling) on Sumex's own problem
domain (Schadenmanagement).

## 2. Tech stack (decided)

| Concern        | Decision |
|----------------|----------|
| Backend lang   | Java 21 |
| Build tool     | Maven |
| Framework      | Spring Boot (REST, service layer, JPA) |
| Persistence    | PostgreSQL via JPA/Hibernate |
| Security       | Spring Security, **JWT** (stateless), DB-backed users/roles, bcrypt |
| Frontend       | Latest Angular (standalone components + signals), Angular Material |
| AI triage      | `TriageService` interface; deterministic **mock** default; optional Anthropic Claude adapter behind config |
| Infra          | Monorepo, Docker Compose (API + Angular + Postgres), GitHub Actions CI |

## 3. Repo layout (monorepo)

```
schadenflow/
  backend/            Spring Boot app (Maven)
  frontend/           Angular app
  infra/              docker-compose.yml, Dockerfiles, db init scripts
  .github/workflows/  ci.yml
  docs/superpowers/specs/
  README.md
```

## 4. Domain model

- **Claim** — `id`, `claimantId`, `title`, `description` (free text), `category`,
  `amount`, `state`, `triageSummary` (nullable, AI-suggested), `createdAt`,
  `updatedAt`. `category` and `triageSummary` are AI-suggested and
  human-confirmed.
- **ClaimState** (enum) — `EINGEREICHT → IN_PRUEFUNG → GENEHMIGT | ABGELEHNT →
  AUSBEZAHLT`. Server-side state machine validates every transition.
- **AuditEntry** — append-only, one row per transition: `id`, `claimId`,
  `fromState`, `toState`, `actorId`, `reason`, `timestamp`.
- **User / Role** — roles `ANSPRUCHSTELLER`, `SACHBEARBEITER`, `ADMIN`.

**Language convention:** German for *domain enums/roles* (Sumex-domain
authenticity); English for code identifiers, package names, and API paths.

## 5. Backend architecture

Layered and feature-grouped, following the `~/dev` baseline rules:

- **Repository pattern** — all data access behind interfaces; application code
  does not build queries directly. Swappable, testable with in-memory fakes.
- **Service layer** — holds the state machine + audit logic. A transition and its
  audit row are written in the same transaction.
- **Controllers** — thin; map domain errors to HTTP status codes.
- **Typed domain errors** — `NotFoundError`, `ValidationError`,
  `IllegalTransitionError`; never a raw `Exception` across a service boundary.
- **Consistent API envelope** — `{ "ok": true, "data": ... }` or
  `{ "ok": false, "error": { "code", "message" } }`.
- **Dependency injection** via constructors; wiring at the entrypoint.
- **AI triage** behind `TriageService` so it is mockable and the real LLM call is
  swappable.

## 6. Key behaviours

- **State transitions** are the core. Only legal transitions are allowed; each is
  role-gated (e.g. only `SACHBEARBEITER`/`ADMIN` may approve/reject) and writes an
  audit row atomically with the state change.
- **AI triage on submit** returns `{ summary, suggestedCategory, missingInfoFlags }`.
  Advisory only — the UI shows "AI suggestion — please confirm" and never
  auto-applies it.
- **Pagination** on every list endpoint. Synthetic seed data only; no real PII.

## 7. Testing strategy

TDD throughout (`~/dev` rules). Many fast unit tests on the state machine and
service layer; integration tests at the persistence and API boundaries; a few
E2E checks for the critical claim-lifecycle flow. Every bug fix starts with a
failing regression test. The mock `TriageService` keeps AI-dependent tests
deterministic and secret-free.

## 8. v1 scope

**In:** Claim CRUD + four-state lifecycle with server-side transition validation;
role-based access for three roles; append-only audit trail; AI triage endpoint
(summary + category suggestion) with "confirm" UI treatment; Angular dashboard +
detail view; JUnit tests on state machine + services; Docker Compose; GitHub
Actions CI.

**Out (v1):** Real Swiss tariff (TARMED/TARDOC) validation; real payment
integration (`AUSBEZAHLT` is modelled, not wired to money); production deployment
/ real PII; mobile app.

## 9. Decomposition into ordered sub-projects

Each sub-project gets its own spec → plan → implement → review cycle. Built in
this order (infra/skeleton first, per user decision). Development is driven with
sub-agents.

1. **Infra & skeleton** *(first build)* — monorepo layout; empty Spring Boot app
   (Maven, Java 21) with a health endpoint; PostgreSQL via Docker Compose; empty
   Angular + Material app; GitHub Actions CI green (build + placeholder tests).
   **Goal:** `docker compose up` runs all three services; CI is green.
2. **Claim domain + state machine** — entities, repositories, the state machine
   with server-side transition validation, append-only audit log. Full TDD
   coverage. Endpoints open in dev (no auth yet).
3. **Security** — JWT auth, DB users/roles, role-gated transitions, login
   endpoint.
4. **AI triage** — `TriageService` interface, mock impl, triage endpoint,
   optional Claude adapter.
5. **Angular frontend** — login, dashboard (filter by state/owner), claim detail
   + workflow actions, audit trail, "AI suggestion — confirm" treatment.
6. **Polish** — README (architecture + AI-in-the-loop note), final CI, make repo
   public.

Only sub-project 1 is specced and planned now; we return to brainstorm
sub-project 2 after it ships.

## 10. Integrity notes

- Describe behaviour and stack only — no invented metrics, users, or "deployed in
  production" claims.
- AI triage is advisory and reviewed; no autonomous-decisioning claims.
- Built solo with Claude Code; fully explainable in an interview.
- Repo link goes on the CV only once the repo exists and is public.
