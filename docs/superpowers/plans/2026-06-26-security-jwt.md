# Security (JWT Auth + DB Users/Roles) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add JWT authentication + DB-backed users/roles, and replace the temporary `X-Actor-*` headers with the verified JWT principal (deriving `claimantId` from the caller and adding resource-ownership authorization).

**Architecture:** A `user/` package (entity + repo, Flyway-managed table + dev seed) and an `auth/` package (login endpoint, `JwtService` via jjwt HS256, a `OncePerRequestFilter`, stateless `SecurityConfig`, REST 401/403 handlers). The existing `ClaimController`/`ClaimService` are refactored to read the authenticated principal instead of headers, with claimant-ownership checks layered on SP2's transition role rules.

**Tech Stack:** Java 21, Spring Boot 3.4.1, Spring Security, jjwt 0.12.6, BCrypt, Flyway, PostgreSQL, JUnit 5, Testcontainers.

## Global Constraints

- **Package root:** `ch.sumex.schadenflow`. Java 21 / Spring Boot 3.4.1 / Maven.
- **Existing types (use exactly):** `Role` enum is in package `ch.sumex.schadenflow.claim` (values `ANSPRUCHSTELLER`, `SACHBEARBEITER`, `ADMIN`). Typed errors are nested in `ch.sumex.schadenflow.shared.DomainException` (`NotFoundError`, `ValidationError`, `ForbiddenError`, `IllegalTransitionError`). The success/error envelope is `ch.sumex.schadenflow.shared.ApiResponse` with `ApiResponse.ok(data)` and `ApiResponse.error(code, message)` (nested `ErrorBody(code, message)`).
- **JWT:** jjwt 0.12.6, HS256, secret from env `SECURITY_JWT_SECRET` (dev default), `sub`=userId, claims `role` + `username`, expiry from `security.jwt.expiration-minutes` (default 60).
- **Auth error codes/status:** bad login → **401** `INVALID_CREDENTIALS`; missing/invalid/expired token on a protected route → **401** `UNAUTHORIZED`; authenticated-but-forbidden (role rule or ownership) → **403** `FORBIDDEN`. All in the standard envelope.
- **Public routes:** `POST /api/auth/login`, `GET /api/health`. Everything else authenticated. Stateless sessions, CSRF disabled.
- **Claimant identity:** `claimantId` = the authenticated user; drop it from the request body.
- **Seed:** synthetic/dev-only, three users (`anspruchsteller`/`sachbearbeiter`/`admin`), bcrypt hash of dev password `password123`, in a separate `classpath:db/seed` Flyway location.
- **Commits:** Conventional Commits. Stage specific files only (never `git add .`). End every message with:
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01YMfonK7B5XK8NccWNrMAoZ
  ```

## File Structure

```
backend/src/main/java/ch/sumex/schadenflow/
  user/
    User.java                       @Entity (users table)
    UserRepository.java             findByUsername(String)
  auth/
    JwtService.java                 issue(User) / parse(token) -> AuthenticatedUser
    AuthenticatedUser.java          record { UUID userId, String username, Role role }
    JwtAuthenticationFilter.java    OncePerRequestFilter
    SecurityConfig.java             SecurityFilterChain + PasswordEncoder + handlers
    RestAuthenticationEntryPoint.java   401 envelope
    RestAccessDeniedHandler.java        403 envelope
    AuthController.java             POST /api/auth/login
    AuthService.java                verify creds + issue token
    dto/LoginRequest.java           { username, password }
    dto/LoginResponse.java          { token, username, role, expiresAt }
backend/src/main/resources/
  db/migration/V3__create_users.sql
  db/seed/V100__seed_dev_users.sql
  application.properties            (+ flyway.locations, + security.jwt.*)
backend/src/main/java/ch/sumex/schadenflow/claim/
  ClaimController.java              (refactor: principal instead of headers)
  ClaimService.java                 (refactor: actor on read paths + ownership checks)
  dto/CreateClaimRequest.java       (drop claimantId)
```

---

## Task 1: jjwt dependency + JwtService + JWT config

**Files:**
- Modify: `backend/pom.xml` (add jjwt 0.12.6)
- Create: `backend/src/main/java/ch/sumex/schadenflow/auth/AuthenticatedUser.java`
- Create: `backend/src/main/java/ch/sumex/schadenflow/auth/JwtService.java`
- Modify: `backend/src/main/resources/application.properties` (jwt props)
- Test: `backend/src/test/java/ch/sumex/schadenflow/auth/JwtServiceTest.java`

**Interfaces:**
- Consumes: `ch.sumex.schadenflow.claim.Role`; `ch.sumex.schadenflow.user.User` is NOT needed here — `JwtService.issue` takes primitives.
- Produces:
  - `record AuthenticatedUser(UUID userId, String username, Role role)`.
  - `JwtService` with `String issue(UUID userId, String username, Role role)` and `AuthenticatedUser parse(String token)` (throws `io.jsonwebtoken.JwtException` on bad signature/expiry/malformed). Configured by `security.jwt.secret` + `security.jwt.expiration-minutes`.

- [ ] **Step 1: Add jjwt to `backend/pom.xml`** — inside `<dependencies>`:

```xml
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>0.12.6</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
```

- [ ] **Step 2: Create `auth/AuthenticatedUser.java`**

```java
package ch.sumex.schadenflow.auth;

