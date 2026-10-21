package com.jobpilot.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import jakarta.servlet.*;
import java.io.IOException;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class NotificationControllerTest {
 @ParameterizedTest @ValueSource(booleans={false,true})
 void oversizedBodyFailsBeforePreferenceSaveForDeclaredAndUnknownLength(boolean unknownLength)throws Exception {
  var bytesRead=new AtomicInteger();var observedLength=new AtomicLong();
  var settings=mock(NotificationSettingsService.class);
  var activation=mock(NotificationActivationService.class);
  var mvc=MockMvcBuilders.standaloneSetup(new NotificationController(settings,activation,new ObjectMapper()))
   .addFilter((request,response,chain)->chain.doFilter(new HttpServletRequestWrapper((HttpServletRequest)request){
    @Override public int getContentLength(){return unknownLength?-1:super.getContentLength();}
    @Override public long getContentLengthLong(){long length=unknownLength?-1:super.getContentLengthLong();observedLength.set(length);return length;}
    @Override public ServletInputStream getInputStream()throws IOException {
     var source=super.getInputStream();
     return new ServletInputStream(){
      public boolean isFinished(){return source.isFinished();}public boolean isReady(){return source.isReady();}
      public void setReadListener(ReadListener listener){source.setReadListener(listener);}
      public int read()throws IOException{int value=source.read();if(value>=0)bytesRead.incrementAndGet();return value;}
      public int read(byte[] bytes,int offset,int length)throws IOException{int count=source.read(bytes,offset,length);if(count>0)bytesRead.addAndGet(count);return count;}
     };
    }
   },response)).build();
  mvc.perform(put("/api/mini-app/v2/me/notifications").contentType("application/json").content(" ".repeat(2*1024*1024)+"{}"))
   .andExpect(status().isBadRequest());
  assertThat(observedLength.get()).isEqualTo(unknownLength?-1:2*1024*1024+2);
  assertThat(bytesRead.get()).isEqualTo(unknownLength?8193:0);
  verifyNoInteractions(settings,activation);
 }
}
