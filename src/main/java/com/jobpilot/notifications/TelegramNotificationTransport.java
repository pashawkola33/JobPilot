package com.jobpilot.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.config.JobPilotProperties;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** One exchange only. Errors deliberately retain no exception, URL, token or response text. */
@Component
public class TelegramNotificationTransport implements NotificationTransport {
    private final HttpClient client;private final URI endpoint;private final ObjectMapper json;
    @Autowired public TelegramNotificationTransport(JobPilotProperties app,ObjectMapper json) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build(),
            configuredEndpoint(app.telegram().botToken()),json);
    }
    TelegramNotificationTransport(HttpClient client,URI endpoint,ObjectMapper json) {this.client=client;this.endpoint=endpoint;this.json=json;}
    private static URI configuredEndpoint(String token){
        if(token==null||!token.matches("[0-9]{1,20}:[A-Za-z0-9_-]{1,256}"))return null;
        return URI.create("https://api.telegram.org/bot"+token+"/sendMessage");
    }
    @Override public Outcome send(long chat,String text) {
        if(endpoint==null)return new Outcome("HALT","BOT_UNAVAILABLE",null,0);
        if(chat<=0||text==null||text.isBlank()||text.length()>4096)return new Outcome("FAILED","INVALID_ENVELOPE",null,0);
        CompletableFuture<HttpResponse<byte[]>> future=null;
        try {
            byte[] payload=json.writeValueAsBytes(Map.of("chat_id",chat,"text",text,"parse_mode","HTML","disable_web_page_preview",true));
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            future=client.sendAsync(request,response->new BoundedBody());
            var response=future.get(5,TimeUnit.SECONDS);var body=json.readTree(response.body());int status=response.statusCode();
            if(status==200&&body.path("ok").isBoolean()&&body.path("ok").booleanValue()) {
                var id=body.path("result").path("message_id");
                if(id.isIntegralNumber()&&id.canConvertToLong()&&id.longValue()>0)return new Outcome("SENT","ACCEPTED",id.longValue(),0);
            }
            if(body.path("ok").isBoolean()&&!body.path("ok").booleanValue()&&body.path("error_code").isIntegralNumber()&&body.path("error_code").intValue()==status) {
                if(status==429){var retry=body.path("parameters").path("retry_after");if(retry.isIntegralNumber()&&retry.canConvertToInt()&&retry.intValue()>0&&retry.intValue()<=3600)return new Outcome("RETRY","RATE_LIMITED",null,retry.intValue());}
                if(status==403)return new Outcome("BLOCKED","PRIVATE_CHAT_BLOCKED",null,0);
                if(status==400)return new Outcome("FAILED","BAD_REQUEST",null,0);
                if(status==401||status==404)return new Outcome("HALT","BOT_UNAVAILABLE",null,0);
            }
            return Outcome.uncertain();
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();return Outcome.uncertain();}
        catch(Exception unsafe) {return Outcome.uncertain();}
        finally {if(future!=null&&!future.isDone())future.cancel(true);}
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> chunks){
            for(var chunk:chunks){if(chunk.remaining()>16384-bytes.size()){subscription.cancel();result.completeExceptionally(new IllegalStateException("RESPONSE_LIMIT"));return;}byte[] part=new byte[chunk.remaining()];chunk.get(part);bytes.writeBytes(part);}subscription.request(1);
        }
        public void onError(Throwable ignored){result.completeExceptionally(new IllegalStateException("RESPONSE_FAILED"));}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
