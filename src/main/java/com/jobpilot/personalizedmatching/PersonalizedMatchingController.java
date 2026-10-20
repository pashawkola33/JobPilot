package com.jobpilot.personalizedmatching;
import com.jobpilot.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/mini-app/v2")
public class PersonalizedMatchingController {
    private final PersonalizedMatchQuery query;
    public PersonalizedMatchingController(PersonalizedMatchQuery query){this.query=query;}
    private AuthenticatedCandidate principal(HttpServletRequest r){return (AuthenticatedCandidate)r.getAttribute(IdentityWebBoundary.PRINCIPAL);}
    @GetMapping("/me/matching") public PersonalizedMatchQuery.State state(HttpServletRequest r){return query.state(principal(r));}
    @GetMapping("/matches") public PersonalizedMatchQuery.Page matches(HttpServletRequest r,@RequestParam(required=false) String limit,@RequestParam(required=false) String cursor,@RequestParam(required=false) String eligibility){return query.list(principal(r),limit,cursor,eligibility);}
    @GetMapping("/jobs/{jobId}") public PersonalizedMatchQuery.Detail detail(HttpServletRequest r,@PathVariable long jobId){if(jobId<1)throw new IdentityException(400,"INVALID_JOB_ID");return query.detail(principal(r),jobId);}
    @ExceptionHandler(IdentityException.class) public ResponseEntity<Map<String,String>> denied(IdentityException e){return ResponseEntity.status(e.status()).header("Cache-Control","no-store").body(Map.of("category",e.category()));}
}
