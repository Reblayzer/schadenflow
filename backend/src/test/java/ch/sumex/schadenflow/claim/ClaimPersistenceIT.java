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
