package ch.sumex.schadenflow.claim;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.sumex.schadenflow.auth.JwtService;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ClaimFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    private record Session(String token, UUID userId) {}

    private Session login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"password123\"}";
        String res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(res).get("data");
        String token = data.get("token").asText();
        UUID userId = jwtService.parse(token).userId();
        return new Session(token, userId);
    }

    @Test
    void fullLifecycleCreateReviewApprovePayLeavesAuditTrail() throws Exception {
        Session anspruchsteller = login("anspruchsteller");
        Session sachbearbeiter = login("sachbearbeiter");
        Session admin = login("admin");

        // create claim as anspruchsteller
        String createBody = """
                {"title":"Broken arm","description":"Fell off a bike","amount":250.00}
                """;
        String createResponse = mockMvc.perform(post("/api/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody)
                        .header("Authorization", "Bearer " + anspruchsteller.token()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(createResponse).contains("\"ok\":true").contains("EINGEREICHT");
        String id = objectMapper.readTree(createResponse).get("data").get("id").asText();

        // anspruchsteller cannot move it to review -> 403
        mockMvc.perform(post("/api/claims/" + id + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetState\":\"IN_PRUEFUNG\"}")
                        .header("Authorization", "Bearer " + anspruchsteller.token()))
                .andExpect(status().isForbidden());

        // sachbearbeiter: EINGEREICHT -> IN_PRUEFUNG
        String inPruefungResponse = mockMvc.perform(post("/api/claims/" + id + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetState\":\"IN_PRUEFUNG\",\"reason\":\"begin\"}")
                        .header("Authorization", "Bearer " + sachbearbeiter.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(inPruefungResponse).get("data").get("state").asText())
                .isEqualTo("IN_PRUEFUNG");

        // sachbearbeiter: IN_PRUEFUNG -> GENEHMIGT
        String genehmigtResponse = mockMvc.perform(post("/api/claims/" + id + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetState\":\"GENEHMIGT\",\"reason\":\"valid\"}")
                        .header("Authorization", "Bearer " + sachbearbeiter.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(genehmigtResponse).get("data").get("state").asText())
                .isEqualTo("GENEHMIGT");

        // sachbearbeiter cannot pay out -> 403
        mockMvc.perform(post("/api/claims/" + id + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetState\":\"AUSBEZAHLT\"}")
                        .header("Authorization", "Bearer " + sachbearbeiter.token()))
                .andExpect(status().isForbidden());

        // admin: GENEHMIGT -> AUSBEZAHLT
        String paidResponse = mockMvc.perform(post("/api/claims/" + id + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetState\":\"AUSBEZAHLT\"}")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(paidResponse).get("data").get("state").asText())
                .isEqualTo("AUSBEZAHLT");

        // audit trail: creation + 3 successful transitions = 4 rows
        String auditResponse = mockMvc.perform(get("/api/claims/" + id + "/audit")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode auditData = objectMapper.readTree(auditResponse).get("data");
        assertThat(auditData).hasSize(4);

        // row[0]: initial creation — no fromState
        assertThat(auditData.get(0).get("fromState").isNull()).isTrue();
        assertThat(auditData.get(0).get("toState").asText()).isEqualTo("EINGEREICHT");
        assertThat(auditData.get(0).get("actorRole").asText()).isEqualTo("ANSPRUCHSTELLER");
        assertThat(auditData.get(0).get("actorId").asText()).isEqualTo(anspruchsteller.userId().toString());
        assertThat(auditData.get(0).get("occurredAt").isNull()).isFalse();

        // row[1]: EINGEREICHT -> IN_PRUEFUNG
        assertThat(auditData.get(1).get("fromState").asText()).isEqualTo("EINGEREICHT");
        assertThat(auditData.get(1).get("toState").asText()).isEqualTo("IN_PRUEFUNG");
        assertThat(auditData.get(1).get("actorRole").asText()).isEqualTo("SACHBEARBEITER");
        assertThat(auditData.get(1).get("actorId").asText()).isEqualTo(sachbearbeiter.userId().toString());
        assertThat(auditData.get(1).get("occurredAt").isNull()).isFalse();

        // row[2]: IN_PRUEFUNG -> GENEHMIGT
        assertThat(auditData.get(2).get("fromState").asText()).isEqualTo("IN_PRUEFUNG");
        assertThat(auditData.get(2).get("toState").asText()).isEqualTo("GENEHMIGT");
        assertThat(auditData.get(2).get("actorRole").asText()).isEqualTo("SACHBEARBEITER");
        assertThat(auditData.get(2).get("actorId").asText()).isEqualTo(sachbearbeiter.userId().toString());
        assertThat(auditData.get(2).get("occurredAt").isNull()).isFalse();

        // row[3]: GENEHMIGT -> AUSBEZAHLT
        assertThat(auditData.get(3).get("fromState").asText()).isEqualTo("GENEHMIGT");
        assertThat(auditData.get(3).get("toState").asText()).isEqualTo("AUSBEZAHLT");
        assertThat(auditData.get(3).get("actorRole").asText()).isEqualTo("ADMIN");
        assertThat(auditData.get(3).get("actorId").asText()).isEqualTo(admin.userId().toString());
        assertThat(auditData.get(3).get("occurredAt").isNull()).isFalse();
    }
}
