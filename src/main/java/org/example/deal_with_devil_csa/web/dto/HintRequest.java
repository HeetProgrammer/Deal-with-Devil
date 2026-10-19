package org.example.deal_with_devil_csa.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One hint version (for one corruption level) of a problem, with the password the admin gives out. */
public record HintRequest(
        @NotBlank @Size(max = 2000) String hintText,
        @NotBlank @Size(max = 100) String password)
{
}
