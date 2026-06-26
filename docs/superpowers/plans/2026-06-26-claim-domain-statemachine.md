# Claim Domain + State Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Schadenflow claim domain — a Claim entity with a server-validated, role-aware state machine and an append-only audit trail — exposed over a REST API with a consistent response envelope and Flyway-managed schema.

**Architecture:** Feature-grouped packages (`shared`, `claim`, `audit`) under `ch.sumex.schadenflow`. A pure hand-rolled `ClaimStateMachine` answers legality + role questions; `ClaimService` orchestrates persistence + audit in single transactions; a thin `ClaimController` maps to REST; one `@RestControllerAdvice` maps typed domain errors to HTTP. Flyway owns the schema (`ddl-auto=validate`); repositories use Spring Data JPA.

**Tech Stack:** Java 21, Spring Boot 3.4.1, Spring Data JPA, Flyway, PostgreSQL, Jakarta Bean Validation, JUnit 5, Mockito, Testcontainers, Failsafe.

## Global Constraints

- **Package root:** `ch.sumex.schadenflow`. Build on the existing backend (do not touch `health/` or SP1 infra).
- **Java 21, Maven, Spring Boot 3.4.1.**
- **Language convention:** German enum *values* (`EINGEREICHT`, `SACHBEARBEITER`, …); English for all code identifiers, JSON field names, and URL paths.
- **API envelope:** success `{ "ok": true, "data": <payload> }`; error `{ "ok": false, "error": { "code": "<CODE>", "message": "<human>" } }`. All endpoints under `/api`.
- **Error → HTTP mapping (single `@RestControllerAdvice`):** request binding / Bean Validation → 400 `VALIDATION_ERROR`; domain `ValidationError` → 422 `VALIDATION_ERROR`; `NotFoundError` → 404 `NOT_FOUND`; `ForbiddenError` → 403 `FORBIDDEN`; `IllegalTransitionError` → 409 `ILLEGAL_TRANSITION`; uncaught → 500 `INTERNAL_ERROR` (generic message; log details server-side).
- **Actor (temporary, pre-auth):** mutating requests carry required headers `X-Actor-Id` (UUID) and `X-Actor-Role` (Role enum). Missing/unparseable → 400 via request binding.
- **State machine edges + roles:** EINGEREICHT→IN_PRUEFUNG (SACHBEARBEITER, ADMIN); IN_PRUEFUNG→GENEHMIGT (SACHBEARBEITER, ADMIN); IN_PRUEFUNG→ABGELEHNT (SACHBEARBEITER, ADMIN); GENEHMIGT→AUSBEZAHLT (ADMIN). Terminal: ABGELEHNT, AUSBEZAHLT.
- **Reject requires a reason:** transitioning to `ABGELEHNT` with a blank/absent `reason` → 422 `ValidationError`.
- **Atomicity:** claim creation writes the claim + its creation audit row (`fromState=null`) in one transaction; each transition updates the claim + appends one audit row in one transaction.
- **No update/delete endpoints. Audit is append-only.** Pagination on the list endpoint (default size 20, max 100); never unbounded.
- **Commits:** Conventional Commits, stage specific files only (never `git add .`). End each commit message with:
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01YMfonK7B5XK8NccWNrMAoZ
  ```

---

## Scope notes

- **`category` stays `null` in SP2.** The column and entity field exist, but no
  endpoint sets it: `POST /api/claims` does not accept a category, and there is no
  update endpoint. This is consistent with spec §9 (the category taxonomy is owned
  by AI triage in SP4, with human confirmation). The spec's "human-set now"
  phrasing in §3 is reconciled here as "the field exists now; it becomes settable
  in SP4." Do not add a category input in this sub-project.
- **Timestamp columns use `TIMESTAMPTZ`** to match Hibernate 6's default mapping
  of `java.time.Instant` (→ `timestamp with time zone`). With `ddl-auto=validate`,
  `ClaimPersistenceIT` (Task 3) is what proves entity ↔ migrated-schema alignment;
  if validation ever complains about a column type, align the migration to
  Hibernate's expected type rather than weakening `validate`.

---

## File Structure

```
backend/
  pom.xml                                  (modify: add flyway, validation deps)
  src/main/resources/
    application.properties                 (modify: ddl-auto=validate, flyway on)
    db/migration/
      V1__create_claims.sql
      V2__create_audit_entries.sql
  src/main/java/ch/sumex/schadenflow/
    shared/
      ApiResponse.java                      record envelope
      DomainException.java                  base + NotFoundError, ValidationError,
                                            ForbiddenError, IllegalTransitionError
      GlobalExceptionHandler.java           @RestControllerAdvice
    claim/
      ClaimState.java                       enum
      Role.java                             enum
      Claim.java                            @Entity
      ClaimRepository.java                  Spring Data JPA repo
      ClaimStateMachine.java                pure transition table + role rules
      ClaimService.java                     orchestration + transactions
      ClaimController.java                  REST endpoints
      dto/
        CreateClaimRequest.java
        TransitionRequest.java
        ClaimResponse.java
        AuditEntryResponse.java
    audit/
      AuditEntry.java                       @Entity
      AuditRepository.java                  Spring Data JPA repo
  src/test/java/ch/sumex/schadenflow/
    claim/ClaimStateMachineTest.java
    claim/ClaimServiceTest.java
    claim/ClaimControllerTest.java
    claim/ClaimFlowIT.java
```

---

## Task 1: Shared response envelope + typed domain errors + exception handler

**Files:**
- Create: `backend/src/main/java/ch/sumex/schadenflow/shared/ApiResponse.java`
- Create: `backend/src/main/java/ch/sumex/schadenflow/shared/DomainException.java`
- Create: `backend/src/main/java/ch/sumex/schadenflow/shared/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/ch/sumex/schadenflow/shared/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `ApiResponse.ok(T data)` → `ApiResponse<T>` with `ok=true, data, error=null`.
  - `ApiResponse.error(String code, String message)` → `ApiResponse<?>` with `ok=false, data=null, error=ErrorBody`.
  - Exceptions (all in `DomainException.java`): `NotFoundError(String message)`, `ValidationError(String message)`, `ForbiddenError(String message)`, `IllegalTransitionError(String message)` — each extends `DomainException`.
  - `GlobalExceptionHandler` maps those + Spring binding exceptions to the envelope with the documented status/code.

