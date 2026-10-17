package org.example.deal_with_devil_csa.web.dto;

import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(@NotBlank String idToken)
{
}