import ch.sumex.schadenflow.claim.Role;
import java.util.UUID;

public record AuthenticatedUser(UUID userId, String username, Role role) {
}
```

- [ ] **Step 3: Write the failing test** `auth/JwtServiceTest.java`

```java
package ch.sumex.schadenflow.auth;

import java.util.UUID;

import ch.sumex.schadenflow.claim.Role;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-which-is-at-least-32-bytes-long-0123456789";

    private JwtService service(long minutes) {
        return new JwtService(SECRET, minutes);
    }

    @Test
    void issueThenParseRoundTrips() {
        UUID userId = UUID.randomUUID();
        JwtService jwt = service(60);
        String token = jwt.issue(userId, "alice", Role.SACHBEARBEITER);

        AuthenticatedUser parsed = jwt.parse(token);
        assertThat(parsed.userId()).isEqualTo(userId);
        assertThat(parsed.username()).isEqualTo("alice");
        assertThat(parsed.role()).isEqualTo(Role.SACHBEARBEITER);
    }

    @Test
    void parseRejectsTamperedToken() {
        JwtService jwt = service(60);
        String token = jwt.issue(UUID.randomUUID(), "alice", Role.ADMIN);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("a") ? "bb" : "aa");
        assertThatThrownBy(() -> jwt.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void parseRejectsTokenSignedWithDifferentSecret() {
        String token = new JwtService("another-secret-which-is-also-32-bytes-long-0123456789", 60)
                .issue(UUID.randomUUID(), "bob", Role.ADMIN);
        assertThatThrownBy(() -> service(60).parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void parseRejectsExpiredToken() {
        JwtService jwt = service(-1); // already expired
        String token = jwt.issue(UUID.randomUUID(), "carol", Role.ANSPRUCHSTELLER);
        assertThatThrownBy(() -> jwt.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void parseRejectsMalformedToken() {
        assertThatThrownBy(() -> service(60).parse("not-a-jwt")).isInstanceOf(JwtException.class);
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `cd backend && mvn -q -Dtest=JwtServiceTest test`
Expected: FAIL — `JwtService` does not exist.

- [ ] **Step 5: Create `auth/JwtService.java`** (jjwt 0.12 API)

```java
package ch.sumex.schadenflow.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import ch.sumex.schadenflow.claim.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(
            @Value("${security.jwt.secret}") String secret,
            @Value("${security.jwt.expiration-minutes}") long expirationMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    public String issue(UUID userId, String username, Role role) {
        Instant now = Instant.now();
        Instant exp = now.plus(expirationMinutes, ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    public AuthenticatedUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new AuthenticatedUser(
                UUID.fromString(claims.getSubject()),
                claims.get("username", String.class),
                Role.valueOf(claims.get("role", String.class)));
    }

    public long expirationMinutes() {
        return expirationMinutes;
    }
}
```

- [ ] **Step 6: Add JWT props to `application.properties`** — append:

```properties
security.jwt.secret=${SECURITY_JWT_SECRET:dev-only-insecure-secret-change-me-0123456789}
security.jwt.expiration-minutes=${SECURITY_JWT_EXPIRATION_MINUTES:60}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd backend && mvn -q -Dtest=JwtServiceTest test`
Expected: PASS — 5 tests green.

- [ ] **Step 8: Commit**

```bash
git add backend/pom.xml backend/src/main/java/ch/sumex/schadenflow/auth/AuthenticatedUser.java backend/src/main/java/ch/sumex/schadenflow/auth/JwtService.java backend/src/main/resources/application.properties backend/src/test/java/ch/sumex/schadenflow/auth/JwtServiceTest.java
git commit -m "feat(auth): add JwtService with HS256 issue/parse"
# (append trailer lines)
```

---

## Task 2: User entity + repository + Flyway table + dev seed

**Files:**
- Create: `user/User.java`, `user/UserRepository.java`
- Create: `backend/src/main/resources/db/migration/V3__create_users.sql`
- Create: `backend/src/main/resources/db/seed/V100__seed_dev_users.sql`
- Modify: `application.properties` (flyway.locations)
- Test: `user/UserPersistenceIT.java`

**Interfaces:**
- Consumes: `ch.sumex.schadenflow.claim.Role`.
- Produces: `User` entity (`UUID id`, `String username`, `String passwordHash`, `Role role`, `Instant createdAt`); `UserRepository extends JpaRepository<User, UUID>` with `Optional<User> findByUsername(String username)`. Three seeded users exist after migration.

- [ ] **Step 1: Create `V3__create_users.sql`**

```sql
CREATE TABLE users (
    id            UUID PRIMARY KEY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(30) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
```

- [ ] **Step 2: Generate bcrypt hashes for the seed, then create `db/seed/V100__seed_dev_users.sql`.**

First compute three bcrypt hashes of `password123` (any cost-10 bcrypt hash of that string works; each row may share or differ). Use a throwaway: `cd backend && mvn -q compile` then a quick Java/Spring shell is overkill — instead run this one-off with the project on the classpath after Task 1 compiled bcrypt is available via spring-security-crypto (transitively present). Simplest: use Python if available — `python3 -c "import bcrypt,sys; print(bcrypt.hashpw(b'password123', bcrypt.gensalt(rounds=10)).decode())"` three times. If `bcrypt` isn't installed, write a tiny JUnit-style main using `org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder` (available once Task 4's security starter is added) — but to avoid ordering coupling, prefer the Python route, or `htpasswd -bnBC 10 "" password123 | tr -d ':\n' | sed 's/$2y/$2a/'`.

Then create the file with the actual hashes substituted (replace the `$2a$...` placeholders with real values you generated):

```sql
-- Synthetic dev-only users. Password for all three is "password123". Do NOT use in production.
INSERT INTO users (id, username, password_hash, role, created_at) VALUES
  ('11111111-1111-1111-1111-111111111111', 'anspruchsteller', '$2a$10$REPLACE_WITH_REAL_HASH_1', 'ANSPRUCHSTELLER', now()),
  ('22222222-2222-2222-2222-222222222222', 'sachbearbeiter',  '$2a$10$REPLACE_WITH_REAL_HASH_2', 'SACHBEARBEITER',  now()),
  ('33333333-3333-3333-3333-333333333333', 'admin',           '$2a$10$REPLACE_WITH_REAL_HASH_3', 'ADMIN',           now());
```

Each hash MUST verify against `password123` (the Task-2 test asserts this with a real `BCryptPasswordEncoder`, so a wrong hash fails the build).

- [ ] **Step 3: Update `application.properties`** — change the flyway locations line to include the seed:

```properties
spring.flyway.locations=classpath:db/migration,classpath:db/seed
```

- [ ] **Step 4: Create `user/User.java`**

```java
package ch.sumex.schadenflow.user;

import ch.sumex.schadenflow.claim.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "username", nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected User() {
    }

    public User(String username, String passwordHash, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 5: Create `user/UserRepository.java`**

```java
package ch.sumex.schadenflow.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);
}
```

- [ ] **Step 6: Write the persistence/seed IT** `user/UserPersistenceIT.java`

```java
package ch.sumex.schadenflow.user;

import ch.sumex.schadenflow.claim.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class UserPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private UserRepository userRepository;

    @Test
    void seedUsersExistAndPasswordsVerify() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        var admin = userRepository.findByUsername("admin").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(encoder.matches("password123", admin.getPasswordHash())).isTrue();
        assertThat(encoder.matches("wrong", admin.getPasswordHash())).isFalse();

        assertThat(userRepository.findByUsername("anspruchsteller").orElseThrow().getRole())
                .isEqualTo(Role.ANSPRUCHSTELLER);
        assertThat(userRepository.findByUsername("sachbearbeiter").orElseThrow().getRole())
                .isEqualTo(Role.SACHBEARBEITER);
    }
}
```

> Important ordering: this task pulls in **only** `spring-security-crypto` (the
> `BCryptPasswordEncoder` class), NOT the full `spring-boot-starter-security`.
> `spring-security-crypto` does NOT activate Spring Security's auto-configured
> filter chain, so all existing SP2 tests stay green after this task. The full
> starter (which secures every route) is added in **Task 3** together with
> `SecurityConfig`, so security is never "on" without its rules.

- [ ] **Step 0a (do first): add `spring-security-crypto` to `backend/pom.xml`** so `BCryptPasswordEncoder` resolves without enabling the security filter chain:

```xml
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-crypto</artifactId>
        </dependency>
```

(Version is managed by the Spring Boot BOM — no `<version>`.)

- [ ] **Step 7: Run this task's IT in isolation**

Run: `cd backend && mvn -q -Dtest=UserPersistenceIT test`
Expected: PASS — seed users present, bcrypt verifies. (Requires Docker.)

- [ ] **Step 8: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/db/migration/V3__create_users.sql backend/src/main/resources/db/seed/V100__seed_dev_users.sql backend/src/main/resources/application.properties backend/src/main/java/ch/sumex/schadenflow/user backend/src/test/java/ch/sumex/schadenflow/user/UserPersistenceIT.java
git commit -m "feat(user): add User entity, repository, table and dev seed"
# (append trailer lines)
```

---

## Task 3: Security wiring — SecurityConfig, JWT filter, REST 401/403 handlers

**Files:**
- Create: `auth/JwtAuthenticationFilter.java`, `auth/SecurityConfig.java`, `auth/RestAuthenticationEntryPoint.java`, `auth/RestAccessDeniedHandler.java`
- Test: `auth/SecurityIntegrationIT.java`

**Interfaces:**
- Consumes: `JwtService`, `AuthenticatedUser`, `ApiResponse`.
- Produces: a stateless `SecurityFilterChain` (public: `POST /api/auth/login`, `GET /api/health`; all else authenticated), a `PasswordEncoder` bean (`BCryptPasswordEncoder`), and the JWT filter that sets a `UsernamePasswordAuthenticationToken` whose principal is the `AuthenticatedUser` and authority is `ROLE_<role>`. 401/403 returned as the envelope.

> ⚠️ **This task activates Spring Security.** Adding the starter secures every route; the `SecurityConfig` in this same task defines the rules, so security is never on without them. From this task until Task 5, the pre-existing SP2 `ClaimControllerTest` and `ClaimFlowIT` (which send `X-Actor-*` headers and no token) will fail under auth — this is an expected, bounded window. In Tasks 3 and 4 run only the new tests in isolation (`-Dtest=...`); the full `mvn verify` is restored to green in Task 5 (unit) and Task 6 (full ITs). State this in your report.

- [ ] **Step 0 (do first): add the security starter to `backend/pom.xml`**

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
```

(Versions managed by the Spring Boot BOM. `spring-security-crypto` from Task 2 is a transitive subset of the starter — leaving both declared is harmless, but you may drop the explicit `spring-security-crypto` entry now that the starter provides it.)

- [ ] **Step 1: Create `auth/RestAuthenticationEntryPoint.java`** (401 envelope for missing/invalid token)

```java
package ch.sumex.schadenflow.auth;

import java.io.IOException;

import ch.sumex.schadenflow.shared.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.error("UNAUTHORIZED", "Authentication required"));
    }
}
```

- [ ] **Step 2: Create `auth/RestAccessDeniedHandler.java`** (403 envelope for Spring-Security-level denials)

```java
package ch.sumex.schadenflow.auth;

