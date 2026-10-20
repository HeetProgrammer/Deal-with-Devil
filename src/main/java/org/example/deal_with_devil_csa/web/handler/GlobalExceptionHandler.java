package org.example.deal_with_devil_csa.web.handler;

import java.util.LinkedHashMap;
import java.util.Map;

import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.web.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/**
 * Turns every exception into the standard {@link ErrorResponse}. Our own errors come in as
 * {@link ApiException}; everything else is mapped to the closest ErrorCode, and anything
 * unexpected becomes INTERNAL_ERROR with the real cause logged but never sent to the client.
 *
 * Note: errors thrown inside the Spring Security filter chain (bad or missing JWT) never reach
 * this class. They are handled by the AuthenticationEntryPoint and AccessDeniedHandler added in Phase 2.
 */
@RestControllerAdvice
public class GlobalExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---- Our own errors ----

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex, HttpServletRequest request)
    {
        ErrorCode code = ex.getCode();
        if (code.getStatus().is5xxServerError())
        {
            log.error("ApiException {} on {}", code, request.getRequestURI(), ex);
        }
        else
        {
            log.debug("ApiException {} on {}: {}", code, request.getRequestURI(), ex.getMessage());
        }
        return build(code, ex.getMessage(), request, ex.getDetails());
    }

    // ---- Validation ----

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest request)
    {
        Map<String, String> details = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> details.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> details.putIfAbsent(ge.getObjectName(), ge.getDefaultMessage()));
        return build(ErrorCode.VALIDATION_FAILED, null, request, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request)
    {
        Map<String, String> details = new LinkedHashMap<>();
        ex.getConstraintViolations()
                .forEach(v -> details.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        return build(ErrorCode.VALIDATION_FAILED, null, request, details);
    }

    // ---- Bad requests ----

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest request)
    {
        log.debug("Unreadable body on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(ErrorCode.MALFORMED_REQUEST, null, request, null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex,
                                                            HttpServletRequest request)
    {
        return build(ErrorCode.INVALID_PARAMETER, null, request,
                Map.of(ex.getParameterName(), "This parameter is required."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request)
    {
        String expected = ex.getRequiredType() == null ? "valid value" : ex.getRequiredType().getSimpleName();
        return build(ErrorCode.INVALID_PARAMETER, null, request,
                Map.of(ex.getName(), "Must be a valid " + expected + "."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                                HttpServletRequest request)
    {
        return build(ErrorCode.METHOD_NOT_ALLOWED, null, request, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex,
                                                         HttpServletRequest request)
    {
        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null, request, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest request)
    {
        return build(ErrorCode.NOT_FOUND, null, request, null);
    }

    // ---- Security (method-level; filter-chain errors are handled separately) ----

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex,
                                                              HttpServletRequest request)
    {
        return build(ErrorCode.UNAUTHORIZED, null, request, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request)
    {
        return build(ErrorCode.FORBIDDEN, null, request, null);
    }

    // ---- Database ----

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                              HttpServletRequest request)
    {
        log.warn("Optimistic lock failure on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(ErrorCode.CONCURRENT_UPDATE, null, request, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex,
                                                             HttpServletRequest request)
    {
        // Full cause goes to the log only; constraint names must not leak to the client.
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(ErrorCode.CONFLICT, null, request, null);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    public ResponseEntity<ErrorResponse> handleDatabaseUnavailable(Exception ex, HttpServletRequest request)
    {
        // The database cannot be reached: tell the user to retry shortly. Never a 401, so nobody is signed out.
        log.error("Database unavailable on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorCode.SERVICE_UNAVAILABLE, null, request, null);
    }

    // ---- Last resort ----

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request)
    {
        log.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, null, request, null);
    }

    // ---- Helper ----

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message, HttpServletRequest request,
                                                Map<String, String> details)
    {
        String text = (message == null || message.isBlank()) ? code.getDefaultMessage() : message;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, text, request.getRequestURI(), details));
    }
}
