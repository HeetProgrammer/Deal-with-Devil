package org.example.deal_with_devil_csa.exception;

import org.springframework.http.HttpStatus;

/**
 * Every error the API can return has a code here. The frontend can switch on the code
 * (for example INSUFFICIENT_BALANCE) without parsing the message text.
 */
public enum ErrorCode
{
    // General
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Some fields are missing or invalid."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "The request body is missing or could not be read."),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "A request parameter is missing or has the wrong type."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "You need to sign in to do that."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You are not allowed to do that."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "This HTTP method is not supported here."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This content type is not supported."),
    CONFLICT(HttpStatus.CONFLICT, "That conflicts with existing data."),
    CONCURRENT_UPDATE(HttpStatus.CONFLICT, "That was updated at the same time by someone else. Please try again."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please slow down."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable. Please try again shortly."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side."),

    // Auth
    INVALID_GOOGLE_TOKEN(HttpStatus.UNAUTHORIZED, "The Google sign-in token is invalid."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "The email or passcode is incorrect."),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Your Google email is not verified."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Your session has expired. Please sign in again."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Your session is invalid. Please sign in again."),
    PROFILE_INCOMPLETE(HttpStatus.FORBIDDEN, "Please complete your profile before continuing."),

    // Domain
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found."),
    PROBLEM_NOT_FOUND(HttpStatus.NOT_FOUND, "Problem not found."),
    ROUND_NOT_FOUND(HttpStatus.NOT_FOUND, "Round not found."),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "The amount is not valid."),
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "Not enough coins."),
    BID_EXCEEDS_BALANCE(HttpStatus.CONFLICT, "Your bids add up to more coins than you have."),
    INVALID_PHASE(HttpStatus.CONFLICT, "That action is not allowed in the current phase."),
    BIDDING_CLOSED(HttpStatus.CONFLICT, "Bidding is closed for this round."),
    ALREADY_SETTLED(HttpStatus.CONFLICT, "This round has already been settled."),
    CORRUPTION_LIMIT_REACHED(HttpStatus.CONFLICT, "The corruption level cannot go any further in that direction.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage)
    {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus()
    {
        return status;
    }

    public String getDefaultMessage()
    {
        return defaultMessage;
    }
}
