package org.example.deal_with_devil_csa.web.dto;

import org.example.deal_with_devil_csa.domain.Tier;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create or fully replace a problem in the bank (admin).
 * basePrice is optional: when left out, the configured base price for the tier is used.
 * roundId is optional: leave it out to keep the problem in the bank only.
 */
public record ProblemRequest(
        @NotBlank @Size(max = 150) String title,
        @NotBlank @Size(max = 60) String category,
        @NotNull Tier tier,
        @NotNull @Min(1) @Max(100) Integer slots,
        @NotBlank @Size(max = 500) @Pattern(regexp = "^https?://\\S+$", message = "must be an http(s) link")
        String hackerRankLink,
        @Min(0) Long basePrice,
        @Size(max = 500) String teaser,
        Long roundId)
{
}
