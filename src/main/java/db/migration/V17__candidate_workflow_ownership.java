package db.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.zip.CRC32;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** PostgreSQL migration with a separate H2 dialect for the existing fast regression suite. */
public class V17__candidate_workflow_ownership extends BaseJavaMigration {
    private static final String POSTGRES="db/ownership/V17-postgresql.sql";
    private static final String H2="db/ownership/V17-h2.sql";
    @Override public Integer getChecksum() {
        CRC32 checksum=new CRC32();
        checksum.update((read(POSTGRES)+"\n"+read(H2)).getBytes(StandardCharsets.UTF_8));
        return (int)checksum.getValue();
    }
    @Override public void migrate(Context context) throws Exception {
        Connection connection=context.getConnection();
        String database=connection.getMetaData().getDatabaseProductName();
        String sql;
        if(database.equals("PostgreSQL")) sql=read(POSTGRES);
        else if(database.equals("H2")) {
            long owner=scalar(connection,"select id from candidates where stable_key='default'");
            long next=scalar(connection,"select coalesce(max(job_id)+1,1) from job_workflow_state");
            sql=read(H2).replace("${owner}",Long.toString(owner))
                    .replace("${workflowSequence}",Long.toString(next))
                    .replace("${workflowJobFk}",constraint(connection,"job_workflow_state","FOREIGN KEY",List.of("job_id")))
                    .replace("${applicationJobFk}",constraint(connection,"applications","FOREIGN KEY",List.of("job_id")))
                    .replace("${workflowPk}",constraint(connection,"job_workflow_state","PRIMARY KEY",List.of("job_id")))
                    .replace("${statePk}",constraint(connection,"mini_app_state","PRIMARY KEY",List.of("id")))
                    .replace("${applicationJobUnique}",constraint(connection,"applications","UNIQUE",List.of("job_id")))
                    .replace("${mutationKeyUnique}",constraint(connection,"mini_app_mutation","UNIQUE",List.of("mutation_key")))
                    .replace("${mutationRevisionUnique}",constraint(connection,"mini_app_mutation","UNIQUE",List.of("mutation_revision")));
        } else throw new IllegalStateException("Unsupported workflow migration database: "+database);
        try(var statement=connection.createStatement()) {
            if(database.equals("PostgreSQL")) statement.execute(sql);
            else {
                // H2 script contains only DDL/DML; PostgreSQL's function bodies stay unsplit.
                String stripped=sql.replaceAll("(?m)--.*$","");
                for(String command:stripped.split(";")) if(!command.isBlank()) statement.execute(command);
            }
        }
    }
    private static long scalar(Connection connection,String sql) throws Exception {
        try(var statement=connection.createStatement();var rows=statement.executeQuery(sql)) {
            if(!rows.next()) throw new IllegalStateException("Migration owner or sequence is missing");
            long value=rows.getLong(1);
            if(rows.next()) throw new IllegalStateException("Migration owner is ambiguous");
            return value;
        }
    }
    private static String constraint(Connection connection,String table,String kind,List<String> columns) throws Exception {
        String query="select tc.constraint_name,kcu.column_name from information_schema.table_constraints tc join information_schema.key_column_usage kcu on tc.constraint_catalog=kcu.constraint_catalog and tc.constraint_schema=kcu.constraint_schema and tc.constraint_name=kcu.constraint_name where tc.table_schema=? and tc.table_name=? and tc.constraint_type=? order by tc.constraint_name,kcu.ordinal_position";
        var constraints=new java.util.LinkedHashMap<String,java.util.List<String>>();
        try(var statement=connection.prepareStatement(query)) {
            statement.setString(1,connection.getSchema()); statement.setString(2,table);statement.setString(3,kind);
            try(var rows=statement.executeQuery()) { while(rows.next()) constraints.computeIfAbsent(rows.getString(1),key->new java.util.ArrayList<>()).add(rows.getString(2)); }
        }
        var matches=constraints.entrySet().stream().filter(entry->entry.getValue().equals(columns)).map(java.util.Map.Entry::getKey).toList();
        if(matches.size()!=1) throw new IllegalStateException("Expected one "+kind+" constraint on "+table+columns);
        return "\""+matches.getFirst().replace("\"","\"\"")+"\"";
    }
    private static String read(String path) {
        try(var stream=V17__candidate_workflow_ownership.class.getClassLoader().getResourceAsStream(path)) {
            if(stream==null) throw new IllegalStateException("Missing migration resource "+path);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException error) { throw new IllegalStateException("Cannot read migration resource",error); }
    }
}
