package ch.sumex.schadenflow.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ch.sumex.schadenflow.audit.AuditEntry;
import ch.sumex.schadenflow.auth.AuthenticatedUser;
import ch.sumex.schadenflow.auth.JwtAuthenticationFilter;
import ch.sumex.schadenflow.auth.JwtService;
import ch.sumex.schadenflow.auth.RestAccessDeniedHandler;
import ch.sumex.schadenflow.auth.RestAuthenticationEntryPoint;
import ch.sumex.schadenflow.auth.SecurityConfig;
import ch.sumex.schadenflow.shared.DomainException;
import ch.sumex.schadenflow.shared.GlobalExceptionHandler;
import org.springframework.data.domain.PageImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClaimController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class ClaimControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ClaimService service;
    @MockitoBean JwtService jwtService;

    private Claim sample() {
        return new Claim(UUID.randomUUID(), UUID.randomUUID(), "Broken arm", "Fell", null,
                new BigDecimal("250.00"), ClaimState.EINGEREICHT, Instant.now(), Instant.now());
    }

    private static RequestPostProcessor asUser(UUID userId, Role role) {
        var principal = new AuthenticatedUser(userId, "u", role);
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    @Test
    void createReturns201WithEnvelope() throws Exception {
        Claim c = sample();
        when(service.create(any(), any(), any(), any(), any())).thenReturn(c);
        String body = """
            {"title":"Broken arm","description":"Fell","amount":250.00}
            """;
        mockMvc.perform(post("/api/claims")
                        .with(asUser(UUID.randomUUID(), Role.ANSPRUCHSTELLER))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.state").value("EINGEREICHT"));
    }

    @Test
    void noAuthenticationReturns401() throws Exception {
        mockMvc.perform(post("/api/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"X\",\"description\":\"Y\",\"amount\":1.00}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createWithBlankTitleReturns400() throws Exception {
        String body = """
            {"title":"","description":"Fell","amount":250.00}
            """;
        mockMvc.perform(post("/api/claims")
                        .with(asUser(UUID.randomUUID(), Role.ANSPRUCHSTELLER))
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
                        .with(asUser(UUID.randomUUID(), Role.SACHBEARBEITER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetState\":\"IN_PRUEFUNG\",\"reason\":\"start review\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("IN_PRUEFUNG"));
    }

    @Test
    void getByIdNotFoundReturns404Envelope() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getById(eq(id), any(), any()))
                .thenThrow(new DomainException.NotFoundError("Claim %s not found".formatted(id)));
        mockMvc.perform(get("/api/claims/{id}", id)
                        .with(asUser(UUID.randomUUID(), Role.SACHBEARBEITER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void listReturns200WithPagedEnvelope() throws Exception {
        Claim c = sample();
        when(service.list(any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(c)));
        mockMvc.perform(get("/api/claims")
                        .with(asUser(UUID.randomUUID(), Role.SACHBEARBEITER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.content[0].state").value("EINGEREICHT"));
    }

    @Test
    void auditReturns200WithEntries() throws Exception {
        UUID id = UUID.randomUUID();
        AuditEntry entry = new AuditEntry(UUID.randomUUID(), id, null, ClaimState.EINGEREICHT,
                UUID.randomUUID(), Role.ANSPRUCHSTELLER, null, Instant.now());
        when(service.getAudit(eq(id), any(), any())).thenReturn(List.of(entry));
        mockMvc.perform(get("/api/claims/{id}/audit", id)
                        .with(asUser(UUID.randomUUID(), Role.SACHBEARBEITER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data[0].toState").value("EINGEREICHT"))
                .andExpect(jsonPath("$.data[0].occurredAt").exists());
    }

    @Test
    void getByIdReturns200WithClaim() throws Exception {
        Claim c = sample();
        when(service.getById(eq(c.getId()), any(), any())).thenReturn(c);
        mockMvc.perform(get("/api/claims/{id}", c.getId())
                        .with(asUser(UUID.randomUUID(), Role.SACHBEARBEITER))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.state").value("EINGEREICHT"));
    }

    @Test
    void malformedJsonBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/claims")
                        .with(asUser(UUID.randomUUID(), Role.ANSPRUCHSTELLER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void amountOverflowReturns400() throws Exception {
        String body = """
            {"title":"Broken arm","description":"Fell","amount":99999999999.99}
            """;
        mockMvc.perform(post("/api/claims")
                        .with(asUser(UUID.randomUUID(), Role.ANSPRUCHSTELLER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
