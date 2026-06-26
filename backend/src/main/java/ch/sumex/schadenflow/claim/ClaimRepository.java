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
