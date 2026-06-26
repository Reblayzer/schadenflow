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

## Status

Sub-project 1 (infra & skeleton) complete. See `docs/superpowers/specs/` for the
v1 design and sub-project roadmap.
