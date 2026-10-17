package org.example.deal_with_devil_csa.service;

import java.util.Locale;

import org.example.deal_with_devil_csa.config.AuthProperties;
import org.example.deal_with_devil_csa.domain.Role;
import org.example.deal_with_devil_csa.domain.User;
import org.example.deal_with_devil_csa.domain.Wallet;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.repository.WalletRepository;
import org.example.deal_with_devil_csa.security.GoogleTokenVerifier;
import org.example.deal_with_devil_csa.security.JwtService;
import org.example.deal_with_devil_csa.web.dto.AuthResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService
{
    private final GoogleTokenVerifier googleTokenVerifier;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final AuthProperties authProperties;

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

        User user = userRepository.findByGoogleId(identity.sub()).orElse(null);
        if (user == null)
        {
            user = new User();
            user.setEmail(email);
            user.setGoogleId(identity.sub());
            user.setRole(role);
            user = userRepository.save(user);
            walletRepository.save(new Wallet(user));
        }
        else if (user.getRole() != role)
        {
            // The allowlist is the source of truth for the admin role, in both directions.
            user.setRole(role);
        }

        JwtService.IssuedToken issued = jwtService.issue(user);
        return new AuthResponse(issued.token(), issued.expiresAt(), user.isProfileComplete(), user.getRole());
    }
}
