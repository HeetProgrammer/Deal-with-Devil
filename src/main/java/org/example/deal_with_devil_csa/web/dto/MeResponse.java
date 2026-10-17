package org.example.deal_with_devil_csa.web.dto;

import org.example.deal_with_devil_csa.domain.Role;

public record MeResponse(
        Long id,
        String email,
        String name,
        String hackerRankId,
        Role role,
        int corruptionLevel,
        long coins,
        boolean profileComplete)
{
}
