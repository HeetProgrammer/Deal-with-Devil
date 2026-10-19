package org.example.deal_with_devil_csa.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One participant's sealed bid on one problem. There is at most one per participant per problem (unique
 * constraint); changing the bid edits this row. Nobody but the bidder ever sees the amount before settlement.
 *
 * submittedAt is the tie-break clock: when two bids are equal, the earlier submittedAt wins. It is set when
 * the bid is first placed and moved forward again only when the amount actually changes, so re-saving the
 * same amount cannot be used to jump the queue, and raising a bid to match a rival does not beat the rival.
 */
@Entity
@Table(name = "bids",
        uniqueConstraints = @UniqueConstraint(name = "uk_bid_user_problem", columnNames = {"user_id", "problem_id"}),
        indexes = @Index(name = "idx_bid_problem", columnList = "problem_id"))
@Getter
@Setter
@NoArgsConstructor
public class Bid
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "problem_id", nullable = false, updatable = false)
    private Problem problem;

    /** Whole coins, at least 1. */
    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private Instant submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BidStatus status = BidStatus.PENDING;

    /** What the winner paid (the Vickrey clearing price, not their own bid). Null until the bid is WON. */
    private Long pricePaid;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate()
    {
        Instant now = Instant.now();
        if (createdAt == null)
        {
            createdAt = now;
        }
        if (submittedAt == null)
        {
            submittedAt = now;
        }
    }
}
