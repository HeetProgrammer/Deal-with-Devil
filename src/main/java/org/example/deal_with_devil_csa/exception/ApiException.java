package org.example.deal_with_devil_csa.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for every error we throw on purpose. The global handler turns it into
 * a clean JSON response using the status from the ErrorCode.
 */
public class ApiException extends RuntimeException
{
    private final ErrorCode code;
    private final Map<String, String> details;

    public ApiException(ErrorCode code)
    {
        this(code, code.getDefaultMessage(), null, null);
    }

    public ApiException(ErrorCode code, String message)
    {
        this(code, message, null, null);
    }

    public ApiException(ErrorCode code, String message, Map<String, String> details)
    {
        this(code, message, details, null);
    }

    public ApiException(ErrorCode code, String message, Throwable cause)
    {
        this(code, message, null, cause);
    }

    public ApiException(ErrorCode code, String message, Map<String, String> details, Throwable cause)
    {
        super(message, cause);
        this.code = code;
        this.details = details == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public ErrorCode getCode()
    {
        return code;
    }

    public Map<String, String> getDetails()
    {
        return details;
    }
}
