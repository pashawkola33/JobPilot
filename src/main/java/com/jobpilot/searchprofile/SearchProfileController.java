package com.jobpilot.searchprofile;

import com.fasterxml.jackson.databind.*;
import com.jobpilot.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mini-app/v2/me/search-profile")
public class SearchProfileController {
    private final SearchProfileService service;
    private final ObjectReader reader;
    public SearchProfileController(SearchProfileService service,ObjectMapper json) {
        this.service=service;
        ObjectMapper strict=json.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        strict.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        this.reader=strict.readerFor(SearchProfileService.Update.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
    @GetMapping public SearchProfileService.View get(HttpServletRequest request) {return service.get(principal(request));}
    @PutMapping public SearchProfileService.View put(HttpServletRequest request,@RequestBody JsonNode body) {
        SearchProfileService.Update update;
        try {update=reader.readValue(body);}catch(java.io.IOException e){throw new IdentityException(400,"INVALID_SEARCH_PROFILE");}
        return service.save(principal(request),update);
    }
    private AuthenticatedCandidate principal(HttpServletRequest request) {return (AuthenticatedCandidate)request.getAttribute(IdentityWebBoundary.PRINCIPAL);}
    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String,String>> denied(IdentityException error) {
        return ResponseEntity.status(error.status()).header("Cache-Control","no-store").body(Map.of("category",error.category()));
    }
}
