package org.example.deal_with_devil_csa.service;

import java.util.List;

import org.example.deal_with_devil_csa.domain.AuditLogEntry;
import org.example.deal_with_devil_csa.repository.AuditLogRepository;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.web.dto.AuditLogResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * The admin audit log. Entries are written by AdminAuditFilter for every state-changing /api/admin call and are
 * never edited or deleted. Each write runs in its own transaction, separate from the action it describes, so a
 * failed action is still logged (with its error status).
 */
@Service
@RequiredArgsConstructor
public class AuditService
{
    static final int MAX_PATH_LENGTH = 300;
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    /** Deliberately not role-restricted: the filter also logs refused calls made by participants (403). */
    @Transactional
    public void record(Long actorId, String method, String path, int status)
    {
        AuditLogEntry entry = new AuditLogEntry();
        entry.setActor(userRepository.getReferenceById(actorId));
        entry.setMethod(method);
        entry.setPath(path.length() > MAX_PATH_LENGTH ? path.substring(0, MAX_PATH_LENGTH) : path);
        entry.setStatus(status);
        auditLogRepository.save(entry);
    }

    /** Newest entries first, for one actor or (actorId null) for everyone. The limit defaults to 100, at most 500. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<AuditLogResponse> recent(Long actorId, Integer limit)
    {
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        PageRequest page = PageRequest.of(0, size);
        List<AuditLogEntry> entries = actorId == null
                ? auditLogRepository.findRecent(page)
                : auditLogRepository.findRecentByActor(actorId, page);
        return entries.stream().map(AuditLogResponse::from).toList();
    }
}
