package ch.sumex.schadenflow.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "claims")
public class Claim {

    @Id
    private UUID id;

    @Column(name = "claimant_id", nullable = false)
    private UUID claimantId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String description;

    @Column
    private String category;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimState state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Claim() { }

    public Claim(UUID id, UUID claimantId, String title, String description,
                 String category, BigDecimal amount, ClaimState state,
                 Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.claimantId = claimantId;
        this.title = title;
        this.description = description;
        this.category = category;
        this.amount = amount;
        this.state = state;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public UUID getClaimantId() { return claimantId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public BigDecimal getAmount() { return amount; }
    public ClaimState getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setCategory(String category) { this.category = category; }
    public void setState(ClaimState state) { this.state = state; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
