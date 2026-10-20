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
 * One notification. A personal event has a user and is shown to that person only (as a pop-up and in the
 * notification list); an event with no user is a broadcast shown to everyone (notification list only).
 * The id only ever grows, so the frontend can ask for "everything after the last id I saw".
 */
@Entity
@Table(name = "game_events", indexes = @Index(name = "idx_event_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
public class GameEvent
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The recipient, or null for a broadcast. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private EventType type;

    /** Ready-to-display text. */
    @Column(nullable = false, updatable = false, length = 500)
    private String message;

    /** Signed coin change, when the event is about coins. */
    @Column(updatable = false)
    private Long amount;

    @Column(updatable = false)
    private Long balanceAfter;

    /** The participant's new corruption level, for devil deals and redemptions. */
    @Column(updatable = false)
    private Integer corruptionLevel;

    /** The problem a bid event is about. */
    @Column(updatable = false)
    private Long problemId;

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
