package org.example.deal_with_devil_csa.web.dto;

import java.time.Instant;

import org.example.deal_with_devil_csa.domain.Role;

/** Returned by login. profileComplete = false means the frontend must show the mandatory form next. */
public record AuthResponse(String token, Instant expiresAt, boolean profileComplete, Role role)
{
}
