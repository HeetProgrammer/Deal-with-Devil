package org.example.deal_with_devil_csa.web.dto;

import org.example.deal_with_devil_csa.domain.Tier;

import jakarta.validation.constraints.NotNull;

/** Admin button "solved Bronze / Silver / Gold": credits the configured solve reward for that tier. */
public record PresetCreditRequest(@NotNull Long userId, @NotNull Tier tier)
{
}
