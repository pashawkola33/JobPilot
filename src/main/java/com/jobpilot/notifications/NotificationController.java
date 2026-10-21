package com.jobpilot.notifications;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.jobpilot.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/mini-app/v2/me/notifications")
public class NotificationController {
    private final NotificationSettingsService settings;private final NotificationActivationService activation;private final ObjectReader reader;
    public NotificationController(NotificationSettingsService settings,NotificationActivationService activation,ObjectMapper json){
        this.settings=settings;this.activation=activation;
        ObjectMapper strict=json.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        strict.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual).setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail).setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail).setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        reader=strict.readerFor(NotificationSettingsService.Update.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
    @GetMapping public NotificationSettingsService.View get(HttpServletRequest request){return settings.view(principal(request));}
    @PutMapping public NotificationSettingsService.View put(HttpServletRequest request){
        NotificationSettingsService.Update update;try{if(request.getContentLengthLong()>8192)throw new IdentityException(400,"INVALID_NOTIFICATION_PREFERENCES");
            byte[] body=request.getInputStream().readNBytes(8193);
            if(body.length>8192)throw new IdentityException(400,"INVALID_NOTIFICATION_PREFERENCES");
            update=reader.readValue(body);}catch(java.io.IOException e){throw new IdentityException(400,"INVALID_NOTIFICATION_PREFERENCES");}
        return settings.save(principal(request),update);
    }
    @PostMapping("/activation") public NotificationActivationService.Challenge activation(HttpServletRequest request){return activation.challenge(principal(request));}
    @DeleteMapping("/connection") public NotificationSettingsService.View disconnect(HttpServletRequest request){return settings.disconnect(principal(request));}
    private AuthenticatedCandidate principal(HttpServletRequest request){return (AuthenticatedCandidate)request.getAttribute(IdentityWebBoundary.PRINCIPAL);}
    @ExceptionHandler(IdentityException.class) public ResponseEntity<Map<String,String>> denied(IdentityException e){return ResponseEntity.status(e.status()).header("Cache-Control","no-store").body(Map.of("category",e.category()));}
}