- [ ] **Step 1: Create `ApiResponse.java`** (generic record envelope)

```java
package ch.sumex.schadenflow.shared;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean ok, T data, ApiResponse.ErrorBody error) {

    public record ErrorBody(String code, String message) {}

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Object> error(String code, String message) {
        return new ApiResponse<>(false, null, new ErrorBody(code, message));
    }
}
```

- [ ] **Step 2: Create `DomainException.java`** (base + four typed errors)

```java
package ch.sumex.schadenflow.shared;

public abstract class DomainException extends RuntimeException {
    protected DomainException(String message) {
        super(message);
    }

    public static class NotFoundError extends DomainException {
        public NotFoundError(String message) { super(message); }
    }

    public static class ValidationError extends DomainException {
        public ValidationError(String message) { super(message); }
    }

    public static class ForbiddenError extends DomainException {
        public ForbiddenError(String message) { super(message); }
    }

    public static class IllegalTransitionError extends DomainException {
        public IllegalTransitionError(String message) { super(message); }
    }
}
```

- [ ] **Step 3: Write the failing test** `GlobalExceptionHandlerTest.java` (standalone MockMvc with a tiny throwing controller)

```java
package ch.sumex.schadenflow.shared;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    @RestController
    static class ThrowingController {
        @GetMapping("/boom/notfound")
        String notFound() { throw new DomainException.NotFoundError("claim 1 not found"); }

        @GetMapping("/boom/illegal")
        String illegal() { throw new DomainException.IllegalTransitionError("EINGEREICHT -> AUSBEZAHLT not allowed"); }

        @GetMapping("/boom/forbidden")
        String forbidden() { throw new DomainException.ForbiddenError("role may not perform this"); }

        @GetMapping("/boom/validation")
        String validation() { throw new DomainException.ValidationError("reason is required"); }
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void notFoundMapsTo404() throws Exception {
        mockMvc.perform(get("/boom/notfound").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("claim 1 not found"));
    }

    @Test
    void illegalTransitionMapsTo409() throws Exception {
        mockMvc.perform(get("/boom/illegal").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_TRANSITION"));
    }

    @Test
    void forbiddenMapsTo403() throws Exception {
        mockMvc.perform(get("/boom/forbidden").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void domainValidationMapsTo422() throws Exception {
        mockMvc.perform(get("/boom/validation").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `cd backend && mvn -q -Dtest=GlobalExceptionHandlerTest test`
Expected: FAIL — `GlobalExceptionHandler` does not exist (compile error).

- [ ] **Step 5: Create `GlobalExceptionHandler.java`**

```java
package ch.sumex.schadenflow.shared;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.NotFoundError.class)
    public ResponseEntity<ApiResponse<Object>> handleNotFound(DomainException.NotFoundError ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(DomainException.IllegalTransitionError.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalTransition(DomainException.IllegalTransitionError ex) {
        return build(HttpStatus.CONFLICT, "ILLEGAL_TRANSITION", ex.getMessage());
    }

    @ExceptionHandler(DomainException.ForbiddenError.class)
    public ResponseEntity<ApiResponse<Object>> handleForbidden(DomainException.ForbiddenError ex) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage());
    }

    @ExceptionHandler(DomainException.ValidationError.class)
    public ResponseEntity<ApiResponse<Object>> handleDomainValidation(DomainException.ValidationError ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", ex.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Object>> handleBadRequest(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUncaught(Exception ex) {
        // Log details server-side; return a generic message to the client.
        org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class)
                .error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private ResponseEntity<ApiResponse<Object>> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiResponse.error(code, message));
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && mvn -q -Dtest=GlobalExceptionHandlerTest test`
Expected: PASS — 4 tests green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/ch/sumex/schadenflow/shared/ backend/src/test/java/ch/sumex/schadenflow/shared/
git commit -m "feat(shared): add ApiResponse envelope, typed domain errors, exception handler

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01YMfonK7B5XK8NccWNrMAoZ"
```

---

## Task 2: Domain enums + hand-rolled state machine

**Files:**
- Create: `backend/src/main/java/ch/sumex/schadenflow/claim/ClaimState.java`
- Create: `backend/src/main/java/ch/sumex/schadenflow/claim/Role.java`
- Create: `backend/src/main/java/ch/sumex/schadenflow/claim/ClaimStateMachine.java`
- Test: `backend/src/test/java/ch/sumex/schadenflow/claim/ClaimStateMachineTest.java`

**Interfaces:**
- Consumes: `DomainException.IllegalTransitionError`, `DomainException.ForbiddenError` from Task 1.
- Produces:
  - `enum ClaimState { EINGEREICHT, IN_PRUEFUNG, GENEHMIGT, ABGELEHNT, AUSBEZAHLT }`
  - `enum Role { ANSPRUCHSTELLER, SACHBEARBEITER, ADMIN }`
  - `ClaimStateMachine` (a Spring `@Component`, but stateless) with:
    - `boolean isLegalTransition(ClaimState from, ClaimState to)`
    - `boolean isRoleAllowed(ClaimState from, ClaimState to, Role role)`
    - `void validateTransition(ClaimState from, ClaimState to, Role role)` — throws `IllegalTransitionError` if the edge is illegal, then `ForbiddenError` if the role is not permitted. Returns normally if allowed.

- [ ] **Step 1: Create `ClaimState.java`**

```java
package ch.sumex.schadenflow.claim;

public enum ClaimState {
    EINGEREICHT,
    IN_PRUEFUNG,
    GENEHMIGT,
    ABGELEHNT,
    AUSBEZAHLT
}
```

- [ ] **Step 2: Create `Role.java`**

```java
package ch.sumex.schadenflow.claim;

public enum Role {
    ANSPRUCHSTELLER,
    SACHBEARBEITER,
    ADMIN
}
```

- [ ] **Step 3: Write the failing test** `ClaimStateMachineTest.java`

