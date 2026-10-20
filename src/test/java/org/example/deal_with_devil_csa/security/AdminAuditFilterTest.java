package org.example.deal_with_devil_csa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.example.deal_with_devil_csa.domain.Role;
import org.example.deal_with_devil_csa.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;

/** Unit tests of the audit filter: servlet mocks and a mocked AuditService, no Spring context. */
@ExtendWith(MockitoExtension.class)
class AdminAuditFilterTest
{
    private static final long ADMIN_ID = 7L;
    private static final String SETTLE = "/api/admin/rounds/current/settle";

    @Mock
    private AuditService auditService;

    private AdminAuditFilter filter;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp()
    {
        filter = new AdminAuditFilter(auditService);
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void clearSecurityContext()
    {
        SecurityContextHolder.clearContext();
    }

    // ---- what gets logged ----

    @Test
    void successfulAdminCallIsLoggedWithItsStatus() throws Exception
    {
        signIn(ADMIN_ID, Role.ADMIN);

        filter.doFilter(new MockHttpServletRequest("POST", SETTLE), response, respondWith(200));

        verify(auditService).record(ADMIN_ID, "POST", SETTLE, 200);
    }

    @Test
    void refusedAdminCallIsLoggedWithTheErrorStatus() throws Exception
    {
        signIn(ADMIN_ID, Role.ADMIN);

        filter.doFilter(new MockHttpServletRequest("POST", SETTLE), response, respondWith(409));

        verify(auditService).record(ADMIN_ID, "POST", SETTLE, 409);
    }

    @Test
    void everyStateChangingMethodIsLogged() throws Exception
    {
        signIn(ADMIN_ID, Role.ADMIN);

        for (String method : List.of("POST", "PUT", "PATCH", "DELETE"))
        {
            filter.doFilter(new MockHttpServletRequest(method, "/api/admin/problems/3"), new MockHttpServletResponse(),
                    respondWith(200));

            verify(auditService).record(ADMIN_ID, method, "/api/admin/problems/3", 200);
        }
    }

    @Test
    void aParticipantTryingAnAdminEndpointIsLoggedToo() throws Exception
    {
        signIn(42L, Role.PARTICIPANT);

        filter.doFilter(new MockHttpServletRequest("POST", SETTLE), response, respondWith(403));

        verify(auditService).record(42L, "POST", SETTLE, 403);
    }

    @Test
    void chainFailureIsLoggedAsServerErrorAndStillPropagates() throws Exception
    {
        signIn(ADMIN_ID, Role.ADMIN);
        FilterChain exploding = (req, res) ->
        {
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("POST", SETTLE), response, exploding))
                .isInstanceOf(ServletException.class);

        verify(auditService).record(ADMIN_ID, "POST", SETTLE, 500);
    }

    // ---- what is NOT logged ----

    @Test
    void readOnlyCallsAreNotLogged() throws Exception
    {
        signIn(ADMIN_ID, Role.ADMIN);

        for (String method : List.of("GET", "HEAD", "OPTIONS"))
        {
            filter.doFilter(new MockHttpServletRequest(method, "/api/admin/ledger"), new MockHttpServletResponse(),
                    respondWith(200));
        }

        verifyNoInteractions(auditService);
    }

    @Test
    void callsOutsideTheAdminAreaAreNotLogged() throws Exception
    {
        signIn(ADMIN_ID, Role.ADMIN);

        filter.doFilter(new MockHttpServletRequest("PUT", "/api/me/profile"), response, respondWith(200));
        filter.doFilter(new MockHttpServletRequest("POST", "/api/auth/refresh"), new MockHttpServletResponse(),
                respondWith(200));

        verifyNoInteractions(auditService);
    }

    @Test
    void anonymousCallsAreNotLoggedBecauseThereIsNoActor() throws Exception
    {
        filter.doFilter(new MockHttpServletRequest("POST", SETTLE), response, respondWith(401));

        verifyNoInteractions(auditService);
    }

    // ---- resilience ----

    @Test
    void aFailureWhileWritingTheLogNeverBreaksTheRequest() throws Exception
    {
        signIn(ADMIN_ID, Role.ADMIN);
        doThrow(new IllegalStateException("database down")).when(auditService)
                .record(anyLong(), anyString(), anyString(), anyInt());

        filter.doFilter(new MockHttpServletRequest("POST", SETTLE), response, respondWith(200));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ---- helpers ----

    private static void signIn(long userId, Role role)
    {
        CurrentUser principal = new CurrentUser(userId, "user" + userId + "@example.com", role, true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    /** A chain whose "controller" simply sets the given HTTP status. */
    private static FilterChain respondWith(int status)
    {
        return (req, res) -> ((HttpServletResponse) res).setStatus(status);
    }
}
