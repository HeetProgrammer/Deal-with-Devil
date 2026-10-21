package org.example.deal_with_devil_csa.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One run of the "credit everyone" admin button. The only reason this row exists is the unique requestId: the
 * frontend makes up a new id each time the confirm popup opens, and the server inserts it before any coin moves.
 * A double click, a retried request or a second tab sending the same id therefore hits the unique constraint and is
 * refused, so the same button press can never credit everybody twice. The coins themselves are in the ledger (one
 * entry per participant); this row only records that the press happened.
 *
 * adminId is a plain number, not a foreign key, so the audit trail of a press survives whatever happens to the user.
 */
@Entity
@Table(name = "bulk_credits")
@Getter
@Setter
@NoArgsConstructor
public class BulkCredit
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, updatable = false, unique = true, length = 100)
    private String requestId;

    @Column(nullable = false, updatable = false)
    private Long adminId;

    /** Coins each participant received. */
    @Column(nullable = false, updatable = false)
    private long amountEach;

    /** How many participants were credited. */
    @Column(nullable = false, updatable = false)
    private int participants;

    @Column(nullable = false, updatable = false)
    private String reason;

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
