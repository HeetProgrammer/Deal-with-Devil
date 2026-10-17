package org.example.deal_with_devil_csa.security;

import org.example.deal_with_devil_csa.domain.Role;

/**
 * Who is making the current request. Built from the database on every request
 * (not from the token), so the role and profile state are always up to date.
 */
public record CurrentUser(Long id, String email, Role role, boolean profileComplete)
{
}
