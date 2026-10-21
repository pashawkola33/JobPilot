package com.jobpilot.notifications;

import java.time.*;

public final class NotificationSchedule {
    private NotificationSchedule() { }
    public static boolean quiet(Instant now,String zone,String start,String end) {
        if(start==null||end==null)return false;
        LocalTime time=now.atZone(ZoneId.of(zone)).toLocalTime(),from=LocalTime.parse(start),until=LocalTime.parse(end);
        return from.isBefore(until)?!time.isBefore(from)&&time.isBefore(until):!time.isBefore(from)||time.isBefore(until);
    }
    public static boolean digestDue(Instant now,String zone,String start,String end) {
        ZoneId timezone=ZoneId.of(zone);LocalDate date=now.atZone(timezone).toLocalDate();
        if(quiet(now,zone,start,end))return false;
        Instant today=digestTarget(date,timezone,start,end);
        if(!now.isBefore(today)&&today.atZone(timezone).toLocalDate().equals(date))return true;
        // Only a quiet-hours deferral may cross the date boundary; missed ordinary digests never catch up.
        Instant deferred=digestTarget(date.minusDays(1),timezone,start,end);
        return deferred.atZone(timezone).toLocalDate().equals(date)&&!now.isBefore(deferred);
    }
    private static Instant digestTarget(LocalDate date,ZoneId timezone,String start,String end){
        Instant target=resolve(date,LocalTime.of(9,0),timezone);
        if(quiet(target,timezone.getId(),start,end)){
            LocalTime until=LocalTime.parse(end);LocalDate quietEnd=until.isBefore(LocalTime.of(9,0))?date.plusDays(1):date;
            target=resolve(quietEnd,until,timezone);
        }return target;
    }
    public static Instant resolve(LocalDate date,LocalTime time,ZoneId zone) {
        LocalDateTime local=date.atTime(time);var offsets=zone.getRules().getValidOffsets(local);
        if(offsets.isEmpty())return zone.getRules().getTransition(local).getInstant();
        return local.toInstant(offsets.getFirst());
    }
}
