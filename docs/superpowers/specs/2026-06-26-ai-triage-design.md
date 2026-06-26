# AI Triage (sub-project 4) — Design Spec

**Date:** 2026-06-26
**Status:** Approved (pending written-spec review)
**Parent design:** `docs/superpowers/specs/2026-06-26-schadenflow-design.md` (§9.4)
**Builds on:** SP1 (infra), SP2 (claim domain + state machine), SP3 (JWT security)

## 1. Purpose

Add an **AI triage** step to the claim workflow. A reviewer triggers triage on a
free-text claim and receives an **advisory** suggestion — a short summary, a
suggested category, and flags for obviously missing information. The suggestion
is never auto-applied: a human reviewer **confirms** (and may edit) before any
value is written to the claim. The AI call sits behind a `TriageService`
interface so it is mockable in tests; a deterministic mock is the default and a
real Anthropic Claude adapter is selectable by config.

This exercises the posting's "AI tooling" emphasis on Sumex's own domain while
keeping the integrity stance: AI is advisory and reviewed, never autonomous.

## 2. Scope

**In:**

- `TriageService` interface + a deterministic `MockTriageService` (default).
- A config-gated real `AnthropicTriageService` (Anthropic Java SDK).
- `POST /api/claims/{id}/triage` — returns a transient suggestion, persists nothing.
- `PATCH /api/claims/{id}` — applies the human-confirmed `category` + `triageSummary`.
- A fixed `Category` domain enum and a `MissingInfoFlag` enum.
- Schema change: `Claim.category` becomes enum-backed; add `triageSummary`.
- Full TDD coverage; one Testcontainers IT for the triage→confirm flow on the mock.

**Out (this sub-project):**

- Angular UI (SP5) — the "AI suggestion, please confirm" treatment is frontend.
- Prompt tuning / evaluation harness, response streaming, caching of suggestions.
- Auditing the confirmation as an `AuditEntry` row (the audit log stays
  transition-shaped; noted as a possible later enhancement).
- Real LLM calls in CI — tests always use the deterministic mock.

## 3. Key decisions (from brainstorming)

| Decision | Choice |
|----------|--------|
| When triage runs / persistence | **On-demand, transient.** A reviewer calls the triage endpoint; the suggestion is returned but **not persisted**. A separate confirm action applies only what the human accepts. |
| Claude adapter scope | **Mock (default) + real Claude adapter, config-gated.** The mock is used in all tests; the real adapter is selected only when `SCHADENFLOW_TRIAGE_PROVIDER=claude` and an API key is present. |
| Category model | **Fixed German allowlist** (`Category` enum). The mock suggests deterministically; the suggestion is always a valid enum value; confirm validates membership. |
| Authorization | **Reviewers only** (`SACHBEARBEITER`/`ADMIN`) trigger triage and confirm. The claimant (`ANSPRUCHSTELLER`) cannot. Triage allowed only while the claim is pre-decision (`EINGEREICHT` or `IN_PRUEFUNG`). |
| Confirm endpoint | `PATCH /api/claims/{id}` carrying `{ category, triageSummary? }`. |
| Confirmation audit | **No audit row in SP4.** The stored `category` + `triageSummary` are the record. |

## 4. Domain / data model changes

- **`Category` enum** (`ch.sumex.schadenflow.claim`, `EnumType.STRING`):
  `ARZTKOSTEN`, `MEDIKAMENTE`, `SPITAL`, `ZAHNARZT`, `THERAPIE`, `HILFSMITTEL`,
  `SONSTIGES`. German domain values, English identifiers — consistent with the
  `Role`/`ClaimState` convention.
- **`MissingInfoFlag` enum** (`ch.sumex.schadenflow.triage`):
  `MISSING_AMOUNT`, `VAGUE_DESCRIPTION`, `MISSING_DATE`, `MISSING_PROVIDER`.
- **`Claim` entity:**
  - `category`: change type from free-text `String` → `Category` (`@Enumerated(STRING)`),
    still nullable. Existing rows have `null` category, so no data backfill is needed.
    The setter becomes `setCategory(Category)`.
  - Add `triageSummary`: nullable `String` (column `triage_summary`, length 2000),
    with getter/setter.
