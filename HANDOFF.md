# HANDOFF — Schadenflow sub-project 4 (AI Triage)

**Written:** 2026-06-26. Resuming from a **different PC** — clone/fetch first, then read this.
**This file is the sole recovery doc:** the `.superpowers/sdd/` ledger, task briefs, and
reports are git-ignored and live only on the PC where the work was done — they are NOT on
the remote. Everything you need is below, plus the committed spec/plan.

## TL;DR

**Sub-project 4 (AI Triage) is fully implemented and reviewed — NOT yet merged.** All 5
tasks are committed on `feat/ai-triage`, every per-task review passed, and the final
whole-branch review verdict is **Ready to merge: YES** (no Critical, no Important). Full
`mvn verify` is green at **74 tests** on the default `mock` provider. Remaining work:
optionally apply a small deferred fix wave (below), then PR → CI → squash-merge.

## Branch / remote state (already pushed)

- **Work branch:** `feat/ai-triage` (off `main` @ `651e1e8`), pushed to `origin`.
- **`main`:** the SP4 **spec** (`cf8e5fa`) and **plan** (`651e1e8`) were committed directly to
  `main` (the repo's convention for docs) and pushed to `origin/main`. `feat/ai-triage`
  branches from the plan commit.
- **Spec:** `docs/superpowers/specs/2026-06-26-ai-triage-design.md`
- **Plan (AUTHORITATIVE — 5 tasks):** `docs/superpowers/plans/2026-06-26-ai-triage.md`
- Public repo: `github.com/Reblayzer/schadenflow`.

On the new PC: `git fetch origin && git checkout feat/ai-triage`. Verify with
`git log --oneline main..feat/ai-triage` (should show the 5 commits below) and
`cd backend && mvn -q verify` (Docker required; expect 74 green).

## The 5 commits on feat/ai-triage

```
0e7ce22 test(triage): drive triage->confirm e2e and document the provider toggle
cadd3e9 feat(triage): add config-gated Anthropic Claude adapter with 503 fail-safe
31aef56 feat(claim): add triage and category-confirm endpoints
ec0a90c feat(triage): add TriageService interface and deterministic mock
97dc83e feat(claim): make category an enum and add triage_summary column
```

## What SP4 built (per task — all reviewed Approved, no Critical/Important)

1. **Schema** (`97dc83e`) — `Category` enum (claim pkg, `EnumType.STRING`: ARZTKOSTEN,
   MEDIKAMENTE, SPITAL, ZAHNARZT, THERAPIE, HILFSMITTEL, SONSTIGES); `Claim.category`
   String→`Category` (kept nullable, existing rows NULL → no backfill); added
   `triageSummary` (`triage_summary VARCHAR(2000)`, Flyway `V4`); `ClaimResponse` updated.
2. **Triage abstraction** (`ec0a90c`) — `triage/` pkg: `MissingInfoFlag` enum,
   `TriageInput`/`TriageResult` records, `TriageService` interface, deterministic
   `MockTriageService` (keyword→category, rule-based flags, templated summary). Wired by
   `TriageConfig` `@Bean` `@ConditionalOnProperty(provider=mock, matchIfMissing=true)` —
   NOT `@Service`, so the claude bean adds cleanly. Mock is the default + the test path.
3. **Endpoints** (`31aef56`) — `ClaimService.triage(...)` (`@Transactional(readOnly=true)`,
   reviewer-only, pre-decision state only, **persists nothing**) and `updateCategory(...)`
   (reviewer-only, sets category+triageSummary+updatedAt, saves, NOT state-gated).
   `POST /api/claims/{id}/triage` + `PATCH /api/claims/{id}`. DTOs `TriageResponse`,
   `UpdateClaimRequest(@NotNull Category, @Size(max=2000) String)`. `assertReviewer` runs
   BEFORE the claim load (claimant → 403 before any 404 — no data oracle).
4. **Claude adapter** (`cadd3e9`) — `AnthropicTriageService` (anthropic-java 2.34.0),
   config-gated `@ConditionalOnProperty(provider=claude)` so `AnthropicOkHttpClient.fromEnv()`
   / `ANTHROPIC_API_KEY` is ONLY touched when provider=claude (default mock needs no key).
   Lenient parse (`parseResponseJson`): unknown category → SONSTIGES, unknown flag dropped;
   any failure → `TriageUnavailableException` → `GlobalExceptionHandler` 503
   `TRIAGE_UNAVAILABLE` with a GENERIC message (no key/provider detail leaked).
5. **E2E + docs** (`0e7ce22`) — `TriageFlowIT` (login/JWT, deterministic ZAHNARZT, asserts
   triage persists nothing via `doesNotExist()` since `ApiResponse` is `@JsonInclude(NON_NULL)`,
   PATCH applies category+summary, claimant→403). `docker-compose.yml` passes
   `SCHADENFLOW_TRIAGE_PROVIDER`/`_MODEL` + `ANTHROPIC_API_KEY` (defaults mock/claude-opus-4-8/empty).
   README "AI Triage" section.

## Key design decisions (from the spec — don't relitigate)

On-demand transient triage (persists nothing) + separate confirm; fixed `Category` enum;
reviewers-only (`SACHBEARBEITER`/`ADMIN`) for triage AND confirm; triage only pre-decision
(EINGEREICHT/IN_PRUEFUNG, else 422); PATCH confirm is NOT state-gated and writes NO audit row
(accepted SP4 scope — see backlog note below); mock provider default + config-gated real
Anthropic adapter; AI output advisory and human-confirmed, never auto-applied; tests/CI always
use the mock. Commit trailers on every commit; stage specific files only.

## DEFERRED final-review fix wave (NOT applied — you interrupted it; apply on resume if you want)

The final review cleared merge-as-is; these are all **Minor**. The 3 most valuable (I was
about to apply them in one commit when we stopped):

1. **Operability:** `shared/GlobalExceptionHandler.java` `handleTriageUnavailable(...)` logs
   NOTHING — add an SLF4J logger + `log.warn("Triage unavailable", ex);` before the return
   (server-side only; keep the client envelope generic, never log the key).
2. **Immutability:** `triage/MockTriageService.java` `flags(...)` returns a mutable `ArrayList`
   — `return List.copyOf(flags);`.
3. **Coverage gap (the one untested production path):** `AnthropicTriageService.triage(...)`'s
   SDK-call → response-text extraction (`response.content().stream().flatMap(b->b.text()...)`)
   has NO runtime test (only `parseResponseJson` + the failure path are tested). Add a no-network
   happy-path test stubbing `messages.create(any(MessageCreateParams.class))` to return a mocked
   `Message` whose content yields a JSON triage object; if the SDK `Message`/content types are
   hard to mock, extract a package-private `extractText(Message)` or `triageFromText(String)` seam
   and test that.

Other documented Minors (pure hygiene — fix or leave): `updateCategoryAppliesValuesForReviewer`
doesn't assert `updatedAt` changed; no `@WebMvcTest`-layer claimant→403 test on the new endpoints
(covered at service layer + IT); `patchWithInvalidCategoryReturns400` asserts status only not
`$.error.code`; `AnthropicTriageServiceTest` import ordering; `AnthropicTriageService` inline
`java.util.Optional` FQNs; `TriageFlowIT` asserts the PATCH response not a follow-up GET and
doesn't assert `missingInfoFlags`.

Backlog (out of SP4 scope, noted by the reviewer): the confirm PATCH is not state-gated and
writes no audit row — a reviewer can overwrite category/summary on an already-decided/paid claim
with no trail. Candidate for the audit-enhancement backlog.

## How to resume (exact steps)

1. `git fetch origin && git checkout feat/ai-triage && cd backend && mvn -q verify` — expect 74 green.
2. (Optional but recommended) Apply the 3 deferred fixes above in one commit; re-run `mvn verify`.
   Re-review is optional for such small, clearly-correct changes — your call.
3. Invoke **superpowers:finishing-a-development-branch**. The branch is already pushed, so:
   open a PR `feat/ai-triage` → `main` (`gh pr create --base main --head feat/ai-triage`); PR body
   = summary + test plan (mvn verify 74 green on mock) + note the AI output is advisory/human-confirmed
   and the provider defaults to mock (no key in CI). End the PR body with the Claude Code footer.
4. Watch CI green (`gh pr checks <N> --watch`), then **squash-merge** (`gh pr merge <N> --squash
   --delete-branch`). The `gh` local fast-forward may fail because local main diverges (SP1-3
   squash-merges) — that's expected; the server-side merge still succeeds (verify with
   `gh pr view <N> --json state`).
5. After merge: realign local main — `git fetch origin && git checkout main &&
   git reset --hard origin/main`. Delete this branch locally if not auto-deleted.
6. **Delete this `HANDOFF.md`** (commit the deletion to main, or it'll linger) once SP4 is merged.

## Project roadmap (overall design §9)

SP1 infra, SP2 claim domain, SP3 security — DONE & merged. **SP4 AI triage — this branch, ready
to merge.** Next: **SP5 Angular frontend** (login, dashboard, claim detail + workflow actions,
audit trail, "AI suggestion — confirm" treatment), then **SP6 polish** (README, final CI, make
repo public). Each sub-project = brainstorm → spec → plan → subagent-driven implement → review →
PR, same as SP1-4. There is also a standing SP3 prod-hardening follow-up documented in
`README.md` ("Production hardening"): gate the dev seed behind a prod profile + fail-fast on the
default `SECURITY_JWT_SECRET` — required before any non-dev deploy.

## Delete this file once SP4 is merged.
