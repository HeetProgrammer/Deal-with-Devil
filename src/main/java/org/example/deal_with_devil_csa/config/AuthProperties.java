package org.example.deal_with_devil_csa.config;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Everything auth needs, bound from "app.auth.*".
 * jwtSecret must be at least 32 characters (256 bits) for HS256.
 * adminEmails is lower-cased and trimmed on load so comparisons are reliable.
 * fallbackAdminPasscode switches on the backup admin sign-in (used if Google sign-in is down); blank means it is off.
 */
@Validated
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        @NotBlank String googleClientId,
        @NotBlank @Size(min = 32) String jwtSecret,
        @Min(1) @Max(12) int jwtExpiryHours,
        List<String> adminEmails,
        String fallbackAdminPasscode)
{
    public AuthProperties
    {
        adminEmails = adminEmails == null
                ? List.of()
                : adminEmails.stream()
                        .map(e -> e.trim().toLowerCase(Locale.ROOT))
                        .filter(e -> !e.isEmpty())
                        .toList();
        fallbackAdminPasscode = fallbackAdminPasscode == null ? "" : fallbackAdminPasscode.trim();
    }
}
