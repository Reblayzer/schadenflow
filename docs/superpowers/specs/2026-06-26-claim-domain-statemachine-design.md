# Schadenflow Sub-project 2 — Claim Domain + State Machine (Design Spec)

**Date:** 2026-06-26
**Status:** Approved (design), pending user review of this document
**Parent spec:** `docs/superpowers/specs/2026-06-26-schadenflow-design.md` (§9 item 2)
**Builds on:** sub-project 1 (infra & skeleton), merged to `main`.

## 1. Purpose

Add the core domain of Schadenflow: a **Claim** that moves through a
server-validated **state machine**, with an **append-only audit trail** of every
transition, exposed over a REST API using a consistent response envelope. This is
the heart of the product — the workflow and its audit are the main selling point.

Backend-only. Out of scope here (later sub-projects): authentication (SP3), AI
triage (SP4), the Angular UI (SP5).

## 2. Decisions (locked during brainstorming)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Transition API | **One generic endpoint** `POST /api/claims/{id}/transitions` | Mirrors the state-machine model; one validated code path to test. |
| State machine | **Hand-rolled transition table** | No heavy dependency; fully unit-testable; interview-explainable for a 5-state flow. |
| Actor (pre-auth) | **`X-Actor-Id` + `X-Actor-Role` headers, temporary** | Records real audit actors and exercises role rules now; SP3 swaps the header for verified JWT identity without changing rule logic. |
| Schema management | **Flyway migrations** | Deterministic, versioned, production-grade; switch `ddl-auto` to `validate`. |
| Role-gating | **Enforced now (header-sourced)** | Role rules are business-workflow logic that belongs with the state machine; SP3 only changes the trust source. |
| Claim mutation | **No update/delete endpoints** | Append-only audit integrity; all state changes flow through audited transitions. |
| `category` | **Free `String`, nullable** | Taxonomy is defined later by AI triage (SP4); avoid premature enum. |

## 3. Domain model

Schema is created and owned by **Flyway** migrations (`V1__create_claims.sql`,
`V2__create_audit_entries.sql`). `spring.jpa.hibernate.ddl-auto` becomes
`validate`.

### Claim
| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | PK, generated |
| `claimantId` | UUID | who filed it |
| `title` | String | required, ≤ 200 chars |
| `description` | text | required free text |
| `category` | String | nullable; human-set now, AI-suggested in SP4 |
| `amount` | BigDecimal | required, ≥ 0 |
| `state` | ClaimState enum | required; starts `EINGEREICHT` |
| `createdAt` / `updatedAt` | Instant | audit timestamps |

### AuditEntry (append-only)
| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | PK |
| `claimId` | UUID | FK → claim |
| `fromState` | ClaimState | **nullable** (null for the creation row) |
| `toState` | ClaimState | required |
| `actorId` | UUID | from `X-Actor-Id` |
| `actorRole` | Role enum | from `X-Actor-Role` |
| `reason` | String | nullable; required for `ABGELEHNT` (see §5) |
| `timestamp` | Instant | when the transition occurred |

### Enums
- **ClaimState:** `EINGEREICHT`, `IN_PRUEFUNG`, `GENEHMIGT`, `ABGELEHNT`, `AUSBEZAHLT`
- **Role:** `ANSPRUCHSTELLER`, `SACHBEARBEITER`, `ADMIN`

