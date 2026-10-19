package org.example.deal_with_devil_csa.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Admin button "hint": deducts the generic hint price. The price is the same for every problem. */
public record HintDeductionRequest(@NotNull Long userId, @NotNull @Min(1) Integer problemNumber)
{
}
