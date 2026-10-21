package com.jobpilot.notifications;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;import java.net.http.*;import java.time.Duration;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
class TelegramNotificationTransportTest {
 HttpServer server;AtomicInteger requests=new AtomicInteger();AtomicReference<String> request=new AtomicReference<>();
 TelegramNotificationTransport transport(int code,String body,long delay)throws Exception{
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
  server.createContext("/sendMessage",x->{requests.incrementAndGet();request.set(new String(x.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));try{Thread.sleep(delay);x.sendResponseHeaders(code,body.length());x.getResponseBody().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{x.close();}});server.start();
  return new TelegramNotificationTransport(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/sendMessage"),new ObjectMapper());
 }
 @AfterEach void stop(){if(server!=null)server.stop(0);}
 @Test void positiveSuccessRequiresValidMessageIdAndSendsOneRequest()throws Exception{var out=transport(200,"{\"ok\":true,\"result\":{\"message_id\":123}}",0).send(9002,"<b>Synthetic</b>");assertThat(out.state()).isEqualTo("SENT");assertThat(out.messageId()).isEqualTo(123);assertThat(requests).hasValue(1);assertThat(request.get()).contains("\"chat_id\":9002","HTML").doesNotContain("callback_data");}
 @Test void definitive429IsTheOnlyRetryableOutcome()throws Exception{var out=transport(429,"{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":7}}",0).send(9002,"Synthetic");assertThat(out.state()).isEqualTo("RETRY");assertThat(out.retryAfter()).isEqualTo(7);assertThat(requests).hasValue(1);}
 @Test void blockedIsPermanent()throws Exception{assertThat(transport(403,"{\"ok\":false,\"error_code\":403}",0).send(9002,"Synthetic").state()).isEqualTo("BLOCKED");assertThat(requests).hasValue(1);}
 @Test void badRequestIsPermanent()throws Exception{assertThat(transport(400,"{\"ok\":false,\"error_code\":400}",0).send(9002,"Synthetic").state()).isEqualTo("FAILED");assertThat(requests).hasValue(1);}
 @Test void serverErrorIsUncertainWithoutRetry()throws Exception{assertThat(transport(503,"unsafe body token location",0).send(9002,"Synthetic").state()).isEqualTo("UNCERTAIN");assertThat(requests).hasValue(1);}
 @Test void malformedSuccessIsUncertain()throws Exception{assertThat(transport(200,"{\"ok\":true,\"result\":{\"message_id\":\"123\"}}",0).send(9002,"Synthetic").state()).isEqualTo("UNCERTAIN");assertThat(requests).hasValue(1);}
 @Test void oversizedBodyIsUncertainAndBounded()throws Exception{assertThat(transport(200,"x".repeat(17000),0).send(9002,"Synthetic").state()).isEqualTo("UNCERTAIN");assertThat(requests).hasValue(1);}
 @Test void timeoutAfterAcceptanceNeverRetries()throws Exception{long start=System.nanoTime();assertThat(transport(200,"{\"ok\":true,\"result\":{\"message_id\":123}}",5500).send(9002,"Synthetic").state()).isEqualTo("UNCERTAIN");assertThat(Duration.ofNanos(System.nanoTime()-start).toMillis()).isBetween(4500L,6500L);assertThat(requests).hasValue(1);}
 @Test void redirectIsNotFollowed()throws Exception{assertThat(transport(302,"",0).send(9002,"Synthetic").state()).isEqualTo("UNCERTAIN");assertThat(requests).hasValue(1);}

 @Test void malformedConfiguredTokenCannotEscapeThroughConstructorOrOutcome(){
  for(String token:java.util.List.of("synthetic secret token","synthetic\nsecret-token","synthetic\rsecret-token")){
   var properties=com.jobpilot.support.TestProperties.create(new com.jobpilot.config.JobPilotProperties.Telegram(token,""));
   java.util.concurrent.atomic.AtomicReference<TelegramNotificationTransport> transport=new java.util.concurrent.atomic.AtomicReference<>();
   Throwable failure=catchThrowable(()->transport.set(new TelegramNotificationTransport(properties,new ObjectMapper())));
   assertThat(failure).as("malformed credential must produce a disabled, sanitized transport").isNull();
   var outcome=transport.get().send(9002,"Synthetic");assertThat(outcome.state()).isEqualTo("HALT");assertThat(outcome.toString()).doesNotContain(token,"secret","synthetic","https://");
  }
 }
}
