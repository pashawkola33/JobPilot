package com.jobpilot.notifications;
import com.jobpilot.identity.IdentityException;
import java.time.ZoneId;
public record NotificationPreferences(boolean enabled,boolean paused,boolean immediate,boolean digest,
        int minimumScore,String timezone,String quietStart,String quietEnd) {
    public NotificationPreferences {
        if(minimumScore<0||minimumScore>100||timezone==null||!ZoneId.getAvailableZoneIds().contains(timezone)
            ||enabled&&!immediate&&!digest|| (quietStart==null)!=(quietEnd==null)
            ||quietStart!=null&&(!quietStart.matches("([01][0-9]|2[0-3]):[0-5][0-9]")||!quietEnd.matches("([01][0-9]|2[0-3]):[0-5][0-9]")||quietStart.equals(quietEnd)))
            throw new IdentityException(400,"INVALID_NOTIFICATION_PREFERENCES");
    }
    public static NotificationPreferences defaults(){return new NotificationPreferences(false,false,true,false,85,"UTC",null,null);}
}
