package org.example.deal_with_devil_csa.web.dto;

import jakarta.validation.constraints.NotNull;

/** Admin buttons "Devil's deal" and "Redemption": which participant to apply it to. */
public record DevilActionRequest(@NotNull Long userId)
{
}
