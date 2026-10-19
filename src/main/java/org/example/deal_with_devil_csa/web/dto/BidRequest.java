package org.example.deal_with_devil_csa.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Place or change a sealed bid, in whole coins. The problem comes from the URL. */
public record BidRequest(@NotNull @Min(1) Long amount)
{
}
