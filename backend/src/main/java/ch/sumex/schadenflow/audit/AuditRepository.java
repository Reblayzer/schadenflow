package ch.sumex.schadenflow.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEntry, UUID> {
    List<AuditEntry> findByClaimIdOrderByOccurredAtAsc(UUID claimId);
}