import java.io.IOException;

import ch.sumex.schadenflow.shared.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException ex) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.error("FORBIDDEN", "Access denied"));
    }
}
```

- [ ] **Step 3: Create `auth/JwtAuthenticationFilter.java`**

```java
package ch.sumex.schadenflow.auth;

import java.io.IOException;
import java.util.List;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            try {
                AuthenticatedUser user = jwtService.parse(header.substring(BEARER.length()));
                var authority = new SimpleGrantedAuthority("ROLE_" + user.role().name());
                var auth = new UsernamePasswordAuthenticationToken(user, null, List.of(authority));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException ex) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
```

- [ ] **Step 4: Create `auth/SecurityConfig.java`**

```java
package ch.sumex.schadenflow.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final RestAuthenticationEntryPoint entryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter,
                          RestAuthenticationEntryPoint entryPoint,
                          RestAccessDeniedHandler accessDeniedHandler) {
        this.jwtFilter = jwtFilter;
        this.entryPoint = entryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **Step 5: Write the security integration test** `auth/SecurityIntegrationIT.java`

```java
package ch.sumex.schadenflow.auth;

import ch.sumex.schadenflow.claim.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SecurityIntegrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void protectedRouteWithoutTokenReturns401Envelope() throws Exception {
        mockMvc.perform(get("/api/claims"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void protectedRouteWithValidTokenSucceeds() throws Exception {
        String token = jwtService.issue(UUID.randomUUID(), "admin", Role.ADMIN);
        mockMvc.perform(get("/api/claims").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void protectedRouteWithGarbageTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/claims").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
```

- [ ] **Step 6: Run this task's IT**

Run: `cd backend && mvn -q -Dtest=SecurityIntegrationIT test`
Expected: PASS — health public; `/api/claims` 401 without token, 200 with a valid token, 401 with garbage. (Requires Docker.)

> Note: the pre-existing SP2 `ClaimControllerTest` (`@WebMvcTest`) and `ClaimFlowIT` still send `X-Actor-*` headers and now run under security — they will FAIL until Task 5/6 refactor them. That is expected; do not "fix" them in this task. Run only `-Dtest=SecurityIntegrationIT` here.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/ch/sumex/schadenflow/auth/JwtAuthenticationFilter.java backend/src/main/java/ch/sumex/schadenflow/auth/SecurityConfig.java backend/src/main/java/ch/sumex/schadenflow/auth/RestAuthenticationEntryPoint.java backend/src/main/java/ch/sumex/schadenflow/auth/RestAccessDeniedHandler.java backend/src/test/java/ch/sumex/schadenflow/auth/SecurityIntegrationIT.java
git commit -m "feat(auth): add stateless JWT security filter chain and REST 401/403 handlers"
# (append trailer lines)
```

---

## Task 4: Login endpoint (AuthController + AuthService + DTOs)

**Files:**
- Create: `auth/AuthService.java`, `auth/AuthController.java`, `auth/dto/LoginRequest.java`, `auth/dto/LoginResponse.java`
- Test: `auth/AuthServiceTest.java`

**Interfaces:**
- Consumes: `UserRepository`, `PasswordEncoder`, `JwtService`.
- Produces: `POST /api/auth/login` → `ApiResponse<LoginResponse>`; bad creds throw a 401-mapped error. `AuthService.login(username, password)` returns a `LoginResponse`.

- [ ] **Step 1: Add a 401 mapping for bad credentials.** Create `auth/InvalidCredentialsException.java`:

```java
package ch.sumex.schadenflow.auth;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid username or password");
    }
}
```

Then add a handler to `shared/GlobalExceptionHandler.java` (place it before the catch-all `Exception` handler):

```java
    @ExceptionHandler(ch.sumex.schadenflow.auth.InvalidCredentialsException.class)
    public ResponseEntity<ApiResponse<Object>> handleInvalidCredentials(
            ch.sumex.schadenflow.auth.InvalidCredentialsException ex) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", ex.getMessage());
    }
