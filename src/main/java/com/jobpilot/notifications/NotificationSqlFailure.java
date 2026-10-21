package com.jobpilot.notifications;
import java.sql.SQLException;
final class NotificationSqlFailure {
 private NotificationSqlFailure(){}
 static boolean transientBeforeDispatch(Throwable error){
  for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql){String state=sql.getSQLState();if("40P01".equals(state)){org.slf4j.LoggerFactory.getLogger(NotificationSqlFailure.class).error("Notification SQL refusal SQLState=40P01");return true;}if("55P03".equals(state)||"57014".equals(state)){org.slf4j.LoggerFactory.getLogger(NotificationSqlFailure.class).info("Notification SQL refusal SQLState={}",state);return true;}}
  return false;
 }
}
