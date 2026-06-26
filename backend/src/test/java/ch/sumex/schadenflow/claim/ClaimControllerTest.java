package ch.sumex.schadenflow.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ch.sumex.schadenflow.audit.AuditEntry;
import ch.sumex.schadenflow.shared.DomainException;
import ch.sumex.schadenflow.shared.GlobalExceptionHandler;
import org.springframework.data.domain.PageImpl;
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

    @Test
    void listReturns200WithPagedEnvelope() throws Exception {
        Claim c = sample();
        when(service.list(any(), any(), any())).thenReturn(new PageImpl<>(List.of(c)));
        mockMvc.perform(get("/api/claims").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.content[0].state").value("EINGEREICHT"));
    }

    @Test
    void auditReturns200WithEntries() throws Exception {
        UUID id = UUID.randomUUID();
        AuditEntry entry = new AuditEntry(UUID.randomUUID(), id, null, ClaimState.EINGEREICHT,
                UUID.randomUUID(), Role.ANSPRUCHSTELLER, null, Instant.now());
        when(service.getAudit(id)).thenReturn(List.of(entry));
        mockMvc.perform(get("/api/claims/{id}/audit", id).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data[0].toState").value("EINGEREICHT"))
                .andExpect(jsonPath("$.data[0].occurredAt").exists());
    }

    @Test
    void getByIdReturns200WithClaim() throws Exception {
        Claim c = sample();
        when(service.getById(c.getId())).thenReturn(c);
        mockMvc.perform(get("/api/claims/{id}", c.getId()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.state").value("EINGEREICHT"));
    }

    @Test
    void malformedJsonBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/claims")
                        .header("X-Actor-Id", UUID.randomUUID().toString())
                        .header("X-Actor-Role", "ANSPRUCHSTELLER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void amountOverflowReturns400() throws Exception {
        String body = """
            {"claimantId":"%s","title":"Broken arm","description":"Fell","amount":99999999999.99}
            """.formatted(UUID.randomUUID());
        mockMvc.perform(post("/api/claims")
                        .header("X-Actor-Id", UUID.randomUUID().toString())
                        .header("X-Actor-Role", "ANSPRUCHSTELLER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