```

(Use the existing `build(HttpStatus, String, String)` helper and `ResponseEntity<ApiResponse<Object>>` return shape already used in that class — match the file's existing handler signatures exactly.)

- [ ] **Step 2: Create the DTOs.**

`auth/dto/LoginRequest.java`:
```java
package ch.sumex.schadenflow.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password) {
}
```

`auth/dto/LoginResponse.java`:
```java
package ch.sumex.schadenflow.auth.dto;

import ch.sumex.schadenflow.claim.Role;
import java.time.Instant;

public record LoginResponse(String token, String username, Role role, Instant expiresAt) {
}
```

- [ ] **Step 3: Write the failing test** `auth/AuthServiceTest.java`

```java
package ch.sumex.schadenflow.auth;

import ch.sumex.schadenflow.auth.dto.LoginResponse;
import ch.sumex.schadenflow.claim.Role;
import ch.sumex.schadenflow.user.User;
import ch.sumex.schadenflow.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final JwtService jwtService = new JwtService(
            "test-secret-which-is-at-least-32-bytes-long-0123456789", 60);
    private final AuthService authService = new AuthService(userRepository, encoder, jwtService);

    private User userWithPassword(String username, Role role, String rawPassword) {
        return new User(username, encoder.encode(rawPassword), role);
    }

    @Test
    void loginWithValidCredentialsReturnsToken() {
        when(userRepository.findByUsername("admin"))
                .thenReturn(Optional.of(userWithPassword("admin", Role.ADMIN, "password123")));

        LoginResponse response = authService.login("admin", "password123");

        assertThat(response.token()).isNotBlank();
        assertThat(response.username()).isEqualTo("admin");
        assertThat(response.role()).isEqualTo(Role.ADMIN);
        assertThat(jwtService.parse(response.token()).role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void loginWithWrongPasswordThrowsInvalidCredentials() {
        when(userRepository.findByUsername("admin"))
                .thenReturn(Optional.of(userWithPassword("admin", Role.ADMIN, "password123")));
        assertThatThrownBy(() -> authService.login("admin", "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginWithUnknownUserThrowsInvalidCredentials() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authService.login("ghost", "password123"))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `cd backend && mvn -q -Dtest=AuthServiceTest test`
Expected: FAIL — `AuthService` does not exist.

- [ ] **Step 5: Create `auth/AuthService.java`**

```java
package ch.sumex.schadenflow.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import ch.sumex.schadenflow.auth.dto.LoginResponse;
import ch.sumex.schadenflow.user.User;
import ch.sumex.schadenflow.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        String token = jwtService.issue(user.getId(), user.getUsername(), user.getRole());
        Instant expiresAt = Instant.now().plus(jwtService.expirationMinutes(), ChronoUnit.MINUTES);
        return new LoginResponse(token, user.getUsername(), user.getRole(), expiresAt);
    }
}
```

- [ ] **Step 6: Create `auth/AuthController.java`**

```java
package ch.sumex.schadenflow.auth;

import ch.sumex.schadenflow.auth.dto.LoginRequest;
import ch.sumex.schadenflow.auth.dto.LoginResponse;
import ch.sumex.schadenflow.shared.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.username(), request.password()));
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd backend && mvn -q -Dtest=AuthServiceTest test`
Expected: PASS — 3 tests green.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/ch/sumex/schadenflow/auth/InvalidCredentialsException.java backend/src/main/java/ch/sumex/schadenflow/shared/GlobalExceptionHandler.java backend/src/main/java/ch/sumex/schadenflow/auth/dto backend/src/main/java/ch/sumex/schadenflow/auth/AuthService.java backend/src/main/java/ch/sumex/schadenflow/auth/AuthController.java backend/src/test/java/ch/sumex/schadenflow/auth/AuthServiceTest.java
git commit -m "feat(auth): add login endpoint issuing JWT for valid credentials"
# (append trailer lines)
```

---

## Task 5: Refactor claims to use the principal + ownership authz

**Files:**
- Modify: `claim/dto/CreateClaimRequest.java` (drop `claimantId`)
- Modify: `claim/ClaimService.java` (actor on read paths + ownership checks; claimant=self on create)
- Modify: `claim/ClaimController.java` (principal instead of headers)
- Modify (tests): `claim/ClaimControllerTest.java`, `claim/ClaimServiceTest.java`
- Test: ownership cases added to `claim/ClaimServiceTest.java`

**Interfaces:**
- Consumes: `AuthenticatedUser` (via `@AuthenticationPrincipal`), `Role`.
- Produces the new `ClaimService` signatures:
  - `Claim create(String title, String description, BigDecimal amount, UUID actorId, Role actorRole)` (claimant = `actorId`).
  - `Claim getById(UUID claimId, UUID actorId, Role actorRole)`
  - `Page<Claim> list(ClaimState state, UUID claimantId, UUID actorId, Role actorRole, Pageable pageable)`
  - `Claim transition(UUID claimId, ClaimState targetState, String reason, UUID actorId, Role actorRole)` (now also ownership-checked)
  - `List<AuditEntry> getAudit(UUID claimId, UUID actorId, Role actorRole)`
  - Private helper `assertCanAccess(Claim claim, UUID actorId, Role actorRole)`: if `actorRole == Role.ANSPRUCHSTELLER && !claim.getClaimantId().equals(actorId)` → `throw new DomainException.ForbiddenError("You may only access your own claims")`.

- [ ] **Step 1: Drop `claimantId` from `CreateClaimRequest.java`**

```java
package ch.sumex.schadenflow.claim.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateClaimRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String description,
        @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal amount
) {}
```

- [ ] **Step 2: Update `ClaimServiceTest.java` to the new signatures + add ownership tests.** Read the existing file first; change every `create(claimantId, title, description, amount, actorId, actorRole)` call to `create(title, description, amount, actorId, actorRole)` (claimant becomes the actor), and update `getById`/`list`/`getAudit`/`transition` calls to pass `actorId, actorRole`. Then add these ownership tests (using the in-memory fakes / mocks the file already uses; `OWNER` is the claimant):

```java
    @Test
    void claimantCannotAccessAnotherUsersClaim() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Claim claim = service.create("Brille", "Neue Brille", new BigDecimal("250.00"),
                owner, Role.ANSPRUCHSTELLER);
        assertThatThrownBy(() -> service.getById(claim.getId(), stranger, Role.ANSPRUCHSTELLER))
                .isInstanceOf(DomainException.ForbiddenError.class);
    }

    @Test
    void caseworkerCanAccessAnyClaim() {
        UUID owner = UUID.randomUUID();
        Claim claim = service.create("Brille", "Neue Brille", new BigDecimal("250.00"),
                owner, Role.ANSPRUCHSTELLER);
        // no throw
        Claim seen = service.getById(claim.getId(), UUID.randomUUID(), Role.SACHBEARBEITER);
        assertThat(seen.getId()).isEqualTo(claim.getId());
    }

    @Test
    void listForClaimantIsForcedToOwnClaims() {
        UUID owner = UUID.randomUUID();
        service.create("A", "desc", new BigDecimal("10.00"), owner, Role.ANSPRUCHSTELLER);
        service.create("B", "desc", new BigDecimal("20.00"), UUID.randomUUID(), Role.ANSPRUCHSTELLER);
        // claimant passes someone else's id but only sees their own
        Page<Claim> page = service.list(null, UUID.randomUUID(), owner, Role.ANSPRUCHSTELLER,
                PageRequest.of(0, 20));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getClaimantId()).isEqualTo(owner);
    }
```

Run: `cd backend && mvn -q -Dtest=ClaimServiceTest test` → Expected: FAIL (signatures don't match / ownership not implemented).

- [ ] **Step 3: Refactor `ClaimService.java`.** Apply these changes (read the file; keep the transaction boundaries and audit logic intact):
  - `create`: drop the `claimantId` parameter; use `actorId` as the claimant.
  - `getById`, `getAudit`, `transition`: after loading the claim, call `assertCanAccess(claim, actorId, actorRole)`.
  - `list`: add `actorId, actorRole` params; if `actorRole == Role.ANSPRUCHSTELLER`, replace the `claimantId` argument passed to the repository with `actorId`.
  - Add the private helper. Reference implementation of the changed/added members:

```java
    @org.springframework.transaction.annotation.Transactional
    public Claim create(String title, String description, java.math.BigDecimal amount,
                        java.util.UUID actorId, ch.sumex.schadenflow.claim.Role actorRole) {
        Claim claim = new Claim(actorId, title, description, null, amount); // claimant = actor
        Claim saved = claimRepository.save(claim);
        auditRepository.save(new ch.sumex.schadenflow.audit.AuditEntry(
                saved.getId(), null, ClaimState.EINGEREICHT, actorId, actorRole, null, java.time.Instant.now()));
        return saved;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Claim getById(java.util.UUID claimId, java.util.UUID actorId, ch.sumex.schadenflow.claim.Role actorRole) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ch.sumex.schadenflow.shared.DomainException.NotFoundError("Claim not found: " + claimId));
        assertCanAccess(claim, actorId, actorRole);
        return claim;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Claim> list(ClaimState state, java.util.UUID claimantId,
            java.util.UUID actorId, ch.sumex.schadenflow.claim.Role actorRole,
            org.springframework.data.domain.Pageable pageable) {
        java.util.UUID effectiveClaimant =
                actorRole == ch.sumex.schadenflow.claim.Role.ANSPRUCHSTELLER ? actorId : claimantId;
        return claimRepository.findFiltered(state, effectiveClaimant, pageable);
    }

    private void assertCanAccess(Claim claim, java.util.UUID actorId, ch.sumex.schadenflow.claim.Role actorRole) {
        if (actorRole == ch.sumex.schadenflow.claim.Role.ANSPRUCHSTELLER
                && !claim.getClaimantId().equals(actorId)) {
            throw new ch.sumex.schadenflow.shared.DomainException.ForbiddenError(
                    "You may only access your own claims");
        }
    }
```

> Note: the `Claim` constructor in SP2 is `new Claim(claimantId, title, description, category, amount)` — pass `null` for category (the create API does not set category; matches SP2 behaviour). Verify the constructor arity by reading `Claim.java`; if category is not a constructor arg in the real code, use the real constructor. For `transition` and `getAudit`, load the claim (or reuse the existing load), call `assertCanAccess(...)` before mutating/returning, keeping all existing audit/transaction logic. The repository finder method name is `findFiltered(state, claimantId, pageable)` (verify in `ClaimRepository`).

Run: `cd backend && mvn -q -Dtest=ClaimServiceTest test` → Expected: PASS (all prior + 3 ownership tests).

- [ ] **Step 4: Refactor `ClaimController.java`** to read the principal instead of headers. Replace the two header params on `create` and `transition` with `@AuthenticationPrincipal AuthenticatedUser actor`, and thread `actor.userId()` / `actor.role()` into the service; add the principal to `getById`, `list`, `audit`:

```java
    @PostMapping
    public ResponseEntity<ApiResponse<ClaimResponse>> create(
            @Valid @RequestBody CreateClaimRequest request,
            @AuthenticationPrincipal AuthenticatedUser actor) {
        Claim claim = service.create(request.title(), request.description(), request.amount(),
                actor.userId(), actor.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(ClaimResponse.from(claim)));
    }

    @GetMapping
    public ApiResponse<Page<ClaimResponse>> list(
            @RequestParam(required = false) ClaimState state,
            @RequestParam(required = false) UUID claimantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUser actor) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        Page<ClaimResponse> result = service.list(state, claimantId, actor.userId(), actor.role(), pageable)
                .map(ClaimResponse::from);
        return ApiResponse.ok(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<ClaimResponse> getById(@PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser actor) {
        return ApiResponse.ok(ClaimResponse.from(service.getById(id, actor.userId(), actor.role())));
    }

    @PostMapping("/{id}/transitions")
    public ApiResponse<ClaimResponse> transition(
            @PathVariable UUID id,
            @Valid @RequestBody TransitionRequest request,
            @AuthenticationPrincipal AuthenticatedUser actor) {
        Claim claim = service.transition(id, request.targetState(), request.reason(),
                actor.userId(), actor.role());
        return ApiResponse.ok(ClaimResponse.from(claim));
    }

    @GetMapping("/{id}/audit")
    public ApiResponse<List<AuditEntryResponse>> audit(@PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser actor) {
        List<AuditEntryResponse> entries = service.getAudit(id, actor.userId(), actor.role()).stream()
                .map(AuditEntryResponse::from).toList();
        return ApiResponse.ok(entries);
    }
```

Add imports: `org.springframework.security.core.annotation.AuthenticationPrincipal` and `ch.sumex.schadenflow.auth.AuthenticatedUser`. Remove the now-unused `RequestHeader` import and the `Role` usage if no longer referenced.

- [ ] **Step 5: Update `ClaimControllerTest.java`** (`@WebMvcTest`). The controller now requires an authenticated principal and runs under Spring Security. Two required changes:
  1. Annotate each test request with a principal so `@AuthenticationPrincipal` resolves — use Spring Security's test support: add `.with(authentication(...))` via `SecurityMockMvcRequestPostProcessors`, or annotate the test methods with a custom principal. Simplest: build a `UsernamePasswordAuthenticationToken` whose principal is an `AuthenticatedUser` and pass it with `.with(authentication(token))` (import `org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication`). Add `@AutoConfigureMockMvc(addFilters = false)` is NOT used — keep filters so authz is realistic; instead supply the authentication.
  2. Remove the `X-Actor-Id`/`X-Actor-Role` headers from every request; remove `claimantId` from the create JSON body. Update the mocked `service.create(...)`/`transition(...)` stubs to the new signatures.

Replace the create-success test and add a principal helper; apply the same pattern to the other cases:

```java
    private static org.springframework.security.test.web.servlet.request.RequestPostProcessor asUser(
            java.util.UUID userId, ch.sumex.schadenflow.claim.Role role) {
        var principal = new ch.sumex.schadenflow.auth.AuthenticatedUser(userId, "u", role);
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, java.util.List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role.name())));
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(auth);
    }

    @Test
    void createReturns201() throws Exception {
        when(service.create(any(), any(), any(), any(), any())).thenReturn(sampleClaim());
        String body = "{\"title\":\"Brille\",\"description\":\"Neue Brille\",\"amount\":250.00}";
        mockMvc.perform(post("/api/claims")
                        .with(asUser(java.util.UUID.randomUUID(), ch.sumex.schadenflow.claim.Role.ANSPRUCHSTELLER))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ok").value(true));
    }
```

Apply `asUser(...)` to the list/get/transition/audit/error tests; adjust `when(...)` stubs to the new arg counts. The "missing actor header → 400" test is now obsolete (no headers) — replace it with "no authentication → 401" by performing a request WITHOUT `.with(asUser(...))` and expecting `status().isUnauthorized()`. Keep `@Import(GlobalExceptionHandler.class)` and ensure the test class has the security filter chain available (it does via `@WebMvcTest` + Spring Security on the classpath; import `SecurityConfig` if needed: `@Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})` and provide `@MockBean JwtService`).

Run: `cd backend && mvn -q -Dtest=ClaimControllerTest,ClaimServiceTest test` → Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/ch/sumex/schadenflow/claim/dto/CreateClaimRequest.java backend/src/main/java/ch/sumex/schadenflow/claim/ClaimService.java backend/src/main/java/ch/sumex/schadenflow/claim/ClaimController.java backend/src/test/java/ch/sumex/schadenflow/claim/ClaimControllerTest.java backend/src/test/java/ch/sumex/schadenflow/claim/ClaimServiceTest.java
git commit -m "refactor(claim): derive actor from JWT principal and enforce ownership"
# (append trailer lines)
```

