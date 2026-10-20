package org.example.deal_with_devil_csa.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One line of the admin audit log: who called which /api/admin endpoint, and how it ended (HTTP status).
 * Append-only, like the coin ledger. The request body is deliberately NOT stored: hint passwords and
 * answers travel in bodies, and the coin details of wallet actions are already in the ledger.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
public class AuditLogEntry
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false, updatable = false)
    private User actor;

    /** POST, PUT, PATCH or DELETE. Read-only calls are not audited. */
    @Column(nullable = false, updatable = false, length = 10)
    private String method;

    /** The request path, for example /api/admin/rounds/current/settle. */
    @Column(nullable = false, updatable = false, length = 300)
    private String path;

    /** The HTTP status the call ended with. 2xx means it went through; 4xx/5xx means it was refused or failed. */
    @Column(nullable = false, updatable = false)
    private int status;

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
