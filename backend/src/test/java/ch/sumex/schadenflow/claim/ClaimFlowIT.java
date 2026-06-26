package ch.sumex.schadenflow.claim;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Autowired
    private ObjectMapper objectMapper;

    private HttpHeaders headers(String role) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("X-Actor-Id", UUID.randomUUID().toString());
        h.add("X-Actor-Role", role);
        return h;
    }

    @Test
    void fullLifecycleCreateReviewApprovePayLeavesAuditTrail() throws Exception {
        UUID claimant = UUID.randomUUID();
        String createBody = """
            {"claimantId":"%s","title":"Broken arm","description":"Fell off a bike","amount":250.00}
            """.formatted(claimant);

        // create
        ResponseEntity<String> created = rest.exchange("/api/claims", HttpMethod.POST,
                new HttpEntity<>(createBody, headers("ANSPRUCHSTELLER")), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).contains("\"ok\":true").contains("EINGEREICHT");
        String id = objectMapper.readTree(created.getBody()).get("data").get("id").asText();

        // claimant cannot move it to review -> 403
        ResponseEntity<String> forbidden = rest.exchange("/api/claims/" + id + "/transitions",
                HttpMethod.POST,
                new HttpEntity<>("{\"targetState\":\"IN_PRUEFUNG\"}", headers("ANSPRUCHSTELLER")),
                String.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // sachbearbeiter: EINGEREICHT -> IN_PRUEFUNG
        ResponseEntity<String> inPruefung = rest.exchange("/api/claims/" + id + "/transitions",
                HttpMethod.POST,
                new HttpEntity<>("{\"targetState\":\"IN_PRUEFUNG\",\"reason\":\"begin\"}", headers("SACHBEARBEITER")),
                String.class);
        assertThat(inPruefung.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(inPruefung.getBody()).get("data").get("state").asText())
                .isEqualTo("IN_PRUEFUNG");

        // sachbearbeiter: IN_PRUEFUNG -> GENEHMIGT
        ResponseEntity<String> genehmigt = rest.exchange("/api/claims/" + id + "/transitions",
                HttpMethod.POST,
                new HttpEntity<>("{\"targetState\":\"GENEHMIGT\",\"reason\":\"valid\"}", headers("SACHBEARBEITER")),
                String.class);
        assertThat(genehmigt.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(genehmigt.getBody()).get("data").get("state").asText())
                .isEqualTo("GENEHMIGT");

        // sachbearbeiter cannot pay out -> 403
        ResponseEntity<String> payForbidden = rest.exchange("/api/claims/" + id + "/transitions",
                HttpMethod.POST,
                new HttpEntity<>("{\"targetState\":\"AUSBEZAHLT\"}", headers("SACHBEARBEITER")),
                String.class);
        assertThat(payForbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // admin: GENEHMIGT -> AUSBEZAHLT
        ResponseEntity<String> paid = rest.exchange("/api/claims/" + id + "/transitions",
                HttpMethod.POST,
                new HttpEntity<>("{\"targetState\":\"AUSBEZAHLT\"}", headers("ADMIN")),
                String.class);
        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(paid.getBody()).get("data").get("state").asText())
                .isEqualTo("AUSBEZAHLT");

        // audit trail: creation + 3 successful transitions = 4 rows
        ResponseEntity<String> audit = rest.getForEntity("/api/claims/" + id + "/audit", String.class);
        assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode auditData = objectMapper.readTree(audit.getBody()).get("data");
        assertThat(auditData).hasSize(4);

        // row[0]: initial creation — no fromState
        assertThat(auditData.get(0).get("fromState").isNull()).isTrue();
        assertThat(auditData.get(0).get("toState").asText()).isEqualTo("EINGEREICHT");
        assertThat(auditData.get(0).get("actorRole").asText()).isEqualTo("ANSPRUCHSTELLER");
        assertThat(auditData.get(0).get("occurredAt").isNull()).isFalse();

        // row[1]: EINGEREICHT -> IN_PRUEFUNG
        assertThat(auditData.get(1).get("fromState").asText()).isEqualTo("EINGEREICHT");
        assertThat(auditData.get(1).get("toState").asText()).isEqualTo("IN_PRUEFUNG");
        assertThat(auditData.get(1).get("actorRole").asText()).isEqualTo("SACHBEARBEITER");
        assertThat(auditData.get(1).get("occurredAt").isNull()).isFalse();

        // row[2]: IN_PRUEFUNG -> GENEHMIGT
        assertThat(auditData.get(2).get("fromState").asText()).isEqualTo("IN_PRUEFUNG");
        assertThat(auditData.get(2).get("toState").asText()).isEqualTo("GENEHMIGT");
        assertThat(auditData.get(2).get("actorRole").asText()).isEqualTo("SACHBEARBEITER");
        assertThat(auditData.get(2).get("occurredAt").isNull()).isFalse();

        // row[3]: GENEHMIGT -> AUSBEZAHLT
        assertThat(auditData.get(3).get("fromState").asText()).isEqualTo("GENEHMIGT");
        assertThat(auditData.get(3).get("toState").asText()).isEqualTo("AUSBEZAHLT");
        assertThat(auditData.get(3).get("actorRole").asText()).isEqualTo("ADMIN");
        assertThat(auditData.get(3).get("occurredAt").isNull()).isFalse();
    }
}