(German values, English code identifiers — per the parent spec's language convention.)

## 4. State machine (hand-rolled)

`ClaimStateMachine` holds an explicit allowed-transitions table; each transition
names the roles permitted to perform it:

| From | To | Allowed roles |
|------|-----|---------------|
| EINGEREICHT | IN_PRUEFUNG | SACHBEARBEITER, ADMIN |
| IN_PRUEFUNG | GENEHMIGT | SACHBEARBEITER, ADMIN |
| IN_PRUEFUNG | ABGELEHNT | SACHBEARBEITER, ADMIN |
| GENEHMIGT | AUSBEZAHLT | ADMIN |

Terminal states: `ABGELEHNT`, `AUSBEZAHLT` (no outgoing transitions).

The machine answers two questions: *is `from → to` a legal edge?* and *may
`role` perform it?* It is a pure, dependency-free unit, exhaustively unit-tested.

## 5. Behaviour & validation

- **Create** (`POST /api/claims`): validates input at the boundary (title/amount
  required, amount ≥ 0), persists the claim in `EINGEREICHT`, and writes the
  **creation audit row** (`fromState = null`, `toState = EINGEREICHT`,
  actor from headers) in the **same transaction**.
- **Transition** (`POST /api/claims/{id}/transitions`, body `{ targetState,
  reason }`):
  1. Load claim (404 `NotFoundError` if absent).
  2. Reject if `current → targetState` is not a legal edge (409
     `IllegalTransitionError`).
  3. Reject if the actor's role may not perform it (403 `ForbiddenError`).
  4. Require a non-blank `reason` when `targetState = ABGELEHNT` (422
     `ValidationError`) — a rejection must be justified.
  5. Update the claim's state + `updatedAt` and append the audit row in **one
     transaction**.
- **Missing/invalid actor headers** → 400 (every mutating request must carry
  `X-Actor-Id` + a valid `X-Actor-Role`). These are declared as required
  `@RequestHeader`s, so a missing header or an unparseable role value surfaces as
  a Spring request-binding error mapped to 400 — not a domain `ValidationError`.
- **List** (`GET /api/claims`): paginated (`page`, `size`, default size 20, max
  100); optional `state` and `claimantId` filters. Never returns an unbounded
  set.

## 6. REST API & response envelope

All responses use the carried-forward envelope (SP1 review item):

```
success: { "ok": true,  "data": <payload> }
error:   { "ok": false, "error": { "code": "<MACHINE_CODE>", "message": "<human>" } }
```

`ApiResponse<T>` is a small generic type built once; a single
`@RestControllerAdvice` (`GlobalExceptionHandler`) maps errors to `(HTTP status,
code)`. Request-shape errors (malformed/missing fields, missing or unparseable
headers, bad enum value) are caught from Spring's built-in binding exceptions and
returned as **400**; domain business-rule violations on otherwise well-formed
input use the typed `ValidationError` and return **422**:

| Source | HTTP | code |
|--------|------|------|
| Bean Validation / request binding (`MethodArgumentNotValidException`, `MissingRequestHeaderException`, `MethodArgumentTypeMismatchException`) | 400 | `VALIDATION_ERROR` |
| `ValidationError` (domain rule, e.g. reject requires a reason) | 422 | `VALIDATION_ERROR` |
| `NotFoundError` | 404 | `NOT_FOUND` |
| `ForbiddenError` | 403 | `FORBIDDEN` |
| `IllegalTransitionError` | 409 | `ILLEGAL_TRANSITION` |
| (uncaught) | 500 | `INTERNAL_ERROR` (generic message; details logged server-side) |

Endpoints:
- `POST /api/claims` → 201, `data` = created claim
- `GET /api/claims?state=&claimantId=&page=&size=` → 200, `data` = page (items + page metadata)
- `GET /api/claims/{id}` → 200, `data` = claim
- `POST /api/claims/{id}/transitions` → 200, `data` = updated claim
- `GET /api/claims/{id}/audit` → 200, `data` = ordered audit entries

DTOs separate the API shape from JPA entities (request DTOs validated with Jakarta
Bean Validation; response DTOs assembled in the service/mapper).

## 7. Architecture

Feature-grouped packages under `ch.sumex.schadenflow`:

```
claim/      Claim entity, ClaimRepository (interface), ClaimService,
            ClaimController, ClaimStateMachine, dto/
audit/      AuditEntry entity, AuditRepository, (AuditService or folded into ClaimService)
shared/     ApiResponse<T>, error types (NotFoundError, ValidationError,
            ForbiddenError, IllegalTransitionError), GlobalExceptionHandler,
            actor-header extraction
```

Repository pattern (data access behind interfaces), constructor DI, typed domain
errors thrown from services and mapped to HTTP only in the advice. Each file has
one responsibility; keep files focused (< ~300 lines).

## 8. Testing strategy (TDD)

- **`ClaimStateMachine`** — exhaustive unit tests: every legal edge accepted,
  every illegal edge rejected, every role rule (permitted/denied), terminal
  states have no outgoing edges.
- **`ClaimService`** — create writes claim + creation audit atomically;
  transition updates state + appends audit atomically; each error path
  (`NotFound`, `IllegalTransition`, `Forbidden`, `Validation` incl. the
  reason-required-on-reject rule) throws the right typed error. Use in-memory
  repository fakes where practical.
- **Controllers** — `@WebMvcTest` for envelope shape, status codes, header
  validation, and error mapping via the advice.
- **Integration (`*IT`, Testcontainers + Flyway)** — migrations apply on a real
  Postgres; repository round-trips; one full **create → transition → audit**
  flow asserts persisted state and the audit chain.

Every test deterministic and independent. The Failsafe binding from SP1 keeps
`*IT` running under `mvn verify`.

## 9. Out of scope (deferred)

- Authentication / verified identity (SP3) — headers are a temporary stand-in.
- AI triage and the `category` taxonomy (SP4).
- Angular UI (SP5).
- Editing claim fields after creation; deleting claims; real payment on
  `AUSBEZAHLT`.

## 10. Definition of done

- Flyway migrations create `claims` + `audit_entries`; `ddl-auto=validate`; the
  Testcontainers IT proves migrations + a full create→transition→audit flow on
  real Postgres.
- The state machine enforces every legal edge and role rule, exhaustively tested.
- All five endpoints work with the `ApiResponse` envelope; typed errors map to the
  documented HTTP codes via one advice.
- `mvn verify` green (unit + IT); `docker compose up` still healthy.
- No update/delete endpoints; audit is append-only.
