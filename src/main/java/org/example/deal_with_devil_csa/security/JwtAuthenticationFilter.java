package org.example.deal_with_devil_csa.security;

import java.io.IOException;
import java.util.List;

import org.example.deal_with_devil_csa.domain.Role;
import org.example.deal_with_devil_csa.domain.User;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Runs on every request. Reads "Authorization: Bearer ...", checks the JWT, loads the user from the database,
 * rejects a stale tokenVersion, and puts a CurrentUser into the security context.
 * Also blocks participants who have not finished the mandatory profile form (PROFILE_INCOMPLETE).
 *
 * Deliberately NOT a Spring bean: a Filter bean would also be registered as a plain servlet filter
 * and run outside the security chain. SecurityConfig creates it by hand instead.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter
{
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final SecurityErrorWriter errorWriter;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository,
                                   SecurityErrorWriter errorWriter)
    {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER))
        {
            // No token: continue anonymously. Protected URLs will get 401 from the entry point.
            chain.doFilter(request, response);
            return;
        }

        User user;
        try
        {
            JwtService.TokenData data = jwtService.parse(header.substring(BEARER.length()).trim());
            user = userRepository.findById(data.userId())
                    .filter(u -> u.getTokenVersion() == data.tokenVersion())
                    .orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID));
        }
        catch (ApiException e)
        {
            errorWriter.write(request, response, e.getCode());
            return;
        }
        catch (RuntimeException e)
        {
            // Not a problem with the token (for example the database is down): report it plainly and never as a
            // 401, so the frontend shows a message instead of signing the user out.
            log.error("Could not check the sign-in token for {} {}", request.getMethod(), request.getRequestURI(), e);
            errorWriter.write(request, response,
                    e instanceof DataAccessException || e instanceof CannotCreateTransactionException
                            ? ErrorCode.SERVICE_UNAVAILABLE
                            : ErrorCode.INTERNAL_ERROR);
            return;
        }

        if (user.getRole() == Role.PARTICIPANT && !user.isProfileComplete() && !isProfileExempt(request))
        {
            errorWriter.write(request, response, ErrorCode.PROFILE_INCOMPLETE);
            return;
        }

        CurrentUser principal = new CurrentUser(user.getId(), user.getEmail(), user.getRole(), user.isProfileComplete());
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        chain.doFilter(request, response);
    }

    /** The only URLs an incomplete participant may call: read themselves, submit the form, read the game config, sign in. */
    private boolean isProfileExempt(HttpServletRequest request)
    {
        String path = request.getRequestURI();
        return path.equals("/api/me") || path.equals("/api/me/profile") || path.equals("/api/config")
                || path.startsWith("/api/auth/");
    }
}