- **Flyway `V4__add_claim_triage.sql`:** `ALTER TABLE claims ADD COLUMN
  triage_summary VARCHAR(2000);`. The existing `category VARCHAR(100)` column is
  kept and now stores enum names (no DDL change required; values are currently null).
- **`ClaimResponse` DTO:** `category` field type becomes `Category`; add a
  `triageSummary` field. (`CreateClaimRequest` is unchanged — claims are still
  created without a category; category arrives only via confirm.)

## 5. Triage abstraction

```
triage/
  TriageService.java          interface: TriageResult triage(TriageInput input)
  TriageInput.java            record { String title, String description, BigDecimal amount }
  TriageResult.java           record { String summary, Category suggestedCategory, List<MissingInfoFlag> missingInfoFlags }
  MissingInfoFlag.java        enum: MISSING_AMOUNT, VAGUE_DESCRIPTION, MISSING_DATE, MISSING_PROVIDER
  MockTriageService.java      deterministic default impl
  AnthropicTriageService.java config-gated real impl (Anthropic Java SDK)
  TriageConfig.java           selects the bean by SCHADENFLOW_TRIAGE_PROVIDER
  TriageUnavailableException.java  thrown by the real adapter on any failure
```

(`Category` lives in `ch.sumex.schadenflow.claim` alongside `ClaimState`, since it
is a persisted `Claim` field.)

- **`MockTriageService` (default, used in all tests):** fully deterministic.
  - `summary`: a templated one-line summary derived from the description (e.g.
    first sentence / truncated), never an LLM call.
  - `suggestedCategory`: keyword match over the lower-cased description
    (`zahn`→`ZAHNARZT`, `apotheke`/`medikament`→`MEDIKAMENTE`, `spital`/`klinik`→`SPITAL`,
    `therapie`→`THERAPIE`, `brille`/`hilfsmittel`→`HILFSMITTEL`, `arzt`→`ARZTKOSTEN`,
    default `SONSTIGES`).
  - `missingInfoFlags`: rule-based (`amount` == 0 → `MISSING_AMOUNT`; description
    shorter than a threshold → `VAGUE_DESCRIPTION`; no date-like token → `MISSING_DATE`).
- **`AnthropicTriageService` (config-gated):** selected only when
  `SCHADENFLOW_TRIAGE_PROVIDER=claude` **and** `ANTHROPIC_API_KEY` is set. Uses the
  Anthropic Java SDK with a structured prompt that constrains `suggestedCategory`
  to the `Category` enum and asks for a JSON object; parses the response into
  `TriageResult` (unknown category → `SONSTIGES`). Any failure — timeout, API
  error, malformed JSON — throws `TriageUnavailableException`; the API key and raw
  provider error are never logged or returned. The current Claude model id and SDK
  usage are confirmed against the `claude-api` skill during planning.
- **`TriageConfig`:** a `@Configuration` that wires the impl by
  `SCHADENFLOW_TRIAGE_PROVIDER` (default `mock`). Tests never set `claude`.

## 6. API

All routes are already behind SP3's JWT filter chain (authenticated by default).

- **`POST /api/claims/{id}/triage`** → `ApiResponse<TriageResponse>` where
  `TriageResponse { String summary, Category suggestedCategory, List<MissingInfoFlag> missingInfoFlags }`.
  - Reviewer-only (`SACHBEARBEITER`/`ADMIN`); a claimant → **403** `FORBIDDEN`.
  - Claim must exist (**404** otherwise) and be `EINGEREICHT` or `IN_PRUEFUNG`
    (else **422** `VALIDATION`, "Triage nur vor der Entscheidung möglich").
  - Loads the claim, calls `TriageService`, returns the suggestion. **Persists nothing.**
- **`PATCH /api/claims/{id}`** → `ApiResponse<ClaimResponse>`.
  - Body `UpdateClaimRequest { @NotNull Category category, @Size(max=2000) String triageSummary }`.
  - Reviewer-only; claimant → **403**. Claim must exist (**404**).
  - Sets `category` + `triageSummary` + `updatedAt` atomically; returns the updated claim.
  - Invalid/unknown category → **400** (enum binding).
  - Not state-gated: a reviewer may set the category/summary regardless of claim
    state (the triage that informs the choice is the pre-decision step).

