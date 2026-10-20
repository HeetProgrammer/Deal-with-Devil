package org.example.deal_with_devil_csa.web.dto;

import java.time.Instant;

import org.example.deal_with_devil_csa.domain.AuditLogEntry;
import org.example.deal_with_devil_csa.domain.User;

/** One line of the admin audit log. Needs the entry's actor loaded. */
public record AuditLogResponse(
        Long id,
        Long actorId,
        String actorName,
        String actorEmail,
        String method,
        String path,
        int status,
        Instant createdAt)
{
    public static AuditLogResponse from(AuditLogEntry entry)
    {
        User actor = entry.getActor();
        return new AuditLogResponse(entry.getId(), actor.getId(), actor.getName(), actor.getEmail(),
                entry.getMethod(), entry.getPath(), entry.getStatus(), entry.getCreatedAt());
    }
}
