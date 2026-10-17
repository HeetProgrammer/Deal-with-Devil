package org.example.deal_with_devil_csa.web.dto;

import java.time.Instant;
import java.util.Map;

import org.example.deal_with_devil_csa.exception.ErrorCode;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The one JSON shape every error leaves the API in. The frontend switches on {@code code}
 * (for example INSUFFICIENT_BALANCE) and shows {@code message}. {@code details} carries
 * per-field problems for validation errors and is left out when empty.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> details)
{
    public static ErrorResponse of(ErrorCode code, String message, String path, Map<String, String> details)
    {
        return new ErrorResponse(
                Instant.now(),
                code.getStatus().value(),
                code.name(),
                message,
                path,
                details == null ? Map.of() : details);
    }
}
