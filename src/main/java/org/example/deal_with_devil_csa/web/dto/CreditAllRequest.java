package org.example.deal_with_devil_csa.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Admin button "credit everyone": the same number of coins for every participant, in one transaction.
 *
 * requestId is made up by the frontend each time the confirm popup is opened (for example a UUID). The server stores
 * it with the credit, so a double click, a retried request or a second tab sending the same id can never credit
 * twice: the repeat is refused with CONFLICT. reason is kept short because it is shown to every participant.
 */
public record CreditAllRequest(
        @NotNull @Min(1) @Max(1_000_000) Long amount,
        @NotBlank @Size(max = 200) String reason,
        @NotBlank @Size(min = 8, max = 100) String requestId)
{
}
