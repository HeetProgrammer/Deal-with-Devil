package org.example.deal_with_devil_csa.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

import org.example.deal_with_devil_csa.config.AuthProperties;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.springframework.stereotype.Component;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;

/**
 * Verifies the Google ID token the React app sends: signature (against Google's public keys),
 * audience (must be our client id), issuer, expiry, and that the email is verified.
 */
@Component
public class GoogleTokenVerifier
{
    private final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
    private final GoogleIdTokenVerifier verifier;

    public GoogleTokenVerifier(AuthProperties properties)
    {
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), jsonFactory)
                .setAudience(List.of(properties.googleClientId()))
                .build();
    }

    public GoogleIdentity verify(String idTokenString)
    {
        GoogleIdToken idToken;
        try
        {
            idToken = GoogleIdToken.parse(jsonFactory, idTokenString);
        }
        catch (IOException | IllegalArgumentException e)
        {
            throw new ApiException(ErrorCode.INVALID_GOOGLE_TOKEN, ErrorCode.INVALID_GOOGLE_TOKEN.getDefaultMessage(), e);
        }

        try
        {
            if (!verifier.verify(idToken))
            {
                throw new ApiException(ErrorCode.INVALID_GOOGLE_TOKEN);
            }
        }
        catch (GeneralSecurityException e)
        {
            throw new ApiException(ErrorCode.INVALID_GOOGLE_TOKEN, ErrorCode.INVALID_GOOGLE_TOKEN.getDefaultMessage(), e);
        }
        catch (IOException e)
        {
            // Could not fetch Google's public keys: our side or Google's, not the user's fault.
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE.getDefaultMessage(), e);
        }

        GoogleIdToken.Payload payload = idToken.getPayload();
        if (!Boolean.TRUE.equals(payload.getEmailVerified()) || payload.getEmail() == null)
        {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        return new GoogleIdentity(payload.getSubject(), payload.getEmail());
    }

    /** sub is Google's permanent user id. */
    public record GoogleIdentity(String sub, String email)
    {
    }
}
