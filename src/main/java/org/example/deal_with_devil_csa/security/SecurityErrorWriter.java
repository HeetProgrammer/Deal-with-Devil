package org.example.deal_with_devil_csa.security;

import java.io.IOException;
import java.time.Instant;

import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes the standard error JSON straight to the response. Errors raised inside the security filter
 * chain never reach GlobalExceptionHandler, so the JWT filter, the 401 entry point and the 403 handler
 * all use this to return the same shape as the rest of the API.
 * The JSON is built by hand because every value is a fixed string, number or escaped text.
 */
@Component
public class SecurityErrorWriter
{
    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException
    {
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String json = "{\"timestamp\":\"" + Instant.now()
                + "\",\"status\":" + code.getStatus().value()
                + ",\"code\":\"" + code.name()
                + "\",\"message\":\"" + escape(code.getDefaultMessage())
                + "\",\"path\":\"" + escape(request.getRequestURI())
                + "\"}";
        response.getWriter().write(json);
    }

    private static String escape(String value)
    {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray())
        {
            switch (c)
            {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default ->
                {
                    if (c < 0x20)
                    {
                        sb.append(String.format("\\u%04x", (int) c));
                    }
                    else
                    {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