```java
package ch.sumex.schadenflow.claim;

import org.junit.jupiter.api.Test;
import ch.sumex.schadenflow.shared.DomainException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ClaimStateMachineTest {

    private final ClaimStateMachine sm = new ClaimStateMachine();

    @Test
    void legalEdgesAreRecognised() {
        assertThat(sm.isLegalTransition(ClaimState.EINGEREICHT, ClaimState.IN_PRUEFUNG)).isTrue();
        assertThat(sm.isLegalTransition(ClaimState.IN_PRUEFUNG, ClaimState.GENEHMIGT)).isTrue();
        assertThat(sm.isLegalTransition(ClaimState.IN_PRUEFUNG, ClaimState.ABGELEHNT)).isTrue();
        assertThat(sm.isLegalTransition(ClaimState.GENEHMIGT, ClaimState.AUSBEZAHLT)).isTrue();
    }

    @Test
    void illegalEdgesAreRejected() {
        assertThat(sm.isLegalTransition(ClaimState.EINGEREICHT, ClaimState.AUSBEZAHLT)).isFalse();
        assertThat(sm.isLegalTransition(ClaimState.EINGEREICHT, ClaimState.GENEHMIGT)).isFalse();
        assertThat(sm.isLegalTransition(ClaimState.GENEHMIGT, ClaimState.ABGELEHNT)).isFalse();
        assertThat(sm.isLegalTransition(ClaimState.ABGELEHNT, ClaimState.IN_PRUEFUNG)).isFalse();
        assertThat(sm.isLegalTransition(ClaimState.AUSBEZAHLT, ClaimState.GENEHMIGT)).isFalse();
    }

    @Test
    void terminalStatesHaveNoOutgoingEdges() {
        for (ClaimState to : ClaimState.values()) {
            assertThat(sm.isLegalTransition(ClaimState.ABGELEHNT, to)).isFalse();
            assertThat(sm.isLegalTransition(ClaimState.AUSBEZAHLT, to)).isFalse();
        }
    }

    @Test
    void roleRulesAreEnforced() {
        // submit -> in Pruefung: Sachbearbeiter or Admin, not Anspruchsteller
        assertThat(sm.isRoleAllowed(ClaimState.EINGEREICHT, ClaimState.IN_PRUEFUNG, Role.SACHBEARBEITER)).isTrue();
        assertThat(sm.isRoleAllowed(ClaimState.EINGEREICHT, ClaimState.IN_PRUEFUNG, Role.ADMIN)).isTrue();
        assertThat(sm.isRoleAllowed(ClaimState.EINGEREICHT, ClaimState.IN_PRUEFUNG, Role.ANSPRUCHSTELLER)).isFalse();
        // pay out: Admin only
        assertThat(sm.isRoleAllowed(ClaimState.GENEHMIGT, ClaimState.AUSBEZAHLT, Role.ADMIN)).isTrue();
        assertThat(sm.isRoleAllowed(ClaimState.GENEHMIGT, ClaimState.AUSBEZAHLT, Role.SACHBEARBEITER)).isFalse();
    }

    @Test
    void validateThrowsIllegalTransitionForBadEdge() {
        assertThatThrownBy(() -> sm.validateTransition(ClaimState.EINGEREICHT, ClaimState.AUSBEZAHLT, Role.ADMIN))
                .isInstanceOf(DomainException.IllegalTransitionError.class);
    }

    @Test
    void validateThrowsForbiddenForDisallowedRole() {
        assertThatThrownBy(() -> sm.validateTransition(ClaimState.GENEHMIGT, ClaimState.AUSBEZAHLT, Role.SACHBEARBEITER))
                .isInstanceOf(DomainException.ForbiddenError.class);
    }

    @Test
    void validatePassesForAllowedTransition() {
        assertThatCode(() -> sm.validateTransition(ClaimState.IN_PRUEFUNG, ClaimState.GENEHMIGT, Role.SACHBEARBEITER))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `cd backend && mvn -q -Dtest=ClaimStateMachineTest test`
Expected: FAIL — `ClaimStateMachine` does not exist.

- [ ] **Step 5: Create `ClaimStateMachine.java`**

```java
package ch.sumex.schadenflow.claim;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import ch.sumex.schadenflow.shared.DomainException;

@Component
public class ClaimStateMachine {

    /** Allowed transition -> the roles permitted to perform it. */
    private record Edge(ClaimState from, ClaimState to) {}

    private static final Map<Edge, Set<Role>> TRANSITIONS = Map.of(
            new Edge(ClaimState.EINGEREICHT, ClaimState.IN_PRUEFUNG), Set.of(Role.SACHBEARBEITER, Role.ADMIN),
            new Edge(ClaimState.IN_PRUEFUNG, ClaimState.GENEHMIGT), Set.of(Role.SACHBEARBEITER, Role.ADMIN),
            new Edge(ClaimState.IN_PRUEFUNG, ClaimState.ABGELEHNT), Set.of(Role.SACHBEARBEITER, Role.ADMIN),
            new Edge(ClaimState.GENEHMIGT, ClaimState.AUSBEZAHLT), Set.of(Role.ADMIN)
    );

    public boolean isLegalTransition(ClaimState from, ClaimState to) {
        return TRANSITIONS.containsKey(new Edge(from, to));
    }

    public boolean isRoleAllowed(ClaimState from, ClaimState to, Role role) {
        Set<Role> allowed = TRANSITIONS.get(new Edge(from, to));
        return allowed != null && allowed.contains(role);
    }

