package com.jobpilot.notifications;
public interface NotificationTransport {
 record Outcome(String state,String category,Long messageId,int retryAfter) {
  public static Outcome uncertain(){return new Outcome("UNCERTAIN","TRANSPORT_UNCERTAIN",null,0);}
 }
 Outcome send(long privateChatId,String html);
}
