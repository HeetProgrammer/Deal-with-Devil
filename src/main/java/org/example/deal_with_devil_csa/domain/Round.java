package org.example.deal_with_devil_csa.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One round of the event. Only the latest round is ever "current". Its phase only moves forward
 * (see RoundPhase), and only the admin moves it. The @Version column makes two admins clicking
 * "advance" at the same moment fail with CONCURRENT_UPDATE instead of skipping a phase.
 */
@Entity
@Table(name = "rounds")
@Getter
@Setter
@NoArgsConstructor
public class Round
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 1, 2, 3, ... Unique, so two simultaneous "start round" calls cannot both create the same round. */
    @Column(name = "round_number", nullable = false, unique = true)
    private int number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoundPhase phase = RoundPhase.PREVIEW;

    /** While paused the phase cannot be advanced (and, from Phase 5, bids are not accepted). */
    @Column(nullable = false)
    private boolean paused = false;

    /**
     * Optional server-side bidding deadline. Null means no deadline: bidding then stays open until the admin
     * moves the phase on. Bids arriving at or after the deadline are rejected whatever the phase says.
     */
    private Instant biddingDeadline;

    @Version
    private long version;

    @Column(nullable = false)
    private Instant phaseChangedAt;

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
        if (phaseChangedAt == null)
        {
            phaseChangedAt = now;
        }
    }
}