---

## Task 6: End-to-end auth flow + full verify + config/docs

**Files:**
- Modify: `claim/ClaimFlowIT.java` (drive via login/token)
- Modify: `docker-compose.yml` (pass `SECURITY_JWT_SECRET`), `README.md` (auth docs)
- Test: `auth/AuthFlowIT.java` (login → use token end-to-end)

**Interfaces:**
- Consumes: the whole stack.
- Produces: a green full `mvn verify`.

- [ ] **Step 1: Refactor `ClaimFlowIT.java`** so every request authenticates with a real token. Read the file; replace the `X-Actor-*` headers with `Authorization: Bearer <token>` where the token is issued by logging in (POST `/api/auth/login` with a seeded user) or directly via the autowired `JwtService` for a seeded user id. Since claimant is now the authenticated user, obtain the claimant's id from the login response / seeded user. Concretely: log in as `anspruchsteller` (claimant), capture the token; create the claim with it; log in as `sachbearbeiter` for the IN_PRUEFUNG and GENEHMIGT transitions; log in as `admin` for AUSBEZAHLT. Assert the same 4-row audit chain and the two 403 role-violation paths (now: an ANSPRUCHSTELLER token attempting IN_PRUEFUNG → 403; a SACHBEARBEITER token attempting AUSBEZAHLT → 403). Use the seeded users' ids for the audit `actorId` assertions.

