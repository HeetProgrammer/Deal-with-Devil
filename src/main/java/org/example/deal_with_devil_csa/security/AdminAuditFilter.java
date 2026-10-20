package org.example.deal_with_devil_csa.security;

import java.io.IOException;
import java.util.Set;

import org.example.deal_with_devil_csa.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes one audit log line for every state-changing call (anything but GET, HEAD and OPTIONS) to /api/admin/**,
 * with who called it, which endpoint and the HTTP status it ended with. Refused calls are logged too, including
 * attempts by signed-in participants (403), because those are exactly what an organiser wants to see.
 *
 * Sits right after JwtAuthenticationFilter so the signed-in user is known. Anonymous calls are skipped (there is
 * no actor to record; they get 401 anyway). The request body is never read or stored.
 * Logging can never break a request: if writing the entry fails, the error is logged and the request carries on.
 *
 * Like JwtAuthenticationFilter this is deliberately NOT a Spring bean; SecurityConfig creates it by hand.
 */
public class AdminAuditFilter extends OncePerRequestFilter
{
    private static final Logger log = LoggerFactory.getLogger(AdminAuditFilter.class);
    private static final String ADMIN_PREFIX = "/api/admin/";
    private static final Set<String> READ_ONLY_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final AuditService auditService;

    public AdminAuditFilter(AuditService auditService)
    {
        this.auditService = auditService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request)
    {
        return !request.getRequestURI().startsWith(ADMIN_PREFIX)
                || READ_ONLY_METHODS.contains(request.getMethod());   // HTTP methods are always upper case
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {
        CurrentUser actor = currentUser();   // read now: it is the caller's identity before anything else runs

        int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;   // what we log if the chain blows up
        try
        {
            chain.doFilter(request, response);
            status = response.getStatus();
        }
        finally
        {
            if (actor != null)
            {
                record(actor, request, status);
            }
        }
    }

    private void record(CurrentUser actor, HttpServletRequest request, int status)
    {
        try
        {
            auditService.record(actor.id(), request.getMethod(), request.getRequestURI(), status);
        }
        catch (RuntimeException e)
        {
            log.error("Could not write the audit log entry for {} {} by user {}",
                    request.getMethod(), request.getRequestURI(), actor.id(), e);
        }
    }

    private static CurrentUser currentUser()
    {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof CurrentUser user ? user : null;
    }
}
