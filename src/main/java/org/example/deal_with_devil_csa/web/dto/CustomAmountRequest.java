package org.example.deal_with_devil_csa.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Admin custom amount. Positive credits the participant, negative deducts. Zero is rejected. A reason is required. */
public record CustomAmountRequest(
        @NotNull Long userId,
        @NotNull Long amount,
        @NotBlank @Size(max = 255) String reason)
{
}
