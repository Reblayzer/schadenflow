package ch.sumex.schadenflow.claim;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.ActiveProfiles;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("dev")
class TriageFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"password123\"}";
        String res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(res).get("data").get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void reviewerTriagesThenConfirmsCategory() throws Exception {
        String claimant = login("anspruchsteller");
        String createBody = "{\"title\":\"Zahnarzt\",\"description\":\"Behandlung beim Zahnarzt am 2026-01-10.\",\"amount\":250.00}";
        String created = mockMvc.perform(post("/api/claims")
                        .header("Authorization", bearer(claimant))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String claimId = objectMapper.readTree(created).get("data").get("id").asText();

        String reviewer = login("sachbearbeiter");

        // Triage returns a suggestion and persists nothing.
        mockMvc.perform(post("/api/claims/{id}/triage", claimId)
                        .header("Authorization", bearer(reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.suggestedCategory").value("ZAHNARZT"))
                .andExpect(jsonPath("$.data.summary").isNotEmpty());

        // Category is still unset after triage (ApiResponse uses NON_NULL so null fields are omitted).
        mockMvc.perform(get("/api/claims/{id}", claimId).header("Authorization", bearer(reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").doesNotExist());

        // Confirm applies category + summary.
        String patchBody = "{\"category\":\"ZAHNARZT\",\"triageSummary\":\"Bestätigte Zahnbehandlung.\"}";
        mockMvc.perform(patch("/api/claims/{id}", claimId)
                        .header("Authorization", bearer(reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("ZAHNARZT"))
                .andExpect(jsonPath("$.data.triageSummary").value("Bestätigte Zahnbehandlung."));

        // 403 for a claimant trying to triage.
        mockMvc.perform(post("/api/claims/{id}/triage", claimId)
                        .header("Authorization", bearer(claimant)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