Helper to log in and return the token + userId (add to the IT):
```java
    private record Session(String token, java.util.UUID userId) {}

    private Session login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"password123\"}";
        String res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode data = objectMapper.readTree(res).get("data");
        String token = data.get("token").asText();
        java.util.UUID userId = jwtService.parse(token).userId();
        return new Session(token, userId);
    }
```
Use `.header("Authorization", "Bearer " + session.token())` on each claim request. Autowire `JwtService` and `ObjectMapper`.

- [ ] **Step 2: Create `auth/AuthFlowIT.java`** — a focused end-to-end login test:

```java
package ch.sumex.schadenflow.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginWithSeededUserReturnsToken() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }
}
```

- [ ] **Step 3: Pass the JWT secret through compose.** In `docker-compose.yml`, under the `backend` service `environment:`, add:

```yaml
      SECURITY_JWT_SECRET: ${SECURITY_JWT_SECRET:-dev-only-insecure-secret-change-me-0123456789}
```

- [ ] **Step 4: Document auth in `README.md`** — add a short "Authentication" subsection: login at `POST /api/auth/login` with a seeded dev user (`admin` / `sachbearbeiter` / `anspruchsteller`, password `password123`), then send `Authorization: Bearer <token>` on all other `/api/*` calls; note the users are synthetic/dev-only and the JWT secret comes from `SECURITY_JWT_SECRET`.

