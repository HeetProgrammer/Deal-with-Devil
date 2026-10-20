package org.example.deal_with_devil_csa.security;

import org.example.deal_with_devil_csa.domain.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The ownership and role checks used by @PreAuthorize on the service functions, for example
 * {@code @PreAuthorize("@guard.isSelf(#userId)")}. They read the signed-in user from the security context,
 * which the JWT filter fills from the database on every request, so the role is never taken from the token.
 *
 * Every check answers false when nobody is signed in or the id is missing, so a missing value can never grant access.
 */
@Component("guard")
public class AccessGuard
{
    /** True if the signed-in user is the user with this id (ownership check). */
    public boolean isSelf(Long userId)
    {
        CurrentUser user = current();
        return user != null && userId != null && userId.equals(user.id());
    }

    /** True if the signed-in user is an admin. */
    public boolean isAdmin()
    {
        CurrentUser user = current();
        return user != null && user.role() == Role.ADMIN;
    }

    /** True for the user with this id, or for any admin. */
    public boolean isSelfOrAdmin(Long userId)
    {
        return isAdmin() || isSelf(userId);
    }

    private static CurrentUser current()
    {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof CurrentUser user ? user : null;
    }
}
