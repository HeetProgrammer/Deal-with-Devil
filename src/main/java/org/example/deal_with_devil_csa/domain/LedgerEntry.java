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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Append-only record of every coin movement. Entries are never updated or deleted;
 * an undo is a new REVERSAL entry that points back at the original via reversalOf.
 * Amount is signed: positive adds coins, negative removes them.
 */
@Entity
@Table(name = "ledger_entries", indexes = @Index(name = "idx_ledger_user_created", columnList = "user_id, createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class LedgerEntry
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private LedgerType type;

    /** Signed coin change. */
    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(nullable = false, updatable = false)
    private long balanceAfter;

    @Column(nullable = false, updatable = false)
    private String reason;

    /** The admin who did it. Null for system actions such as settlement. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", updatable = false)
    private User actor;

    /** Set only on REVERSAL entries: the id of the entry being undone. */
    @Column(updatable = false)
    private Long reversalOf;

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
