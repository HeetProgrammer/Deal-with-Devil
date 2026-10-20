package org.example.deal_with_devil_csa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.example.deal_with_devil_csa.domain.AuditLogEntry;
import org.example.deal_with_devil_csa.domain.User;
import org.example.deal_with_devil_csa.repository.AuditLogRepository;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.web.dto.AuditLogResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

/** Unit tests of the audit log service. Repositories are mocked. */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest
{
    private static final long ADMIN_ID = 7L;

    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private UserRepository userRepository;

    private AuditService service;
    private User admin;

    @BeforeEach
    void setUp()
    {
        service = new AuditService(auditLogRepository, userRepository);

        admin = new User();
        admin.setId(ADMIN_ID);
        admin.setName("Asha");
        admin.setEmail("asha@example.com");

        lenient().when(userRepository.getReferenceById(ADMIN_ID)).thenReturn(admin);
    }

    // ---- record ----

    @Test
    void recordStoresActorMethodPathAndStatus()
    {
        service.record(ADMIN_ID, "POST", "/api/admin/rounds/current/settle", 200);

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLogEntry entry = captor.getValue();
        assertThat(entry.getActor()).isSameAs(admin);
        assertThat(entry.getMethod()).isEqualTo("POST");
        assertThat(entry.getPath()).isEqualTo("/api/admin/rounds/current/settle");
        assertThat(entry.getStatus()).isEqualTo(200);
    }

    @Test
    void aVeryLongPathIsCutToWhatTheColumnHolds()
    {
        String longPath = "/api/admin/" + "x".repeat(1000);

        service.record(ADMIN_ID, "DELETE", longPath, 204);

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getPath()).hasSize(AuditService.MAX_PATH_LENGTH);
        assertThat(longPath).startsWith(captor.getValue().getPath());
    }

    // ---- recent ----

    @Test
    void recentForEveryoneDefaultsToTheLatestHundred()
    {
        AuditLogEntry entry = entry(1L, "PUT", "/api/admin/problems/3", 200);
        when(auditLogRepository.findRecent(PageRequest.of(0, 100))).thenReturn(List.of(entry));

        List<AuditLogResponse> result = service.recent(null, null);

        assertThat(result).hasSize(1);
        AuditLogResponse response = result.get(0);
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.actorId()).isEqualTo(ADMIN_ID);
        assertThat(response.actorName()).isEqualTo("Asha");
        assertThat(response.actorEmail()).isEqualTo("asha@example.com");
        assertThat(response.method()).isEqualTo("PUT");
        assertThat(response.path()).isEqualTo("/api/admin/problems/3");
        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void recentForOneActorUsesTheActorQuery()
    {
        AuditLogEntry entry = entry(2L, "POST", "/api/admin/rounds", 201);
        when(auditLogRepository.findRecentByActor(ADMIN_ID, PageRequest.of(0, 100))).thenReturn(List.of(entry));

        assertThat(service.recent(ADMIN_ID, null)).hasSize(1);
    }

    @Test
    void limitIsClampedBetweenOneAndFiveHundred()
    {
        when(auditLogRepository.findRecent(any(PageRequest.class))).thenReturn(List.of());

        service.recent(null, 100_000);
        service.recent(null, 0);
        service.recent(null, -5);

        verify(auditLogRepository).findRecent(PageRequest.of(0, 500));
        verify(auditLogRepository, org.mockito.Mockito.times(2)).findRecent(PageRequest.of(0, 1));
    }

    // ---- helpers ----

    private AuditLogEntry entry(Long id, String method, String path, int status)
    {
        AuditLogEntry entry = new AuditLogEntry();
        entry.setId(id);
        entry.setActor(admin);
        entry.setMethod(method);
        entry.setPath(path);
        entry.setStatus(status);
        entry.setCreatedAt(Instant.parse("2026-11-02T10:00:00Z"));
        return entry;
    }
}
