package org.example.deal_with_devil_csa.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why the admin is undoing a ledger entry. Kept short so the final reversal reason still fits the 255-char column. */
public record UndoRequest(@NotBlank @Size(max = 200) String reason)
{
}
