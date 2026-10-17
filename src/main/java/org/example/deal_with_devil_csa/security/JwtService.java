package org.example.deal_with_devil_csa.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.example.deal_with_devil_csa.config.AuthProperties;
import org.example.deal_with_devil_csa.domain.User;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and checks our own JWTs. The token carries only the user id and the user's token version;
 * the role is always read from the database, so a role change or a bumped tokenVersion takes effect immediately.
 */
@Service
public class JwtService
{
    private static final String TOKEN_VERSION_CLAIM = "tv";

    private final SecretKey key;
    private final Duration expiry;

    public JwtService(AuthProperties properties)
    {
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.expiry = Duration.ofHours(properties.jwtExpiryHours());
    }

    public IssuedToken issue(User user)
    {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expiry);
        String token = Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim(TOKEN_VERSION_CLAIM, user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /** Checks signature and expiry. Throws ApiException TOKEN_EXPIRED or TOKEN_INVALID. */
    public TokenData parse(String token)
    {
        try
        {
            var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            Object version = claims.get(TOKEN_VERSION_CLAIM);
            if (!(version instanceof Number number))
            {
                throw new ApiException(ErrorCode.TOKEN_INVALID);
            }
            return new TokenData(Long.parseLong(claims.getSubject()), number.intValue());
        }
        catch (ExpiredJwtException e)
        {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED);
        }
        catch (JwtException | IllegalArgumentException e)
        {
            throw new ApiException(ErrorCode.TOKEN_INVALID);
        }
    }

    public record IssuedToken(String token, Instant expiresAt)
    {
    }

    public record TokenData(long userId, int tokenVersion)
    {
    }
}
