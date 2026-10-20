package org.example.deal_with_devil_csa.web.controller;

import java.util.List;

import org.example.deal_with_devil_csa.service.AuditService;
import org.example.deal_with_devil_csa.web.dto.AuditLogResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** Read-only view of the admin audit log. /api/admin/** is already restricted to ADMIN in SecurityConfig. */
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController
{
    private final AuditService auditService;

    /** Newest first. Optional ?actorId= for one person; ?limit= defaults to 100, at most 500. */
    @GetMapping
    public List<AuditLogResponse> recent(@RequestParam(required = false) Long actorId,
                                         @RequestParam(required = false) Integer limit)
    {
        return auditService.recent(actorId, limit);
    }
}
