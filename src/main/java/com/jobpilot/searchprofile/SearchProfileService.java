package com.jobpilot.searchprofile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.identity.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchProfileService {
    public record Update(Long expectedRevision, String requestId, SearchProfile profile) { }
    public record View(long activeRevision, SearchProfile profile, String completionStatus,
                       List<String> missingRequiredFields, Instant createdAt) { }
    private final JdbcTemplate jdbc;
    private final TelegramIdentityService identities;
    private final ObjectMapper json;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment notificationEnvironment=new org.springframework.core.env.StandardEnvironment();
    public SearchProfileService(JdbcTemplate jdbc,TelegramIdentityService identities,ObjectMapper json,Clock clock) {
        this.jdbc=jdbc;this.identities=identities;this.json=json;this.clock=clock;
    }
    @Transactional(readOnly=true)
    public View get(AuthenticatedCandidate principal) {
        identities.me(principal);
        return current(principal.candidateId());
    }
    @Transactional(timeout=15)
    public View save(AuthenticatedCandidate principal,Update update) {
        identities.me(principal);
        if(update==null || update.expectedRevision()==null || update.expectedRevision()<0 || update.expectedRevision()==Long.MAX_VALUE
                || update.profile()==null || update.requestId()==null || !update.requestId().matches("[A-Za-z0-9_-]{8,64}"))
            throw new IdentityException(400,"INVALID_SEARCH_PROFILE");
        long candidate=principal.candidateId();
        // This lock exists even before a profile does, and remains held through activation/commit.
        com.jobpilot.notifications.NotificationLocks.bound(jdbc).lockCandidate(candidate);
        identities.me(principal);
        String payload=encode(update.profile());
        View active=current(candidate);
        var replay=jdbc.query("select revision,expected_revision,payload from search_profile_revisions where candidate_id=? and request_id=?",
                (r,n)->new Object[]{r.getLong(1),r.getLong(2),r.getString(3)},candidate,update.requestId());
        if(!replay.isEmpty()) {
            Object[] old=replay.getFirst();
            if(!old[0].equals(active.activeRevision()) || !old[1].equals(update.expectedRevision()) || !encode(decode((String)old[2])).equals(payload))throw conflict();
            return active;
        }
        if(active.activeRevision()!=update.expectedRevision())throw conflict();
        long revision=active.activeRevision()+1;Instant now=clock.instant();
        boolean completed=update.profile().missingRequiredFields().isEmpty();
        jdbc.update("insert into search_profile_revisions(candidate_id,revision,expected_revision,request_id,payload,completed,created_at) values(?,?,?,?,?,?,?)",
                candidate,revision,update.expectedRevision(),update.requestId(),payload,completed,Timestamp.from(now));
        if(active.activeRevision()==0)jdbc.update("insert into search_profile_state(candidate_id,active_revision) values(?,?)",candidate,revision);
        else jdbc.update("update search_profile_state set active_revision=? where candidate_id=?",revision,candidate);
        com.jobpilot.personalizedmatching.MatchingWorkService.activate(jdbc,candidate,revision,completed);
        if(!java.util.Arrays.asList(notificationEnvironment.getActiveProfiles()).contains("recovery"))
            com.jobpilot.notifications.NotificationSettingsService.rebaseline(jdbc,candidate,"PROFILE_CHANGED");
        return current(candidate);
    }
    private View current(long candidate) {
        var profiles=jdbc.query("select r.revision,r.payload,r.created_at from search_profile_state s join search_profile_revisions r on r.candidate_id=s.candidate_id and r.revision=s.active_revision where s.candidate_id=?",
                (r,n)->view(r.getLong(1),decode(r.getString(2)),r.getTimestamp(3).toInstant()),candidate);
        return profiles.isEmpty()?view(0,SearchProfile.empty(),null):profiles.getFirst();
    }
    private View view(long revision,SearchProfile profile,Instant createdAt) {
        var missing=profile.missingRequiredFields();
        return new View(revision,profile,revision==0?"NOT_STARTED":missing.isEmpty()?"COMPLETE":"INCOMPLETE",missing,createdAt);
    }
    private SearchProfile decode(String payload) {
        try{return json.readValue(payload,SearchProfile.class);}catch(java.io.IOException e){throw new IllegalStateException("Invalid stored search profile",e);}
    }
    private String encode(SearchProfile profile) {
        try{return json.writeValueAsString(profile);}catch(java.io.IOException e){throw new IllegalStateException("Cannot encode search profile",e);}
    }
    private IdentityException conflict(){return new IdentityException(409,"SEARCH_PROFILE_CONFLICT");}
}
