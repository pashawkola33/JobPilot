package com.jobpilot.telegram.polling;

import com.jobpilot.common.Hashing;
import java.sql.Connection;
import java.util.Arrays;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** The session lock spans the whole poll cycle and its update acknowledgements. */
@Component
public class TelegramPollingLease {
    private final JdbcTemplate jdbc;private final Environment env;private final long key;
    public TelegramPollingLease(JdbcTemplate jdbc,Environment env){this.jdbc=jdbc;this.env=env;key=Long.parseUnsignedLong(Hashing.sha256("jobpilot-poller\0"+env.getProperty("jobpilot.telegram.bot-token","")).substring(0,16),16);}
    public AutoCloseable acquire() {
        if(Arrays.asList(env.getActiveProfiles()).contains("recovery"))return null;
        Connection connection=null;
        try {
            connection=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection();
            try(var query=connection.prepareStatement("select pg_try_advisory_lock(?)")){query.setLong(1,key);try(var rows=query.executeQuery()){if(!rows.next()||!rows.getBoolean(1)){connection.close();return null;}}}
            Connection held=connection;
            return ()->{try{try(var unlock=held.prepareStatement("select pg_advisory_unlock(?)")){unlock.setLong(1,key);unlock.execute();}}catch(Exception failure){try{held.abort(Runnable::run);}catch(Exception ignored){}}finally{held.close();}};
        }catch(Exception failure){if(connection!=null)try{connection.close();}catch(Exception ignored){}return null;}
    }
}
