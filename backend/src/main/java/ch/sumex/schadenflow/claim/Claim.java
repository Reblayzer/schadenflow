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

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(length = 100)
    private Category category;

    @Column(name = "triage_summary", length = 2000)
    private String triageSummary;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClaimState state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Claim() { }

    public Claim(UUID id, UUID claimantId, String title, String description,
                 Category category, BigDecimal amount, ClaimState state,
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
    public Category getCategory() { return category; }
    public String getTriageSummary() { return triageSummary; }
    public BigDecimal getAmount() { return amount; }
    public ClaimState getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setCategory(Category category) { this.category = category; }
    public void setTriageSummary(String triageSummary) { this.triageSummary = triageSummary; }
    public void setState(ClaimState state) { this.state = state; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
