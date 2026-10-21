package com.jobpilot.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class IdentityWebBoundary implements WebMvcConfigurer {
    public static final String PRINCIPAL = AuthenticatedCandidate.class.getName();
    private final TelegramIdentityService identities;
    private final IdentityProperties settings;
    private final ObjectMapper json;
    private final com.jobpilot.config.JobPilotProperties application;

    public IdentityWebBoundary(TelegramIdentityService identities, IdentityProperties settings, ObjectMapper json, com.jobpilot.config.JobPilotProperties application) {
        this.identities=identities; this.settings=settings; this.json=json; this.application=application;
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
                response.setHeader("Cache-Control","no-store");
                try {
                    if (!settings.enabled()) throw new IdentityException(503,"IDENTITY_DISABLED");
                    if (java.util.Collections.list(request.getHeaders("Authorization")).size()>1
                            || java.util.Collections.list(request.getHeaders("X-Telegram-Init-Data")).size()>1)
                        throw new IdentityException(400,"INVALID_AUTH_REQUEST");
                    boolean exchange=request.getRequestURI().equals("/api/mini-app/v2/auth/telegram") && request.getMethod().equals("POST");
                    if (exchange) {
                        if (request.getHeader("X-Telegram-Init-Data")==null) throw new IdentityException(401,"UNAUTHENTICATED");
                    } else {
                        String authorization=request.getHeader("Authorization");
                        if (authorization==null || !authorization.startsWith("Bearer ")) throw new IdentityException(401,"UNAUTHENTICATED");
                        request.setAttribute(PRINCIPAL, identities.authenticate(authorization.substring(7)));
                    }
                    boolean jsonWrite = (request.getMethod().equals("PUT") && request.getRequestURI().equals("/api/mini-app/v2/me/notifications")) || (request.getMethod().equals("PUT") && request.getRequestURI().equals("/api/mini-app/v2/me/search-profile")) || (request.getMethod().equals("PUT") && request.getRequestURI().matches("/api/mini-app/v2/(jobs/[1-9][0-9]*/workflow|applications/[1-9][0-9]*/(notes|follow-up|status))"))
                            || (request.getMethod().equals("POST") && request.getRequestURI().equals("/api/mini-app/v2/undo"));
                    boolean matchesRead=request.getMethod().equals("GET")&&request.getRequestURI().equals("/api/mini-app/v2/matches");
                    if(matchesRead)for(var parameter:request.getParameterMap().entrySet()) {
                        if(!java.util.Set.of("limit","cursor","eligibility").contains(parameter.getKey())||parameter.getValue().length!=1||parameter.getValue()[0].length()>2048)
                            throw new IdentityException(400,"INVALID_MATCH_QUERY");
                    }
                    boolean body = request.getContentLengthLong()>0 || request.getHeader("Transfer-Encoding")!=null;
                    if (body && (!jsonWrite || request.getContentType()==null
                            || !org.springframework.http.MediaType.APPLICATION_JSON.isCompatibleWith(org.springframework.http.MediaType.parseMediaType(request.getContentType())))
                            || !matchesRead && (request.getQueryString()!=null || !request.getParameterMap().isEmpty()))
                        throw new IdentityException(400,"INVALID_AUTH_REQUEST");
                    return true;
                } catch (org.springframework.http.InvalidMediaTypeException invalid) {
                    reject(response,new IdentityException(400,"INVALID_AUTH_REQUEST")); return false;
                } catch (IdentityException denied) { reject(response,denied); return false; }
            }
        }).addPathPatterns("/api/mini-app/v2", "/api/mini-app/v2/**");
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
                if (!settings.enabled() && !application.miniApp().enabled()) return true;
                reject(response,new IdentityException(403,"PRIVATE_ENDPOINT")); return false;
            }
        }).addPathPatterns("/internal", "/internal/**", "/api/sources", "/api/sources/**");
    }
    private void reject(HttpServletResponse response,IdentityException error) throws IOException {
        response.setStatus(error.status()); response.setContentType("application/json");
        response.setHeader("Cache-Control","no-store");
        json.writeValue(response.getWriter(), Map.of("category",error.category()));
    }
}
