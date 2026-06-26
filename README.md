# Schadenflow

A small health-insurance claims-management portal: a claim moves through a
stewarded approval workflow with an append-only audit trail and role-based
access, plus an advisory AI triage step (summary + category suggestion) that a
caseworker always confirms.

> Portfolio project. Synthetic data only — no real PII, no production deployment,
> no real payment integration.

## Tech stack

- **Backend:** Java 21, Spring Boot, Maven, PostgreSQL (JPA/Hibernate)
- **Frontend:** Angular (standalone + signals), Angular Material
- **Infra:** Docker Compose, GitHub Actions CI

## Repository layout

| Path        | Contents |
|-------------|----------|
| `backend/`  | Spring Boot REST API |
| `frontend/` | Angular app |
| `infra/`    | Dockerfiles, nginx config |
| `docs/`     | Design specs and implementation plans |

## Running locally

Requires Docker. From the repo root:

```bash
docker compose up --build
```

- API health: <http://localhost:8080/api/health>
- Frontend: <http://localhost:4200>

## Running tests

```bash
# backend
cd backend && mvn verify        # needs Docker (Testcontainers)

# frontend
cd frontend && npm ci && npm test -- --watch=false --browsers=ChromeHeadless
```

### Local development notes

**WSL2 + Docker Desktop:** Testcontainers (used by `mvn verify`) may fail with a
Docker API version error (`MinAPIVersion` / HTTP 400) because Docker Desktop
enforces a newer minimum API version than the embedded docker-java client probes
for. If you hit this, create `~/.docker-java.properties` with a single line:

```
api.version=1.44
```

This is a local-machine config only — it is not needed in CI (GitHub's
`ubuntu-latest` uses a stock Docker daemon).

## Authentication

All `/api/*` endpoints (except `/api/health`) require a JWT bearer token.

**Login:**

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password123"}'
```

Returns `{ "ok": true, "data": { "token": "<jwt>", "role": "ADMIN" } }`.

**Send the token on subsequent requests:**

```bash
curl -s http://localhost:8080/api/claims \
  -H "Authorization: Bearer <token>"
```

**Seeded dev users** (synthetic data only — not real users):

| Username | Password | Role |
|---|---|---|
| `anspruchsteller` | `password123` | ANSPRUCHSTELLER |
| `sachbearbeiter` | `password123` | SACHBEARBEITER |
| `admin` | `password123` | ADMIN |

The JWT signing secret is read from the `SECURITY_JWT_SECRET` environment variable
(defaults to an insecure dev value in Compose; set a strong secret in production).

Wrong credentials return `401` with `{ "error": { "code": "INVALID_CREDENTIALS" } }`.
Missing or invalid token returns `401` with `{ "error": { "code": "UNAUTHORIZED" } }`.

### Production hardening (required before any non-dev deployment)

Two known blocking items were deferred from SP3 and **must** be resolved before
the app runs outside a developer laptop:

1. **Gate the seed migrations behind a dev profile.** `spring.flyway.locations`
   currently includes `classpath:db/seed` unconditionally, so the three dev users
   (including an ADMIN with the public password `password123`) are inserted in
   every environment. Before any non-dev deploy, move the seed location into a
   `application-dev.properties` override so production runs `classpath:db/migration`
   only.
2. **Fail fast when the JWT secret is the dev default.** `SECURITY_JWT_SECRET`
   falls back to a publicly-known value when unset. Before any non-dev deploy, add
   a startup check (e.g. an `ApplicationListener` or `@PostConstruct`) that aborts
   with a clear error if the secret equals the dev default (or is shorter than 32
   bytes) outside the `dev` Spring profile.

## AI Triage

A reviewer (`SACHBEARBEITER` or `ADMIN`) can request an AI-generated triage on
any pre-decision claim (state `EINGEREICHT` or `IN_PRUEFUNG`).

**Endpoints:**

```bash
# Request an advisory triage (reviewer only, persists nothing)
POST /api/claims/{id}/triage
Authorization: Bearer <reviewer-token>
```

Returns `{ "ok": true, "data": { "summary": "...", "suggestedCategory": "ZAHNARZT", "missingInfoFlags": [...] } }`.

```bash
# Confirm the human-reviewed category and summary (reviewer only)
PATCH /api/claims/{id}
Authorization: Bearer <reviewer-token>
Content-Type: application/json

{ "category": "ZAHNARZT", "triageSummary": "Bestätigte Zahnbehandlung." }
```

The AI output is **advisory only** — it is never auto-applied. A caseworker always
confirms the category and summary via `PATCH` before it is persisted.

**Provider toggle:**

| Variable | Default | Notes |
|---|---|---|
| `SCHADENFLOW_TRIAGE_PROVIDER` | `mock` | `mock` (deterministic, no API key, used in CI/tests) or `claude` (real Anthropic API) |
| `SCHADENFLOW_TRIAGE_MODEL` | `claude-opus-4-8` | Only used when provider is `claude` |
| `ANTHROPIC_API_KEY` | *(empty)* | Required when provider is `claude` |

The mock provider is deterministic and requires no API key — it is the default for
all tests and CI. To switch to the real Anthropic adapter in a local Compose run,
set `SCHADENFLOW_TRIAGE_PROVIDER=claude` and `ANTHROPIC_API_KEY=<your-key>` in
your environment before running `docker compose up`.

## Status

Sub-project 1 (infra & skeleton) complete. See `docs/superpowers/specs/` for the
v1 design and sub-project roadmap.
