package org.example.deal_with_devil_csa.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The mandatory first-login form. Also used later when a participant corrects their HackerRank ID. */
public record ProfileRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 50) @Pattern(regexp = "^\\S+$", message = "must not contain spaces") String hackerRankId)
{
}
