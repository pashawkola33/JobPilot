package com.jobpilot.miniapp.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.miniapp.api.MiniAppApiError;
import com.jobpilot.identity.IdentityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The v1 gate: feature flag, signed Telegram launch, allow-list, and explicit legacy owner.
 *
 * <p>These controllers still operate on the original global workflow. Account-only beta
 * identities use the separate v2 principal boundary and cannot reach this workflow.
 */
@Component
public class MiniAppAuthInterceptor implements HandlerInterceptor {
    /** Raw initData travels in its own header: never a query parameter, never a cookie. */
    public static final String HEADER = "X-Telegram-Init-Data";

    private final JobPilotProperties properties;
    private final TelegramInitDataValidator validator;
    private final ObjectMapper objectMapper;
    private final IdentityProperties identity;

    public MiniAppAuthInterceptor(JobPilotProperties properties,
                                  TelegramInitDataValidator validator,
                                  ObjectMapper objectMapper, IdentityProperties identity) {
        this.properties = properties;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.identity = identity;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        if (!properties.miniApp().enabled()) {
            return reject(response, HttpStatus.SERVICE_UNAVAILABLE, MiniAppApiError.disabled());
        }

        String initData = request.getHeader(HEADER);
        if (initData == null || initData.isBlank()) {
            return reject(response, HttpStatus.UNAUTHORIZED, MiniAppApiError.unauthenticated());
        }

        TelegramInitDataValidator.Result result = validator.validate(initData);
        if (!result.valid()) {
            // MALFORMED, INVALID_HASH and NOT_YET_VALID collapse into one answer so the
            // response cannot be used to distinguish which check rejected the payload.
            return switch (result.failure()) {
                case EXPIRED -> reject(response, HttpStatus.UNAUTHORIZED, MiniAppApiError.expiredAuth());
                default -> reject(response, HttpStatus.UNAUTHORIZED, MiniAppApiError.invalidAuth());
            };
        }

        if (!properties.miniApp().allows(result.userId()) || !identity.isLegacyOwner(result.userId())) {
            return reject(response, HttpStatus.FORBIDDEN, MiniAppApiError.forbidden());
        }
        response.setHeader("Cache-Control", "no-store");
        return true;
    }

    private boolean reject(HttpServletResponse response, HttpStatus status, MiniAppApiError error)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(error));
        return false;
    }
}