    public void validateTransition(ClaimState from, ClaimState to, Role role) {
        if (!isLegalTransition(from, to)) {
            throw new DomainException.IllegalTransitionError(
                    "Transition %s -> %s is not allowed".formatted(from, to));
        }
        if (!isRoleAllowed(from, to, role)) {
            throw new DomainException.ForbiddenError(
                    "Role %s may not perform transition %s -> %s".formatted(role, from, to));
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && mvn -q -Dtest=ClaimStateMachineTest test`
Expected: PASS — all tests green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/ch/sumex/schadenflow/claim/ClaimState.java backend/src/main/java/ch/sumex/schadenflow/claim/Role.java backend/src/main/java/ch/sumex/schadenflow/claim/ClaimStateMachine.java backend/src/test/java/ch/sumex/schadenflow/claim/ClaimStateMachineTest.java
git commit -m "feat(claim): add domain enums and hand-rolled state machine

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01YMfonK7B5XK8NccWNrMAoZ"
```

---

## Task 3: Flyway dependency + migrations + JPA entities + repositories

**Files:**
- Modify: `backend/pom.xml` (add `flyway-core`, `flyway-database-postgresql`, `spring-boot-starter-validation`)
- Modify: `backend/src/main/resources/application.properties` (`ddl-auto=validate`, enable Flyway)
- Create: `backend/src/main/resources/db/migration/V1__create_claims.sql`
- Create: `backend/src/main/resources/db/migration/V2__create_audit_entries.sql`
- Create: `backend/src/main/java/ch/sumex/schadenflow/claim/Claim.java`
- Create: `backend/src/main/java/ch/sumex/schadenflow/audit/AuditEntry.java`
- Create: `backend/src/main/java/ch/sumex/schadenflow/claim/ClaimRepository.java`
- Create: `backend/src/main/java/ch/sumex/schadenflow/audit/AuditRepository.java`
- Test: `backend/src/test/java/ch/sumex/schadenflow/claim/ClaimPersistenceIT.java`

**Interfaces:**
- Consumes: `ClaimState`, `Role` (Task 2).
- Produces:
  - `Claim` entity with fields `id` (UUID), `claimantId` (UUID), `title`, `description`, `category` (nullable), `amount` (BigDecimal), `state` (ClaimState), `createdAt`, `updatedAt` (Instant) + getters/setters; no-arg + convenience constructor.
  - `AuditEntry` entity: `id` (UUID), `claimId` (UUID), `fromState` (ClaimState, nullable), `toState` (ClaimState), `actorId` (UUID), `actorRole` (Role), `reason` (String, nullable), `timestamp` (Instant).
  - `ClaimRepository extends JpaRepository<Claim, UUID>` with `Page<Claim> findByState(ClaimState state, Pageable p)`, `Page<Claim> findByClaimantId(UUID id, Pageable p)`, `Page<Claim> findByStateAndClaimantId(ClaimState s, UUID id, Pageable p)`.
  - `AuditRepository extends JpaRepository<AuditEntry, UUID>` with `List<AuditEntry> findByClaimIdOrderByTimestampAsc(UUID claimId)`.

- [ ] **Step 1: Add dependencies to `backend/pom.xml`** — insert inside `<dependencies>`, after the `spring-boot-starter-data-jpa` block:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
```

- [ ] **Step 2: Update `backend/src/main/resources/application.properties`** — change `ddl-auto` and enable Flyway. Replace the JPA lines so the file reads:

```properties
spring.application.name=schadenflow
server.port=8080

spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/schadenflow}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME:schadenflow}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:schadenflow}

spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false

spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
```

- [ ] **Step 3: Create `V1__create_claims.sql`**

```sql
CREATE TABLE claims (
    id           UUID PRIMARY KEY,
    claimant_id  UUID NOT NULL,
    title        VARCHAR(200) NOT NULL,
    description  TEXT NOT NULL,
    category     VARCHAR(100),
    amount       NUMERIC(12, 2) NOT NULL,
    state        VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_claims_state ON claims (state);
CREATE INDEX idx_claims_claimant_id ON claims (claimant_id);
```

- [ ] **Step 4: Create `V2__create_audit_entries.sql`**

```sql
CREATE TABLE audit_entries (
    id          UUID PRIMARY KEY,
    claim_id    UUID NOT NULL REFERENCES claims (id),
    from_state  VARCHAR(20),
    to_state    VARCHAR(20) NOT NULL,
    actor_id    UUID NOT NULL,
    actor_role  VARCHAR(20) NOT NULL,
    reason      TEXT,
    timestamp   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_entries_claim_id ON audit_entries (claim_id);
```

- [ ] **Step 5: Create `Claim.java`**

```java
package ch.sumex.schadenflow.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "claims")
public class Claim {

    @Id
    private UUID id;

    @Column(name = "claimant_id", nullable = false)
    private UUID claimantId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String description;

    @Column
    private String category;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimState state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Claim() { }

    public Claim(UUID id, UUID claimantId, String title, String description,
                 String category, BigDecimal amount, ClaimState state,
                 Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.claimantId = claimantId;
        this.title = title;
        this.description = description;
        this.category = category;
        this.amount = amount;
        this.state = state;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public UUID getClaimantId() { return claimantId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public BigDecimal getAmount() { return amount; }
    public ClaimState getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setCategory(String category) { this.category = category; }
    public void setState(ClaimState state) { this.state = state; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 6: Create `AuditEntry.java`**

```java
package ch.sumex.schadenflow.audit;

import ch.sumex.schadenflow.claim.ClaimState;
import ch.sumex.schadenflow.claim.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_entries")
public class AuditEntry {

    @Id
    private UUID id;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state")
    private ClaimState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false)
    private ClaimState toState;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false)
    private Role actorRole;

    @Column
    private String reason;

    @Column(nullable = false)
    private Instant timestamp;

    protected AuditEntry() { }

    public AuditEntry(UUID id, UUID claimId, ClaimState fromState, ClaimState toState,
                      UUID actorId, Role actorRole, String reason, Instant timestamp) {
        this.id = id;
        this.claimId = claimId;
        this.fromState = fromState;
        this.toState = toState;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.reason = reason;
        this.timestamp = timestamp;
    }

    public UUID getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public ClaimState getFromState() { return fromState; }
    public ClaimState getToState() { return toState; }
    public UUID getActorId() { return actorId; }
    public Role getActorRole() { return actorRole; }
    public String getReason() { return reason; }
    public Instant getTimestamp() { return timestamp; }
}
```

- [ ] **Step 7: Create `ClaimRepository.java`**

```java
package ch.sumex.schadenflow.claim;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {
    Page<Claim> findByState(ClaimState state, Pageable pageable);
    Page<Claim> findByClaimantId(UUID claimantId, Pageable pageable);
    Page<Claim> findByStateAndClaimantId(ClaimState state, UUID claimantId, Pageable pageable);
}
```

- [ ] **Step 8: Create `AuditRepository.java`**

```java
package ch.sumex.schadenflow.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEntry, UUID> {
    List<AuditEntry> findByClaimIdOrderByTimestampAsc(UUID claimId);
}
```

- [ ] **Step 9: Write the integration test** `ClaimPersistenceIT.java` (Testcontainers + Flyway; proves migrations apply and entities map to the migrated schema)

```java
package ch.sumex.schadenflow.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import ch.sumex.schadenflow.audit.AuditEntry;
import ch.sumex.schadenflow.audit.AuditRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ClaimPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ClaimRepository claimRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Test
    void persistsClaimAndAuditAgainstFlywaySchema() {
        UUID claimId = UUID.randomUUID();
        UUID claimantId = UUID.randomUUID();
        Instant now = Instant.now();

        claimRepository.save(new Claim(claimId, claimantId, "Broken arm", "Fell off a bike",
                null, new BigDecimal("250.00"), ClaimState.EINGEREICHT, now, now));
        auditRepository.save(new AuditEntry(UUID.randomUUID(), claimId, null,
                ClaimState.EINGEREICHT, claimantId, Role.ANSPRUCHSTELLER, null, now));

        assertThat(claimRepository.findById(claimId)).isPresent();
        assertThat(auditRepository.findByClaimIdOrderByTimestampAsc(claimId)).hasSize(1);
    }
}
```

- [ ] **Step 10: Run the integration test (proves Flyway migrations + mappings)**

Run: `cd backend && mvn -q -Dtest=ClaimPersistenceIT -DfailIfNoTests=false test`
Note: `ClaimPersistenceIT` ends in `IT`, so Surefire's `-Dtest=` is needed to run it under the `test` phase here. Expected: PASS — Testcontainers boots Postgres, Flyway applies V1+V2, the round-trip succeeds. (If the `-Dtest` filter does not pick it up, run `mvn -q verify` instead, which runs it via Failsafe.)

- [ ] **Step 11: Run `mvn verify` to confirm the IT runs under Failsafe and nothing regressed**

Run: `cd backend && mvn -q verify`
Expected: PASS — `ClaimPersistenceIT`, `ApplicationContextIT`, and all unit tests green. (`ddl-auto=validate` means Hibernate validates entities against the Flyway schema — a mapping/column mismatch fails here.)

- [ ] **Step 12: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.properties backend/src/main/resources/db/migration/ backend/src/main/java/ch/sumex/schadenflow/claim/Claim.java backend/src/main/java/ch/sumex/schadenflow/claim/ClaimRepository.java backend/src/main/java/ch/sumex/schadenflow/audit/ backend/src/test/java/ch/sumex/schadenflow/claim/ClaimPersistenceIT.java
git commit -m "feat(claim): add Flyway schema, JPA entities and repositories

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01YMfonK7B5XK8NccWNrMAoZ"
```

---

## Task 4: ClaimService (create + transition, atomic with audit)

**Files:**
- Create: `backend/src/main/java/ch/sumex/schadenflow/claim/ClaimService.java`
- Test: `backend/src/test/java/ch/sumex/schadenflow/claim/ClaimServiceTest.java`

**Interfaces:**
- Consumes: `ClaimRepository`, `AuditRepository`, `ClaimStateMachine`, `ClaimState`, `Role`, the entities, and `DomainException.*` from earlier tasks.
- Produces a `@Service ClaimService` with:
  - `Claim create(UUID claimantId, String title, String description, BigDecimal amount, UUID actorId, Role actorRole)` — persists a claim in `EINGEREICHT` and a creation audit row (`fromState=null`), returns the saved claim. (Bean Validation on the DTO guards inputs at the controller; the service trusts validated inputs but still sets timestamps + ids.)
  - `Claim transition(UUID claimId, ClaimState targetState, String reason, UUID actorId, Role actorRole)` — loads the claim (`NotFoundError`), calls `stateMachine.validateTransition(current, target, role)`, enforces reject-needs-reason (`ValidationError` when target is `ABGELEHNT` and reason blank), updates state + `updatedAt`, appends an audit row, returns the saved claim.
  - `Claim getById(UUID claimId)` — `NotFoundError` if absent.
  - `Page<Claim> list(ClaimState state, UUID claimantId, Pageable pageable)` — applies whichever filters are non-null.
  - `List<AuditEntry> getAudit(UUID claimId)` — `NotFoundError` if the claim does not exist, else the ordered entries.

- [ ] **Step 1: Write the failing test** `ClaimServiceTest.java` (Mockito mocks for repos + a real `ClaimStateMachine`)

```java
package ch.sumex.schadenflow.claim;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import ch.sumex.schadenflow.audit.AuditEntry;
import ch.sumex.schadenflow.audit.AuditRepository;
import ch.sumex.schadenflow.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

    @Mock ClaimRepository claimRepository;
    @Mock AuditRepository auditRepository;
    @Spy ClaimStateMachine stateMachine = new ClaimStateMachine();
    @InjectMocks ClaimService service;

    private Claim sampleClaim(ClaimState state) {
        return new Claim(UUID.randomUUID(), UUID.randomUUID(), "t", "d", null,
                new BigDecimal("10.00"), state, java.time.Instant.now(), java.time.Instant.now());
    }

    @Test
    void createPersistsClaimAndCreationAuditRow() {
        UUID claimant = UUID.randomUUID();
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        Claim result = service.create(claimant, "Broken arm", "Fell", new BigDecimal("250.00"),
                claimant, Role.ANSPRUCHSTELLER);

        assertThat(result.getState()).isEqualTo(ClaimState.EINGEREICHT);
        ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditRepository).save(audit.capture());
        assertThat(audit.getValue().getFromState()).isNull();
        assertThat(audit.getValue().getToState()).isEqualTo(ClaimState.EINGEREICHT);
        assertThat(audit.getValue().getActorId()).isEqualTo(claimant);
    }

    @Test
    void transitionUpdatesStateAndAppendsAudit() {
        Claim claim = sampleClaim(ClaimState.IN_PRUEFUNG);
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID actor = UUID.randomUUID();
        Claim result = service.transition(claim.getId(), ClaimState.GENEHMIGT, "looks valid",
                actor, Role.SACHBEARBEITER);

        assertThat(result.getState()).isEqualTo(ClaimState.GENEHMIGT);
        ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditRepository).save(audit.capture());
        assertThat(audit.getValue().getFromState()).isEqualTo(ClaimState.IN_PRUEFUNG);
        assertThat(audit.getValue().getToState()).isEqualTo(ClaimState.GENEHMIGT);
        assertThat(audit.getValue().getActorRole()).isEqualTo(Role.SACHBEARBEITER);
    }

    @Test
    void transitionOnMissingClaimThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(claimRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transition(id, ClaimState.IN_PRUEFUNG, null,
                UUID.randomUUID(), Role.SACHBEARBEITER))
                .isInstanceOf(DomainException.NotFoundError.class);
    }

    @Test
    void illegalTransitionThrowsAndWritesNoAudit() {
        Claim claim = sampleClaim(ClaimState.EINGEREICHT);
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        assertThatThrownBy(() -> service.transition(claim.getId(), ClaimState.AUSBEZAHLT, null,
                UUID.randomUUID(), Role.ADMIN))
                .isInstanceOf(DomainException.IllegalTransitionError.class);
        verify(auditRepository, never()).save(any());
    }

    @Test
    void disallowedRoleThrowsForbidden() {
        Claim claim = sampleClaim(ClaimState.GENEHMIGT);
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        assertThatThrownBy(() -> service.transition(claim.getId(), ClaimState.AUSBEZAHLT, null,
                UUID.randomUUID(), Role.SACHBEARBEITER))
                .isInstanceOf(DomainException.ForbiddenError.class);
    }

    @Test
    void rejectWithoutReasonThrowsValidation() {
        Claim claim = sampleClaim(ClaimState.IN_PRUEFUNG);
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        assertThatThrownBy(() -> service.transition(claim.getId(), ClaimState.ABGELEHNT, "  ",
                UUID.randomUUID(), Role.SACHBEARBEITER))
                .isInstanceOf(DomainException.ValidationError.class);
        verify(auditRepository, never()).save(any());
    }

    @Test
    void getByIdMissingThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(claimRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(DomainException.NotFoundError.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && mvn -q -Dtest=ClaimServiceTest test`
Expected: FAIL — `ClaimService` does not exist.

- [ ] **Step 3: Create `ClaimService.java`**

```java
package ch.sumex.schadenflow.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ch.sumex.schadenflow.audit.AuditEntry;
import ch.sumex.schadenflow.audit.AuditRepository;
import ch.sumex.schadenflow.shared.DomainException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClaimService {

    private final ClaimRepository claimRepository;
    private final AuditRepository auditRepository;
    private final ClaimStateMachine stateMachine;

    public ClaimService(ClaimRepository claimRepository, AuditRepository auditRepository,
                        ClaimStateMachine stateMachine) {
        this.claimRepository = claimRepository;
        this.auditRepository = auditRepository;
        this.stateMachine = stateMachine;
    }

    @Transactional
    public Claim create(UUID claimantId, String title, String description, BigDecimal amount,
                        UUID actorId, Role actorRole) {
        Instant now = Instant.now();
        Claim claim = new Claim(UUID.randomUUID(), claimantId, title, description, null, amount,
                ClaimState.EINGEREICHT, now, now);
        Claim saved = claimRepository.save(claim);
        auditRepository.save(new AuditEntry(UUID.randomUUID(), saved.getId(), null,
                ClaimState.EINGEREICHT, actorId, actorRole, null, now));
        return saved;
    }

    @Transactional
    public Claim transition(UUID claimId, ClaimState targetState, String reason,
                            UUID actorId, Role actorRole) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new DomainException.NotFoundError("Claim %s not found".formatted(claimId)));
        ClaimState from = claim.getState();
        stateMachine.validateTransition(from, targetState, actorRole);
        if (targetState == ClaimState.ABGELEHNT && (reason == null || reason.isBlank())) {
            throw new DomainException.ValidationError("A reason is required when rejecting a claim");
        }
        Instant now = Instant.now();
        claim.setState(targetState);
        claim.setUpdatedAt(now);
        Claim saved = claimRepository.save(claim);
        auditRepository.save(new AuditEntry(UUID.randomUUID(), saved.getId(), from, targetState,
                actorId, actorRole, reason, now));
        return saved;
    }

    @Transactional(readOnly = true)
    public Claim getById(UUID claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new DomainException.NotFoundError("Claim %s not found".formatted(claimId)));
    }

    @Transactional(readOnly = true)
    public Page<Claim> list(ClaimState state, UUID claimantId, Pageable pageable) {
        if (state != null && claimantId != null) {
            return claimRepository.findByStateAndClaimantId(state, claimantId, pageable);
        }
        if (state != null) {
            return claimRepository.findByState(state, pageable);
        }
        if (claimantId != null) {
            return claimRepository.findByClaimantId(claimantId, pageable);
        }
        return claimRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> getAudit(UUID claimId) {
        if (!claimRepository.existsById(claimId)) {
            throw new DomainException.NotFoundError("Claim %s not found".formatted(claimId));
        }
        return auditRepository.findByClaimIdOrderByTimestampAsc(claimId);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && mvn -q -Dtest=ClaimServiceTest test`
Expected: PASS — all 7 tests green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/ch/sumex/schadenflow/claim/ClaimService.java backend/src/test/java/ch/sumex/schadenflow/claim/ClaimServiceTest.java
git commit -m "feat(claim): add ClaimService with atomic create/transition + audit

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01YMfonK7B5XK8NccWNrMAoZ"
```

---

## Task 5: DTOs + ClaimController (REST endpoints + envelope + header binding)

**Files:**
- Create: `backend/src/main/java/ch/sumex/schadenflow/claim/dto/CreateClaimRequest.java`
- Create: `backend/src/main/java/ch/sumex/schadenflow/claim/dto/TransitionRequest.java`
- Create: `backend/src/main/java/ch/sumex/schadenflow/claim/dto/ClaimResponse.java`
- Create: `backend/src/main/java/ch/sumex/schadenflow/claim/dto/AuditEntryResponse.java`
- Create: `backend/src/main/java/ch/sumex/schadenflow/claim/ClaimController.java`
- Test: `backend/src/test/java/ch/sumex/schadenflow/claim/ClaimControllerTest.java`

**Interfaces:**
- Consumes: `ClaimService`, `Claim`, `AuditEntry`, `ClaimState`, `Role`, `ApiResponse` from earlier tasks.
- Produces REST endpoints (all responses wrapped in `ApiResponse`):
  - `POST /api/claims` (body `CreateClaimRequest`, headers `X-Actor-Id`, `X-Actor-Role`) → 201
  - `GET /api/claims?state=&claimantId=&page=&size=` → 200 (page payload)
  - `GET /api/claims/{id}` → 200
  - `POST /api/claims/{id}/transitions` (body `TransitionRequest`, headers) → 200
  - `GET /api/claims/{id}/audit` → 200
  - DTOs: `CreateClaimRequest(UUID claimantId, String title, String description, BigDecimal amount)` with Bean Validation; `TransitionRequest(ClaimState targetState, String reason)`; `ClaimResponse` + `AuditEntryResponse` with static `from(...)` mappers.

- [ ] **Step 1: Create `CreateClaimRequest.java`**

```java
package ch.sumex.schadenflow.claim.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateClaimRequest(
        @NotNull UUID claimantId,
        @NotBlank @Size(max = 200) String title,
        @NotBlank String description,
        @NotNull @PositiveOrZero BigDecimal amount
) {}
```

- [ ] **Step 2: Create `TransitionRequest.java`**

```java
package ch.sumex.schadenflow.claim.dto;

import ch.sumex.schadenflow.claim.ClaimState;
import jakarta.validation.constraints.NotNull;

public record TransitionRequest(
        @NotNull ClaimState targetState,
        String reason
) {}
```

- [ ] **Step 3: Create `ClaimResponse.java`**

```java
package ch.sumex.schadenflow.claim.dto;

import ch.sumex.schadenflow.claim.Claim;
import ch.sumex.schadenflow.claim.ClaimState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ClaimResponse(
        UUID id,
        UUID claimantId,
        String title,
        String description,
        String category,
        BigDecimal amount,
        ClaimState state,
        Instant createdAt,
        Instant updatedAt
) {
    public static ClaimResponse from(Claim c) {
        return new ClaimResponse(c.getId(), c.getClaimantId(), c.getTitle(), c.getDescription(),
                c.getCategory(), c.getAmount(), c.getState(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
```

- [ ] **Step 4: Create `AuditEntryResponse.java`**

```java
package ch.sumex.schadenflow.claim.dto;

import ch.sumex.schadenflow.audit.AuditEntry;
import ch.sumex.schadenflow.claim.ClaimState;
import ch.sumex.schadenflow.claim.Role;
import java.time.Instant;
import java.util.UUID;

public record AuditEntryResponse(
        UUID id,
        UUID claimId,
        ClaimState fromState,
        ClaimState toState,
        UUID actorId,
        Role actorRole,
        String reason,
        Instant timestamp
) {
    public static AuditEntryResponse from(AuditEntry a) {
        return new AuditEntryResponse(a.getId(), a.getClaimId(), a.getFromState(), a.getToState(),
                a.getActorId(), a.getActorRole(), a.getReason(), a.getTimestamp());
    }
}
```

- [ ] **Step 5: Write the failing test** `ClaimControllerTest.java` (`@WebMvcTest` with mocked `ClaimService`, importing the advice)

```java
package ch.sumex.schadenflow.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import ch.sumex.schadenflow.shared.DomainException;
import ch.sumex.schadenflow.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClaimController.class)
@Import(GlobalExceptionHandler.class)
class ClaimControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ClaimService service;

    private Claim sample() {
        return new Claim(UUID.randomUUID(), UUID.randomUUID(), "Broken arm", "Fell", null,
                new BigDecimal("250.00"), ClaimState.EINGEREICHT, Instant.now(), Instant.now());
    }

    @Test
    void createReturns201WithEnvelope() throws Exception {
        Claim c = sample();
        when(service.create(any(), any(), any(), any(), any(), any())).thenReturn(c);
        String body = """
            {"claimantId":"%s","title":"Broken arm","description":"Fell","amount":250.00}
            """.formatted(c.getClaimantId());
        mockMvc.perform(post("/api/claims")
                        .header("X-Actor-Id", UUID.randomUUID().toString())
                        .header("X-Actor-Role", "ANSPRUCHSTELLER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.state").value("EINGEREICHT"));
    }

    @Test
    void createWithMissingActorHeaderReturns400() throws Exception {
        String body = """
            {"claimantId":"%s","title":"Broken arm","description":"Fell","amount":250.00}
            """.formatted(UUID.randomUUID());
        mockMvc.perform(post("/api/claims")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createWithBlankTitleReturns400() throws Exception {
        String body = """
            {"claimantId":"%s","title":"","description":"Fell","amount":250.00}
            """.formatted(UUID.randomUUID());
        mockMvc.perform(post("/api/claims")
                        .header("X-Actor-Id", UUID.randomUUID().toString())
                        .header("X-Actor-Role", "ANSPRUCHSTELLER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void transitionReturns200() throws Exception {
        Claim c = sample();
        c.setState(ClaimState.IN_PRUEFUNG);
        when(service.transition(any(), any(), any(), any(), any())).thenReturn(c);
        mockMvc.perform(post("/api/claims/{id}/transitions", c.getId())
                        .header("X-Actor-Id", UUID.randomUUID().toString())
                        .header("X-Actor-Role", "SACHBEARBEITER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetState\":\"IN_PRUEFUNG\",\"reason\":\"start review\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("IN_PRUEFUNG"));
    }

    @Test
    void getByIdNotFoundReturns404Envelope() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getById(id)).thenThrow(new DomainException.NotFoundError("Claim %s not found".formatted(id)));
        mockMvc.perform(get("/api/claims/{id}", id).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `cd backend && mvn -q -Dtest=ClaimControllerTest test`
Expected: FAIL — `ClaimController` does not exist.

- [ ] **Step 7: Create `ClaimController.java`**

```java
package ch.sumex.schadenflow.claim;

import java.util.List;
import java.util.UUID;

import ch.sumex.schadenflow.claim.dto.AuditEntryResponse;
import ch.sumex.schadenflow.claim.dto.ClaimResponse;
import ch.sumex.schadenflow.claim.dto.CreateClaimRequest;
import ch.sumex.schadenflow.claim.dto.TransitionRequest;
import ch.sumex.schadenflow.shared.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/claims")
public class ClaimController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ClaimResponse>> create(
            @Valid @RequestBody CreateClaimRequest request,
            @RequestHeader("X-Actor-Id") UUID actorId,
            @RequestHeader("X-Actor-Role") Role actorRole) {
        Claim claim = service.create(request.claimantId(), request.title(), request.description(),
                request.amount(), actorId, actorRole);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(ClaimResponse.from(claim)));
    }

    @GetMapping
    public ApiResponse<Page<ClaimResponse>> list(
            @RequestParam(required = false) ClaimState state,
            @RequestParam(required = false) UUID claimantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        Page<ClaimResponse> result = service.list(state, claimantId, pageable).map(ClaimResponse::from);
        return ApiResponse.ok(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<ClaimResponse> getById(@PathVariable UUID id) {
        return ApiResponse.ok(ClaimResponse.from(service.getById(id)));
    }

    @PostMapping("/{id}/transitions")
    public ApiResponse<ClaimResponse> transition(
            @PathVariable UUID id,
            @Valid @RequestBody TransitionRequest request,
            @RequestHeader("X-Actor-Id") UUID actorId,
            @RequestHeader("X-Actor-Role") Role actorRole) {
        Claim claim = service.transition(id, request.targetState(), request.reason(), actorId, actorRole);
        return ApiResponse.ok(ClaimResponse.from(claim));
    }

    @GetMapping("/{id}/audit")
    public ApiResponse<List<AuditEntryResponse>> audit(@PathVariable UUID id) {
        List<AuditEntryResponse> entries = service.getAudit(id).stream()
                .map(AuditEntryResponse::from).toList();
        return ApiResponse.ok(entries);
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `cd backend && mvn -q -Dtest=ClaimControllerTest test`
Expected: PASS — all 5 tests green.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/ch/sumex/schadenflow/claim/dto/ backend/src/main/java/ch/sumex/schadenflow/claim/ClaimController.java backend/src/test/java/ch/sumex/schadenflow/claim/ClaimControllerTest.java
git commit -m "feat(claim): add DTOs and REST controller with envelope + header binding

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01YMfonK7B5XK8NccWNrMAoZ"
```

---

## Task 6: End-to-end flow integration test

**Files:**
- Test: `backend/src/test/java/ch/sumex/schadenflow/claim/ClaimFlowIT.java`

**Interfaces:**
- Consumes: the full wired application (controller → service → repos → Flyway/Postgres).
- Produces: a `@SpringBootTest(webEnvironment = RANDOM_PORT)` Testcontainers test driving the real HTTP API through a complete create → transition → audit lifecycle, asserting envelope shape, state changes, role enforcement, and the audit chain.

- [ ] **Step 1: Write the integration test** `ClaimFlowIT.java`

```java
package ch.sumex.schadenflow.claim;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class ClaimFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    private HttpHeaders headers(String role) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("X-Actor-Id", UUID.randomUUID().toString());
        h.add("X-Actor-Role", role);
        return h;
    }

    @Test
    void fullLifecycleCreateReviewApprovePayLeavesAuditTrail() {
        UUID claimant = UUID.randomUUID();
        String createBody = """
            {"claimantId":"%s","title":"Broken arm","description":"Fell off a bike","amount":250.00}
            """.formatted(claimant);

        // create
        ResponseEntity<String> created = rest.exchange("/api/claims", HttpMethod.POST,
                new HttpEntity<>(createBody, headers("ANSPRUCHSTELLER")), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).contains("\"ok\":true").contains("EINGEREICHT");
        String id = created.getBody().replaceAll(".*\"id\":\"([0-9a-f-]+)\".*", "$1");

        // claimant cannot move it to review -> 403
        ResponseEntity<String> forbidden = rest.exchange("/api/claims/" + id + "/transitions",
                HttpMethod.POST,
                new HttpEntity<>("{\"targetState\":\"IN_PRUEFUNG\"}", headers("ANSPRUCHSTELLER")),
                String.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // sachbearbeiter: submit -> review -> approve
        rest.exchange("/api/claims/" + id + "/transitions", HttpMethod.POST,
                new HttpEntity<>("{\"targetState\":\"IN_PRUEFUNG\",\"reason\":\"begin\"}", headers("SACHBEARBEITER")),
                String.class);
        rest.exchange("/api/claims/" + id + "/transitions", HttpMethod.POST,
                new HttpEntity<>("{\"targetState\":\"GENEHMIGT\",\"reason\":\"valid\"}", headers("SACHBEARBEITER")),
                String.class);

        // sachbearbeiter cannot pay out -> 403
        ResponseEntity<String> payForbidden = rest.exchange("/api/claims/" + id + "/transitions",
                HttpMethod.POST,
                new HttpEntity<>("{\"targetState\":\"AUSBEZAHLT\"}", headers("SACHBEARBEITER")),
                String.class);
        assertThat(payForbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // admin pays out
        ResponseEntity<String> paid = rest.exchange("/api/claims/" + id + "/transitions",
                HttpMethod.POST,
                new HttpEntity<>("{\"targetState\":\"AUSBEZAHLT\"}", headers("ADMIN")),
                String.class);
        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paid.getBody()).contains("AUSBEZAHLT");

        // audit trail: creation + 3 successful transitions = 4 rows
        ResponseEntity<String> audit = rest.getForEntity("/api/claims/" + id + "/audit", String.class);
        assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
        int rows = audit.getBody().split("\"toState\"").length - 1;
        assertThat(rows).isEqualTo(4);
    }
}
```

- [ ] **Step 2: Run the integration test**

Run: `cd backend && mvn -q -Dtest=ClaimFlowIT -DfailIfNoTests=false test` (or `mvn -q verify` to run it via Failsafe)
Expected: PASS — full lifecycle drives create→review→approve→pay, the two role violations return 403, and the audit endpoint returns 4 rows.

- [ ] **Step 3: Run the full suite to confirm everything is green together**

Run: `cd backend && mvn -q verify`
Expected: PASS — all unit tests + `GlobalExceptionHandlerTest`, `ClaimStateMachineTest`, `ClaimServiceTest`, `ClaimControllerTest`, and the ITs (`ApplicationContextIT`, `ClaimPersistenceIT`, `ClaimFlowIT`) green.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/ch/sumex/schadenflow/claim/ClaimFlowIT.java
git commit -m "test(claim): add end-to-end claim lifecycle integration test

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01YMfonK7B5XK8NccWNrMAoZ"
```

---

## Definition of done (sub-project 2)

- Flyway `V1`/`V2` create `claims` + `audit_entries`; `ddl-auto=validate`; entities validate against the migrated schema (proved by `ClaimPersistenceIT`).
- `ClaimStateMachine` enforces every legal edge and role rule, exhaustively unit-tested.
- `ClaimService` writes claim + audit atomically on create and transition; all error paths throw the right typed error.
- All five endpoints return the `ApiResponse` envelope; typed errors map to the documented HTTP codes via the single `@RestControllerAdvice`; request/header binding errors → 400, domain validation → 422.
- `ClaimFlowIT` proves a full create→review→approve→pay lifecycle with role enforcement and a 4-row audit trail on real Postgres.
- `mvn verify` green; no update/delete endpoints; audit append-only.
