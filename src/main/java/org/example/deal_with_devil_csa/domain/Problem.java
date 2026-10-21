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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A HackerRank problem in the problem bank. It joins a round by having its round set; a problem with
 * no round is just sitting in the bank. The problem is solved on HackerRank, so only the link is stored.
 * The three hint versions live in ProblemHint.
 */
@Entity
@Table(name = "problems")
@Getter
@Setter
@NoArgsConstructor
public class Problem
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, length = 60)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Tier tier;

    /** How many participants can win this problem (k in the Vickrey settlement). */
    @Column(nullable = false)
    private int slots = 1;

    @Column(nullable = false, length = 500)
    private String hackerRankLink;

    /** The reserve price: what winners pay when there are fewer than slots + 1 bids. Whole coins. */
    @Column(nullable = false)
    private long basePrice;

    /** Short hint teaser shown in the round preview. Optional. */
    @Column(length = 500)
    private String teaser;

    /**
     * The question preview participants read before bidding (for example the problem statement or a sample).
     * Optional. Set by the admin through its own endpoint; the HackerRank link itself is never shown to bidders.
     */
    @Column(length = 4000)
    private String preview;

    /** The round this problem is played in, or null while it is only in the bank. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "round_id")
    private Round round;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate()
    {
        if (createdAt == null)
        {
            createdAt = Instant.now();
        }
    }
}
