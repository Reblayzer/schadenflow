# Schadenflow — tailored project for Sumex SA (Junior Software Engineer Java)

## One-line pitch
A small health-insurance claims-management portal: a claim moves through a stewarded approval workflow with an audit trail and role-based access, plus an AI triage step that summarises a free-text claim and suggests a category.

## Why this project for this posting
Sumex builds ERP and claims-management (Schadenmanagement) software for the Swiss healthcare sector, and the posting names Java, Spring Boot, Angular, JavaScript, and AI tools (Claude Code, AI-based code reviews) explicitly. Schadenflow exercises that exact stack on Sumex's own problem domain, so it reads as direct, relevant evidence rather than a generic demo.

## Posting technologies it demonstrates
- **Java** (core backend language)
- **Spring Boot** (REST API, service layer, persistence)
- **Angular** (TypeScript frontend, claims dashboard)
- **JavaScript / TypeScript** (frontend)
- **REST** API design, **PostgreSQL**, **Docker**, **CI/CD** (GitHub Actions)
- **AI integration**: an LLM-backed triage endpoint (claim summary + category suggestion), and the whole thing built with Claude Code (custom skills + MCP) — mirrors their "AI-Tools" and "AI-basierte Code Reviews" emphasis.

## Architecture sketch
- **Backend (Java + Spring Boot):** REST API exposing claims, a state machine for the claim lifecycle, JPA/Hibernate persistence to PostgreSQL, role-based access (Anspruchsteller / Sachbearbeiter / Admin), and an append-only audit log of every state transition.
- **Claim lifecycle (state machine):** `eingereicht -> in Pruefung -> genehmigt | abgelehnt -> ausbezahlt`. Each transition is validated server-side, recorded with actor + timestamp + reason.
- **AI triage service:** on submission, a triage endpoint calls an LLM to (a) produce a short summary of the free-text claim description, (b) suggest a category, and (c) flag obviously missing information. The suggestion is advisory; a Sachbearbeiter always confirms. Critical: AI output is reviewed, never auto-applied (matches Alex's "steer, don't blindly accept" stance).
- **Frontend (Angular):** a claims dashboard (filter by state/owner), a claim detail view with the workflow actions, and the audit trail. TypeScript + Angular services talking to the REST API.
- **Infra:** Docker Compose (API + Angular + PostgreSQL), GitHub Actions CI (build, unit tests, lint).

## v1 scope
**In:**
- Claim CRUD + the four-state lifecycle with server-side transition validation.
- Role-based access for the three roles.
- Append-only audit trail per claim.
- AI triage endpoint (summary + category suggestion) with a clear "AI suggestion, please confirm" UI treatment.
- Angular dashboard + detail view.
- JUnit tests on the state machine and the service layer; Docker Compose; GitHub Actions pipeline.

**Out (v1):**
- Real Swiss tariff (TARMED/TARDOC) validation — out of scope, too domain-heavy for v1.
- Real payment integration (the "ausbezahlt" state is modelled, not wired to money).
- Production deployment / real PII data (synthetic claims only).
- Mobile app.

## Build plan
1. Spring Boot skeleton: entities (Claim, AuditEntry, User/role), PostgreSQL via JPA, REST controllers.
2. Claim state machine + server-side transition validation + audit logging.
3. Role-based access (Spring Security, three roles).
4. AI triage endpoint (LLM call behind an interface so it's swappable / mockable in tests).
5. Angular frontend: dashboard, detail view, workflow actions, audit trail.
6. JUnit tests (state machine + services), Dockerfile + docker compose, GitHub Actions CI.
7. README with architecture + a note on the AI-review-in-the-loop workflow. Make the repo public, then add the link to the CV.

## Repo
`github.com/Reblayzer/schadenflow` (create when build starts; do NOT put the link on the CV until the repo exists and is public).

## Integrity notes
- Describe behaviour and stack only. No invented metrics, users, or "deployed in production" claims.
- The AI triage is advisory and reviewed; do not claim autonomous decisioning.
- Built solo with Claude Code; fully explainable in an interview by the time anyone replies.
