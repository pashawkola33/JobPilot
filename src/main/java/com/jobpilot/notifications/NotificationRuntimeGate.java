package com.jobpilot.notifications;

import com.jobpilot.common.ApplicationLifecycleGate;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** External reconciliation is required after every process start, including a normal restart. */
@Component
public class NotificationRuntimeGate {
    public record OperatorIdentity(String epoch,String nonce) { }
    private final JdbcTemplate jdbc;private final NotificationProperties properties;private final Environment env;
    private final ApplicationLifecycleGate lifecycle;private final String nonce;private volatile boolean halted;
    public NotificationRuntimeGate(JdbcTemplate jdbc,NotificationProperties properties,Environment env,ApplicationLifecycleGate lifecycle) {
        this.jdbc=jdbc;this.properties=properties;this.env=env;this.lifecycle=lifecycle;
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);nonce=HexFormat.of().formatHex(bytes);
    }
    public OperatorIdentity operatorIdentity(){return new OperatorIdentity(jdbc.queryForObject("select runtime_epoch from notification_runtime_state where id=1",String.class),nonce);}
    @EventListener(ApplicationReadyEvent.class) public void operatorStartup() {
        if(properties.enabled()&&!recovery()) {
            var id=operatorIdentity();org.slf4j.LoggerFactory.getLogger(getClass()).info("Personalized notifications DISARMED; operator reconciliation epoch={} processNonce={}",id.epoch(),id.nonce());
        }
    }
    public void halt(){halted=true;}
    public boolean recovery(){return Arrays.asList(env.getActiveProfiles()).contains("recovery");}
    public boolean permitted() {
        if(halted||!properties.enabled()||recovery()||!lifecycle.acceptingWork())return false;
        try {
            Path path=Path.of(env.getProperty("jobpilot.notifications.approval-file","/run/jobpilot-notifications/approval"));
            if(!path.isAbsolute())return false;
            var before=Files.readAttributes(path,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            var owner=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
            if(!before.isRegularFile()||before.isSymbolicLink()||before.size()>256||!before.owner().equals(owner)||!before.permissions().equals(PosixFilePermissions.fromString("rw-------")))return false;
            ByteBuffer bytes=ByteBuffer.allocate(257);
            try(var channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){while(bytes.hasRemaining()&&channel.read(bytes)>0){} }
            if(bytes.position()>256)return false;
            var after=Files.readAttributes(path,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!Objects.equals(before.fileKey(),after.fileKey())||!before.lastModifiedTime().equals(after.lastModifiedTime())||after.size()!=before.size())return false;
            var id=operatorIdentity();String expected=id.epoch()+"\n"+id.nonce()+"\n";
            return java.security.MessageDigest.isEqual(Arrays.copyOf(bytes.array(),bytes.position()),expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch(Exception ignored){return false;}
    }
}