The controller reads the actor via `@AuthenticationPrincipal AuthenticatedUser`
(SP3 pattern) and threads `actor.userId()` / `actor.role()` into the service.

## 7. Service layer

`TriageService` is injected into `ClaimService`, which already owns claim loading,
role enforcement, and transaction boundaries. New methods:

- `TriageResult triage(UUID claimId, UUID actorId, Role actorRole)` — load claim,
  assert reviewer role + pre-decision state, delegate to `TriageService`. Persists nothing.
- `Claim updateCategory(UUID claimId, Category category, String triageSummary, UUID actorId, Role actorRole)`
  — load claim, assert reviewer role, set fields + `updatedAt`, save (`@Transactional`).

Reviewer-role enforcement reuses the role model from SP2/SP3; a typed
`DomainException.ForbiddenError` maps to 403. Wrong-state and validation failures
use `DomainException.ValidationError` → 422.

## 8. Error handling (standard envelope)

| Condition | HTTP | Envelope code |
|-----------|------|---------------|
| Real adapter failure (timeout/API/parse) | 503 | `TRIAGE_UNAVAILABLE` |
| Triage on a post-decision claim | 422 | `VALIDATION` |
| Non-reviewer triggers triage/confirm | 403 | `FORBIDDEN` |
| Claim not found | 404 | `NOT_FOUND` |
| Unknown category in confirm body | 400 | (bean-validation/parse) |

`TriageUnavailableException` gets a new `@ExceptionHandler` in
`GlobalExceptionHandler` (before the catch-all), mapping to 503 via the existing
`build(HttpStatus, code, message)` helper. The handler message is generic; the
provider error is logged server-side only, never the API key.

## 9. Config / secrets

- `SCHADENFLOW_TRIAGE_PROVIDER` — `mock` (default) | `claude`.
- `ANTHROPIC_API_KEY` — required only when provider is `claude`; never logged.
- `docker-compose.yml` passes both through to the backend (optional, defaulting to
  `mock` with no key). `README.md` documents the toggle and that the default is a
  deterministic, secret-free mock.
- CI and all tests run with the mock provider.

## 10. Testing strategy (TDD)

- **`MockTriageService` unit tests** — deterministic summary, category keyword
  mapping (incl. default `SONSTIGES`), and each `MissingInfoFlag` rule.
- **Triage endpoint** (`@WebMvcTest`, real filter chain per SP3) — reviewer gets
  200 + suggestion shape; claimant → 403; post-decision claim → 422; missing → 404.
- **Confirm endpoint + service** — applies `category` + `triageSummary`;
  reviewer-only (claimant 403); unknown category → 400; persists and returns updated claim.
- **`AnthropicTriageService` unit test** — with a **mocked** SDK/HTTP client (no
  network): verifies prompt construction, JSON→`TriageResult` parsing, unknown
  category → `SONSTIGES`, and every failure path → `TriageUnavailableException`.
- **Integration (`Testcontainers`)** — drive triage→confirm end-to-end via
  login/JWT on the mock provider: assert the triage call persists nothing, then the
  PATCH confirm persists `category` + `triageSummary`.
- Full `mvn verify` green (unit + all ITs on real Postgres).

## 11. Definition of done

- `Category` + `MissingInfoFlag` enums exist; `Claim` is enum-backed with a
  `triageSummary` column via `V4`; `mvn verify` green.
- `POST /api/claims/{id}/triage` returns an advisory suggestion (summary +
  enum category + flags) and persists nothing; reviewer-only; pre-decision only.
- `PATCH /api/claims/{id}` applies the human-confirmed category + summary;
  reviewer-only; invalid category rejected.
- `TriageService` has a deterministic mock (default) and a config-gated Anthropic
  adapter that fails safe (503, no secret leakage); the provider is env-selected.
- README documents the provider toggle; CI runs on the mock.

## 12. Integrity notes

- AI triage is advisory and human-confirmed; no autonomous-decisioning claims.
- The default path is a deterministic mock — the project builds and tests fully
  without any API key or network access.
- The real adapter is real (not faked) but optional and clearly gated.
