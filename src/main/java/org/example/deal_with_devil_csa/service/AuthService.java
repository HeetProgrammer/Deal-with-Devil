package org.example.deal_with_devil_csa.service;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.example.deal_with_devil_csa.config.AuthProperties;
import org.example.deal_with_devil_csa.domain.Role;
import org.example.deal_with_devil_csa.domain.User;
import org.example.deal_with_devil_csa.domain.Wallet;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.repository.WalletRepository;
import org.example.deal_with_devil_csa.security.GoogleTokenVerifier;
import org.example.deal_with_devil_csa.security.JwtService;
import org.example.deal_with_devil_csa.web.dto.AuthResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService
{
    /** Marks a user created by the backup admin sign-in, who has not signed in with Google yet. */
    private static final String FALLBACK_GOOGLE_ID_PREFIX = "fallback:";
    private static final int MAX_FALLBACK_FAILURES = 5;
    private static final Duration FALLBACK_WINDOW = Duration.ofMinutes(5);
    private static final int MAX_TRACKED_EMAILS = 1000;

    private final GoogleTokenVerifier googleTokenVerifier;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final AuthProperties authProperties;

    /** Recent failed backup-sign-in attempts per email, to stop passcode guessing. Lives in memory only. */
    private final Map<String, Deque<Instant>> fallbackFailures = new ConcurrentHashMap<>();

    /**
     * Verifies the Google token, finds the user by Google id (creating the user and an empty wallet on first
     * sign-in), applies the admin allowlist, and issues our own JWT.
     */
    @Transactional
    public AuthResponse loginWithGoogle(String idToken)
    {
        GoogleTokenVerifier.GoogleIdentity identity = googleTokenVerifier.verify(idToken);
        String email = identity.email().toLowerCase(Locale.ROOT);
        Role role = authProperties.adminEmails().contains(email) ? Role.ADMIN : Role.PARTICIPANT;

        // An admin who first signed in through the backup route has a placeholder Google id: link it now.
        User user = userRepository.findByGoogleId(identity.sub())
                .or(() -> userRepository.findByEmail(email)
                        .filter(u -> u.getGoogleId().startsWith(FALLBACK_GOOGLE_ID_PREFIX)))
                .orElse(null);
        if (user == null)
        {
            user = createUser(email, identity.sub(), role);
        }
        else
        {
            if (user.getGoogleId().startsWith(FALLBACK_GOOGLE_ID_PREFIX))
            {
                user.setGoogleId(identity.sub());
            }
            if (user.getRole() != role)
            {
                // The allowlist is the source of truth for the admin role, in both directions.
                user.setRole(role);
            }
        }

        return toAuthResponse(user);
    }

    /**
     * Backup admin sign-in for when Google sign-in is unavailable: an allowlisted admin email plus the shared
     * passcode from the server configuration. Switched off (always rejects) when no passcode is configured.
     * Repeated failures for one email are temporarily blocked.
     */
    @Transactional
    public AuthResponse loginWithFallback(String rawEmail, String passcode)
    {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        requireNotLockedOut(email);

        String configured = authProperties.fallbackAdminPasscode();
        boolean valid = !configured.isEmpty()
                && authProperties.adminEmails().contains(email)
                && MessageDigest.isEqual(configured.getBytes(UTF_8), passcode.getBytes(UTF_8));
        if (!valid)
        {
            recordFailure(email);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        fallbackFailures.remove(email);

        User user = userRepository.findByEmail(email)
                .orElseGet(() -> createUser(email, FALLBACK_GOOGLE_ID_PREFIX + email, Role.ADMIN));
        if (user.getRole() != Role.ADMIN)
        {
            user.setRole(Role.ADMIN);
        }
        return toAuthResponse(user);
    }

    /** Issues a fresh token for an already signed-in user (the frontend calls this shortly before expiry). */
    @Transactional(readOnly = true)
    @PreAuthorize("@guard.isSelf(#userId)")
    public AuthResponse refresh(Long userId)
    {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID));
        return toAuthResponse(user);
    }

    // ---- Internals ----

    private User createUser(String email, String googleId, Role role)
    {
        User user = new User();
        user.setEmail(email);
        user.setGoogleId(googleId);
        user.setRole(role);
        user = userRepository.save(user);
        walletRepository.save(new Wallet(user));
        return user;
    }

    private AuthResponse toAuthResponse(User user)
    {
        JwtService.IssuedToken issued = jwtService.issue(user);
        return new AuthResponse(issued.token(), issued.expiresAt(), user.isProfileComplete(), user.getRole());
    }

    private void requireNotLockedOut(String email)
    {
        Deque<Instant> failures = fallbackFailures.get(email);
        if (failures == null)
        {
            return;
        }
        synchronized (failures)
        {
            Instant cutoff = Instant.now().minus(FALLBACK_WINDOW);
            while (!failures.isEmpty() && failures.peekFirst().isBefore(cutoff))
            {
                failures.pollFirst();
            }
            if (failures.size() >= MAX_FALLBACK_FAILURES)
            {
                throw new ApiException(ErrorCode.TOO_MANY_REQUESTS,
                        "Too many failed attempts. Try again in a few minutes.");
            }
        }
    }

    private void recordFailure(String email)
    {
        if (fallbackFailures.size() > MAX_TRACKED_EMAILS)
        {
            fallbackFailures.clear();
        }
        Deque<Instant> failures = fallbackFailures.computeIfAbsent(email, k -> new ArrayDeque<>());
        synchronized (failures)
        {
            failures.addLast(Instant.now());
        }
    }
}
