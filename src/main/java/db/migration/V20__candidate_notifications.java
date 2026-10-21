package db.migration;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Additive preference storage; H2 remains a fast-test dialect only. */
public class V20__candidate_notifications extends BaseJavaMigration {
    private static String read(String name) {
        try(var stream=V20__candidate_notifications.class.getClassLoader().getResourceAsStream("db/notifications/"+name)) {
            if(stream==null) throw new IllegalStateException("Missing search migration resource");
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        } catch(java.io.IOException e) {throw new IllegalStateException(e);}
    }
    @Override public Integer getChecksum() {
        CRC32 crc=new CRC32();crc.update((read("V20-schema.sql")+"\n"+read("V20-postgresql.sql")).getBytes(StandardCharsets.UTF_8));
        return (int)crc.getValue();
    }
    @Override public void migrate(Context context) throws Exception {
        String database=context.getConnection().getMetaData().getDatabaseProductName();
        if(!database.equals("PostgreSQL") && !database.equals("H2")) throw new IllegalStateException("Unsupported candidate notifications database");
        try(var statement=context.getConnection().createStatement()) {
            for(String sql:read("V20-schema.sql").split(";")) if(!sql.isBlank())statement.execute(sql);
            if(database.equals("PostgreSQL"))statement.execute(read("V20-postgresql.sql"));
            byte[] epoch=new byte[32];new java.security.SecureRandom().nextBytes(epoch);
            try(var update=context.getConnection().prepareStatement("update notification_runtime_state set runtime_epoch=? where id=1")){
                update.setString(1,java.util.HexFormat.of().formatHex(epoch));update.executeUpdate();
            }
        }
    }
}
