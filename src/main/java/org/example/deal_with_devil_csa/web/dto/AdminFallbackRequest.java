package org.example.deal_with_devil_csa.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Backup admin sign-in for when Google sign-in is unavailable. */
public record AdminFallbackRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 200) String passcode)
{
}
