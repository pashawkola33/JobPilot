package com.jobpilot.identity;

import com.jobpilot.miniapp.auth.MiniAppAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mini-app/v2")
public class IdentityController {
    private final TelegramIdentityService identities;
    public IdentityController(TelegramIdentityService identities) { this.identities=identities; }

    @PostMapping("/auth/telegram")
    public TelegramIdentityService.Session exchange(@RequestHeader(value=MiniAppAuthInterceptor.HEADER,required=false) String initData) {
        return identities.exchange(initData);
    }
    @GetMapping("/me")
    public TelegramIdentityService.Account me(HttpServletRequest request) {
        return identities.me((AuthenticatedCandidate)request.getAttribute(IdentityWebBoundary.PRINCIPAL));
    }
    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String,String>> denied(IdentityException error) {
        return ResponseEntity.status(error.status()).header("Cache-Control","no-store").body(Map.of("category",error.category()));
    }
}
