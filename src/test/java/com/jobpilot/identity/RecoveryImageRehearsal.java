package com.jobpilot.identity;

import com.fasterxml.jackson.databind.*;
import com.jobpilot.miniapp.auth.TelegramInitDataFixture;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.*;

/** Disposable synthetic-only rehearsal of the immutable saved runtime image. */
public class RecoveryImageRehearsal {
 static Path DIR;
 static final ObjectMapper JSON=new ObjectMapper();
 static final HttpClient HTTP=HttpClient.newHttpClient();
 static int passed;
 static String image;
 static void check(boolean ok,String description){if(!ok)throw new AssertionError(description);passed++;System.out.println("PASS: "+description);}
 static Connection connection(PostgreSQLContainer<?> db)throws Exception{return DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());}
 static HttpResponse<String> call(String base,String method,String path,String header,String value,String body)throws Exception {
  var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15));
  if(header!=null)request.header(header,value);
  if(body!=null)request.header("Content-Type","application/json");
  return HTTP.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
 }
 static JsonNode ok(String base,String method,String path,String token,String body)throws Exception {
  var r=call(base,method,path,"Authorization","Bearer "+token,body);
  if(r.statusCode()!=200)throw new AssertionError(path+" "+r.statusCode()+": "+r.body());
  return JSON.readTree(r.body());
 }
 static String launch(long id){return TelegramInitDataFixture.launch(id,Instant.now().getEpochSecond()).with("query_id",UUID.randomUUID().toString()).signed();}
 static String login(String base,long id)throws Exception {
  var r=call(base,"POST","/api/mini-app/v2/auth/telegram","X-Telegram-Init-Data",launch(id),null);
  check(r.statusCode()==200,"image authenticates synthetic account "+id);
  return JSON.readTree(r.body()).path("accessToken").asText();
 }
 static JsonNode change(String base,String token,int job,String status,String key)throws Exception {
  return ok(base,"PUT","/api/mini-app/v2/jobs/"+job+"/workflow",token,JSON.writeValueAsString(Map.of("status",status,"mutationId",key)));
 }
 static JsonNode snapshot(String base,String token)throws Exception {return ok(base,"GET","/api/mini-app/v2/snapshot",token,null);}
 static List<List<String>> rows(Connection c,String table,String where)throws Exception {
  var out=new ArrayList<List<String>>();try(var s=c.createStatement();var r=s.executeQuery("select * from "+table+" "+where)) {
   while(r.next()){var row=new ArrayList<String>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getString(i));out.add(row);}
  }out.sort(Comparator.comparing(Object::toString));return out;
 }
 static Map<String,List<List<String>>> beta(Connection c)throws Exception {
  var out=new TreeMap<String,List<List<String>>>();
  for(String table:List.of("job_workflow_state","applications","application_status_history","mini_app_state","mini_app_mutation","telegram_identities","auth_sessions","telegram_auth_exchanges","resume_versions","cover_notes","search_profile_revisions","search_profile_state","candidate_match_results","candidate_match_current","candidate_matching_state"))
   out.put(table,rows(c,table,"where candidate_id in (select candidate_id from telegram_identities where telegram_user_id in (9002,9003))"));
  return out;
 }
 @SuppressWarnings("unchecked") static String runtimeConfiguration(boolean recovery)throws Exception {
  Map<String,Object> values=new LinkedHashMap<>((Map<String,Object>)new org.yaml.snakeyaml.Yaml().load(RecoveryImageRehearsal.class.getResourceAsStream("/fixtures/identity-legacy-profile.yml")));
  values.put("jobpilot.personalized-matching.enabled",!recovery);
  values.put("jobpilot.scheduled-tasks-enabled",!recovery);
  values.put("jobpilot.scheduling.fetch-cron","-");values.put("jobpilot.scheduling.digest-cron","-");
  return JSON.writeValueAsString(values);
 }
 static void matchingReady(String base,String token)throws Exception {
  long end=System.nanoTime()+Duration.ofSeconds(30).toNanos();
  while(System.nanoTime()<end){var state=ok(base,"GET","/api/mini-app/v2/me/matching",token,null);if(state.path("status").asText().equals("READY"))return;Thread.sleep(200);}
  throw new AssertionError("bounded image worker did not finish synthetic matching");
 }
 static Map<String,List<List<String>>> catalogueRows(Connection c)throws Exception {
  var out=new TreeMap<String,List<List<String>>>();
  for(String table:List.of("vacancy_fact_revisions","vacancy_fact_state","matching_catalogue_state","matching_catalogue_changes"))out.put(table,rows(c,table,""));return out;
 }
 static class App implements AutoCloseable {
  GenericContainer<?> container;String base;String name;
  App(Network network,PostgreSQLContainer<?> db,boolean recovery,boolean fail,String name)throws Exception {
   this.name=name;
   container=new GenericContainer<>(image).withImagePullPolicy(n->false).withNetwork(network).withExposedPorts(8080)
    .withEnv("DATABASE_URL","jdbc:postgresql://"+db.getNetworkAliases().get(0)+":5432/"+db.getDatabaseName())
    .withEnv("POSTGRES_USER",db.getUsername()).withEnv("POSTGRES_PASSWORD",db.getPassword())
    .withEnv("LEGACY_TELEGRAM_USER_ID","4242").withEnv("MINI_APP_ALLOWED_USER_IDS",fail?"9999":"4242")
    .withEnv("TELEGRAM_BOT_TOKEN",TelegramInitDataFixture.BOT_TOKEN)
    .withEnv("SPRING_APPLICATION_JSON",runtimeConfiguration(recovery))
    .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/health").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(90)));
   if(!recovery)container.withEnv("SPRING_PROFILES_ACTIVE","normal").withEnv("IDENTITY_API_ENABLED","true")
    .withEnv("IDENTITY_REGISTRATION_ENABLED","true").withEnv("IDENTITY_BETA_ALLOWED_USER_IDS","9002,9003")
    .withEnv("MINI_APP_API_ENABLED","true").withEnv("JOBPILOT_SCHEDULING_ENABLED","true")
    .withEnv("JOB_FETCH_CRON","-").withEnv("DAILY_DIGEST_CRON","-")
    .withEnv("TELEGRAM_BOT_ENABLED","false").withEnv("TELEGRAM_COMMANDS_ENABLED","false");
   try {
    container.start();
    check(container.getContainerInfo().getImageId().equals(image),"running "+name+" uses saved manifest image ID");
    base="http://"+container.getHost()+":"+container.getMappedPort(8080);
   }catch(Exception ex){Files.writeString(DIR.resolve(name+".log"),container.getLogs());container.close();throw ex;}
  }
  public void close()throws Exception{Files.writeString(DIR.resolve(name+".log"),container.getLogs());container.close();}
 }
 static JsonNode profile(String url,String token,long expected,String key,String title)throws Exception {
  return ok(url,"PUT","/api/mini-app/v2/me/search-profile",token,JSON.writeValueAsString(Map.of("expectedRevision",expected,"requestId",key,
    "profile",Map.of("desiredTitles",List.of(title),"targetRegions",List.of("Romania"),"workFormats",List.of("REMOTE")))));
 }
 public static void main(String[] args)throws Exception {
  if(args.length!=3)throw new IllegalArgumentException("Usage: RecoveryImageRehearsal <V19 image manifest> <new private evidence directory> <preserved V18 manifest>");
  DIR=Path.of(args[1]);Files.createDirectory(DIR);Files.setPosixFilePermissions(DIR,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
  var manifest=JSON.readTree(Files.readString(Path.of(args[0])));image=manifest.path("image_id").asText();
  check(manifest.path("schema").asInt()==19&&manifest.path("default_profile").asText().equals("recovery"),"manifest pins V19 default recovery");
  try(var network=Network.newNetwork();var db=new PostgreSQLContainer<>("postgres:16-alpine").withNetwork(network).withNetworkAliases("recovery-db")) {
   db.start();
   Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).target("13").load().migrate();
   String a,b,owner,undo;long app;
   Map<String,List<List<String>>> before,catalogueBefore;
   List<List<String>> deliveries,botState,legacyApps,legacyHistory;
   try(var c=connection(db)) {LegacyMigrationFixture.seed(c);}
   try(var normal=new App(network,db,false,false,"image-normal" );var c=connection(db)) {
    check(Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).load().info().current().getVersion().toString().equals("19"),"actual image migrates synthetic V13 through V19");
    check(call(normal.base,"GET","/mini-app/",null,null,null).statusCode()==200,"packaged API frontend served by image");
    owner=login(normal.base,4242);a=login(normal.base,9002);b=login(normal.base,9003);
    profile(normal.base,a,0,"image-profile-a1","Backend engineer");
    profile(normal.base,a,1,"image-profile-a2","Platform engineer");
    profile(normal.base,b,0,"image-profile-b1","Designer");
    profile(normal.base,b,1,"image-profile-b2","Product designer");
    check(ok(normal.base,"GET","/api/mini-app/v2/me",a,null).path("accountState").asText().equals("READY"),"actual image derives onboarding from confirmed profile");
    change(normal.base,owner,103,"SAVED","image-owner-save");
    change(normal.base,a,103,"SAVED","image-a-save");change(normal.base,a,103,"APPLIED","image-a-apply");
    undo=change(normal.base,b,103,"DISMISSED","image-b-dismiss").path("undoToken").asText();
    change(normal.base,b,102,"SAVED","image-b-save");
    var applied=change(normal.base,b,102,"APPLIED","image-b-apply");
    ok(normal.base,"POST","/api/mini-app/v2/undo",b,JSON.writeValueAsString(Map.of("mutationId","image-b-undo","undoToken",applied.path("undoToken").asText())));
    app=ok(normal.base,"GET","/api/mini-app/v2/jobs/103/application",a,null).path("applicationId").asLong();
    ok(normal.base,"PUT","/api/mini-app/v2/applications/"+app+"/notes",a,"{\"notes\":\"PRIVATE_IMAGE_NOTE\"}");
    matchingReady(normal.base,a);matchingReady(normal.base,b);
    check(!ok(normal.base,"GET","/api/mini-app/v2/jobs/103",a,null).path("match").isNull(),"actual saved image computes current candidate-scoped matches from stored catalogue without scrape");
    catalogueBefore=catalogueRows(c);
    before=beta(c);deliveries=rows(c,"telegram_job_delivery","");botState=rows(c,"telegram_bot_state","");
    legacyApps=rows(c,"applications","where id in (201,202)");legacyHistory=rows(c,"application_status_history","where id in (301,302)");
    check(snapshot(normal.base,a).path("workflowCounts").path("applied").asInt()==1&&snapshot(normal.base,b).path("saved").path("total").asInt()==1,"normal image preserves independent apply/dismiss/save/undo");
   }
   String v19Image=image;
   image=JSON.readTree(Files.readString(Path.of(args[2]))).path("image_id").asText();
   boolean oldRefused=false;
   try(var old=new App(network,db,true,false,"v18-image-refuses-v19")){throw new AssertionError("preserved V18 image accepted V19");}
   catch(org.testcontainers.containers.ContainerLaunchException expected){oldRefused=true;}
   finally{image=v19Image;}
   check(oldRefused && Files.readString(DIR.resolve("v18-image-refuses-v19.log")).contains("19"),"preserved actual V18 image refuses committed V19 without deleting user data");
   boolean failed=false;try(var bad=new App(network,db,false,true,"image-failed-release")) {throw new AssertionError("bad owner configuration unexpectedly started");}catch(org.testcontainers.containers.ContainerLaunchException expected){failed=true;}
   check(failed&&Files.readString(DIR.resolve("image-failed-release.log")).toLowerCase().contains("legacy"),"failed release startup stopped before recovery");
   try(var recovery=new App(network,db,true,false,"image-recovery");var c=connection(db)) {
    check(recovery.container.getLogs().contains("recovery"),"default image profile is recovery");
    check(beta(c).equals(before),"recovery startup preserves every beta column and timestamp, including match history/work state");
    Thread.sleep(1500);
    check(catalogueRows(c).equals(catalogueBefore)&&beta(c).equals(before),"V19 recovery performs no background matching or catalogue bootstrap");
    check(call(recovery.base,"GET","/api/mini-app/v2/snapshot","Authorization","Bearer "+a,null).statusCode()==401,"recovery denies existing beta sessions");
    check(call(recovery.base,"POST","/api/mini-app/v2/auth/telegram","X-Telegram-Init-Data",launch(9003),null).statusCode()==403,"recovery denies fresh beta admission");
    check(call(recovery.base,"GET","/api/mini-app/v2/me/search-profile","Authorization","Bearer "+a,null).statusCode()==401,"V19 recovery denies beta profile access");
    String freshOwner=login(recovery.base,4242);
    check(ok(recovery.base,"GET","/api/mini-app/v2/me/search-profile",freshOwner,null).path("activeRevision").asLong()==0,"legacy owner requires no search profile to operate");
    profile(recovery.base,freshOwner,0,"image-owner-profile","Owner preference");
    check(snapshot(recovery.base,owner).path("applications").path("total").asInt()==3,"existing owner session still reads own state after beta writes");
    check(call(recovery.base,"PUT","/api/mini-app/v1/jobs/103/workflow","X-Telegram-Init-Data",launch(4242),"{\"status\":\"APPLIED\",\"mutationId\":\"image-recovery-owner\"}").statusCode()==200,"owner v1 mutates during image recovery");
    check(!snapshot(recovery.base,freshOwner).toString().contains("PRIVATE_IMAGE_NOTE"),"owner snapshot does not expose beta notes");
    check(call(recovery.base,"GET","/api/mini-app/v2/applications/"+app,"Authorization","Bearer "+freshOwner,null).statusCode()==404,"owner cannot enumerate beta application");
    check(call(recovery.base,"POST","/api/mini-app/v2/undo","Authorization","Bearer "+freshOwner,JSON.writeValueAsString(Map.of("mutationId","image-foreign-undo","undoToken",undo))).statusCode()==409,"owner cannot replay beta undo");
    check(call(recovery.base,"GET","/internal/v1/applications",null,null,null).statusCode()==403,"image recovery denies public internal application route");
    check(beta(c).equals(before)&&rows(c,"applications","where id in (201,202)").equals(legacyApps)&&rows(c,"application_status_history","where id in (301,302)").equals(legacyHistory),"owner recovery preserves beta IDs/history/ledger and original legacy rows");
    check(rows(c,"telegram_job_delivery","").equals(deliveries)&&rows(c,"telegram_bot_state","").equals(botState),"recovery image leaves Telegram delivery/offset records unchanged");
   }
   try(var normal=new App(network,db,false,false,"image-resumed");var c=connection(db)) {
    check(beta(c).equals(before),"normal restart retains exact beta rows before new authentication");
    String freshA=login(normal.base,9002),freshB=login(normal.base,9003);
    check(ok(normal.base,"GET","/api/mini-app/v2/me/search-profile",freshA,null).path("profile").path("desiredTitles").get(0).asText().equals("Platform engineer"),"resumed beta A retains confirmed preferences");
    check(profile(normal.base,freshB,1,"image-profile-b2","Product designer").path("activeRevision").asLong()==2,"resumed beta B profile retry retains idempotency");
    check(snapshot(normal.base,freshA).path("workflowCounts").path("applied").asInt()==1&&snapshot(normal.base,freshB).path("saved").path("total").asInt()==1&&snapshot(normal.base,freshB).path("workflowCounts").path("dismissed").asInt()==1,"both beta users resume previous workflows");
    check(change(normal.base,freshB,103,"DISMISSED","image-b-dismiss").path("replayed").asBoolean(),"resumed mutation retains same-account idempotency");
    change(normal.base,freshA,101,"SAVED","image-resumed-a-save");
    check(snapshot(normal.base,freshB).path("saved").path("total").asInt()==1,"resumed beta mutation remains isolated");
   }
   // Run unstubbed browser onboarding/workflows against a separate synthetic database.
   try(var browserDb=new PostgreSQLContainer<>("postgres:16-alpine").withNetwork(network).withNetworkAliases("browser-db")) {
    browserDb.start();
    Flyway.configure().dataSource(browserDb.getJdbcUrl(),browserDb.getUsername(),browserDb.getPassword()).target("13").load().migrate();
    try(var c=connection(browserDb)){LegacyMigrationFixture.seed(c);try(var st=c.createStatement()) {
      st.execute("update jobs set title='Browser shared vacancy' where id=103");
      st.execute("update jobs set status='EXPIRED' where id in (101,102)");
    }}
    try(var runtime=new App(network,browserDb,false,false,"image-browser")) {
      var process=new ProcessBuilder("node","tests/real-backend-smoke.mjs",runtime.base,DIR.resolve("recommended-mobile.png").toString())
        .directory(Path.of("mini-app").toFile()).redirectErrorStream(true).redirectOutput(DIR.resolve("real-browser.log").toFile()).start();
      if(!process.waitFor(120,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Browser rehearsal timed out");}
      check(process.exitValue()==0,"real browsers onboard two users, persist/reload preferences and keep workflows isolated through actual V19 image/PostgreSQL");
    }
   }
   var migration=Files.createTempDirectory("image-future-migration-");
   try {
    Files.writeString(migration.resolve("V20__image_future_probe.sql"),"create table image_future_probe(id bigint);");
    Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).locations("classpath:db/migration","filesystem:"+migration).load().migrate();
    boolean refused=false;try(var future=new App(network,db,true,false,"image-future-refused")){throw new AssertionError("future schema started");}catch(org.testcontainers.containers.ContainerLaunchException expected){refused=true;}
    check(refused&&Files.readString(DIR.resolve("image-future-refused.log")).contains("20"),"actual saved recovery image refuses committed V20 startup");
   }finally{Files.deleteIfExists(migration.resolve("V20__image_future_probe.sql"));Files.deleteIfExists(migration);}
  }
  Files.writeString(DIR.resolve("image-rehearsal.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("image_id",image,"passed",passed,"failed",0,"synthetic_disposable_only",true))+"\n");
  System.out.println("IMAGE REHEARSAL: "+passed+" passed, 0 failed; image="+image);
 }
}
