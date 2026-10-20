package org.example.deal_with_devil_csa.web.dto;

import java.time.Instant;

import org.example.deal_with_devil_csa.domain.EventType;
import org.example.deal_with_devil_csa.domain.GameEvent;

/**
 * One notification. broadcast = true means it is for everyone (notification list only); false means it is
 * personal and the frontend shows a pop-up. Safe to build outside a transaction (only plain columns are read).
 */
public record EventResponse(
        Long id,
        EventType type,
        boolean broadcast,
        String message,
        Long amount,
        Long balanceAfter,
        Integer corruptionLevel,
        Long problemId,
        Instant createdAt)
{
    public static EventResponse from(GameEvent event)
    {
        return new EventResponse(event.getId(), event.getType(), event.getUser() == null, event.getMessage(),
                event.getAmount(), event.getBalanceAfter(), event.getCorruptionLevel(), event.getProblemId(),
                event.getCreatedAt());
    }
}