- [ ] **Step 5: Run the FULL suite**

Run: `cd backend && mvn -q verify`
Expected: PASS — all unit tests + all ITs (JwtService, UserPersistence, SecurityIntegration, AuthService, AuthFlow, ClaimFlow, ClaimPersistence, ApplicationContext) green on real Postgres. Confirm no SP2 test still references `X-Actor-*` or `claimantId` in the create body.

- [ ] **Step 6: Commit**

```bash
git add backend/src/test/java/ch/sumex/schadenflow/claim/ClaimFlowIT.java backend/src/test/java/ch/sumex/schadenflow/auth/AuthFlowIT.java docker-compose.yml README.md
git commit -m "test(auth): drive claim flow through login/JWT and add auth e2e + docs"
# (append trailer lines)
```

---

## Definition of done (sub-project 3)

- `V3` + dev seed create and populate `users`; the three seeded logins work; `mvn verify` green (unit + all Testcontainers ITs).
- `POST /api/auth/login` issues a verifiable HS256 token; bad creds → 401 `INVALID_CREDENTIALS`.
- Protected endpoints require a valid token (401 `UNAUTHORIZED` otherwise); the JWT role + identity drive SP2's transition role rules and the new ownership checks (403 `FORBIDDEN` on violation).
- `X-Actor-*` headers removed; `claimantId` derived from the principal (dropped from the request body); all SP2 behaviour preserved under authenticated tests.
- `docker compose up` healthy; README documents login + bearer usage.
