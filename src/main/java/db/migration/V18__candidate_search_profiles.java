package db.migration;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Additive preference storage; H2 remains a fast-test dialect only. */
public class V18__candidate_search_profiles extends BaseJavaMigration {
    private static String read(String name) {
        try(var stream=V18__candidate_search_profiles.class.getClassLoader().getResourceAsStream("db/search/"+name)) {
            if(stream==null) throw new IllegalStateException("Missing search migration resource");
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        } catch(java.io.IOException e) {throw new IllegalStateException(e);}
    }
    @Override public Integer getChecksum() {
        CRC32 crc=new CRC32();crc.update((read("V18-schema.sql")+"\n"+read("V18-postgresql.sql")).getBytes(StandardCharsets.UTF_8));
        return (int)crc.getValue();
    }
    @Override public void migrate(Context context) throws Exception {
        String database=context.getConnection().getMetaData().getDatabaseProductName();
        if(!database.equals("PostgreSQL") && !database.equals("H2")) throw new IllegalStateException("Unsupported search profile database");
        try(var statement=context.getConnection().createStatement()) {
            for(String sql:read("V18-schema.sql").split(";")) if(!sql.isBlank())statement.execute(sql);
            if(database.equals("PostgreSQL"))statement.execute(read("V18-postgresql.sql"));
        }
    }
}
