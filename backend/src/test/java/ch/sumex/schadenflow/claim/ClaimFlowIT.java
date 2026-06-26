package ch.sumex.schadenflow.claim;

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
