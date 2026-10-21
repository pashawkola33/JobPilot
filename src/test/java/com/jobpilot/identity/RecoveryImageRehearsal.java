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
 static final String PINNED_BASE="eclipse-temurin@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555";
 static final long POLL_KEY=Long.parseUnsignedLong(com.jobpilot.common.Hashing.sha256("jobpilot-poller\0"+TelegramInitDataFixture.BOT_TOKEN).substring(0,16),16);
 static final Map<String,Connection> POLL_HOLDS=new HashMap<>();
 static String sha(Path path)throws Exception {
  var digest=java.security.MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)){byte[] bytes=new byte[1024*1024];int n;while((n=input.read(bytes))!=-1)digest.update(bytes,0,n);}return HexFormat.of().formatHex(digest.digest());
 }
 static JsonNode manifest(Path path,int schema)throws Exception {
  var manifest=JSON.readTree(Files.readString(path));Path directory=path.toAbsolutePath().getParent();
  check(manifest.path("schema").asInt()==schema&&manifest.path("default_profile").asText().equals("recovery"),"manifest pins V"+schema+" default recovery");
  check(manifest.path("base_image").asText().equals(PINNED_BASE)&&manifest.path("platform").asText().equals("linux/amd64"),"V"+schema+" uses pinned existing base/platform");
  Path proofPath=directory.resolve("verification.json");check(sha(proofPath).equals(manifest.path("verification_sha256").asText()),"V"+schema+" verification hash matches manifest");
  var proof=JSON.readTree(Files.readString(proofPath));
  check(proof.path("schema").asInt()==schema&&proof.path("verification").asText().equals("passed")&&proof.path("frontend_mode").asText().equals("api"),"V"+schema+" proof records verified API artifact");
  var files=proof.path("files");
  for(String required:List.of("source.tar","source-files.json","jobpilot.jar","Dockerfile.recovery","backend-verify.log","required-postgres-suites.log","frontend-tests.log","frontend-api-build.log","frontend-typecheck.log","frontend-lint.log","npm-ci.log"))check(files.has(required),"V"+schema+" proof retains "+required);
  if(schema==20)for(String required:List.of("artifact-self-tests.log","integration-guard-self-tests.log"))check(files.has(required),"V20 proof retains "+required);
  var entries=files.fields();while(entries.hasNext()){var entry=entries.next();String name=entry.getKey();check(Path.of(name).getFileName().toString().equals(name)&&Files.isRegularFile(directory.resolve(name),LinkOption.NOFOLLOW_LINKS)&&sha(directory.resolve(name)).equals(entry.getValue().asText()),"V"+schema+" evidence hash matches "+name);}
  check(proof.path("source_sha256").asText().equals(manifest.path("source_sha256").asText())&&files.path("jobpilot.jar").asText().equals(manifest.path("jar_sha256").asText()),"V"+schema+" source/JAR agree across proof and manifest");
  String archive=manifest.path("archive").asText();check(Path.of(archive).getFileName().toString().equals(archive)&&sha(directory.resolve(archive)).equals(manifest.path("archive_sha256").asText()),"V"+schema+" saved archive hash is unchanged");
  return manifest;
 }
 static final class PollHold implements AutoCloseable {
  final PostgreSQLContainer<?> db;final Connection held;
  PollHold(PostgreSQLContainer<?> db)throws Exception {this.db=db;held=connection(db);try(var q=held.prepareStatement("select pg_try_advisory_lock(?)")){q.setLong(1,POLL_KEY);try(var rs=q.executeQuery()){rs.next();check(rs.getBoolean(1),"harness acquired actual synthetic per-bot SESSION poll lease before image boot");}}POLL_HOLDS.put(db.getJdbcUrl(),held);assertPollHeld(db);}
  public void close()throws Exception {POLL_HOLDS.remove(db.getJdbcUrl());held.close();}
 }
 static void assertPollHeld(PostgreSQLContainer<?> db)throws Exception {
  Connection held=POLL_HOLDS.get(db.getJdbcUrl());check(held!=null&&!held.isClosed()&&held.isValid(2),"harness poll lease owner remains live");
  try(var q=held.createStatement();var rs=q.executeQuery("select count(*) from pg_locks where pid=pg_backend_pid() and locktype='advisory' and granted")){rs.next();check(rs.getInt(1)==1,"harness session owns one poll advisory lock");}
  var data=new org.springframework.jdbc.datasource.DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword());
  var env=new org.springframework.mock.env.MockEnvironment().withProperty("jobpilot.telegram.bot-token",TelegramInitDataFixture.BOT_TOKEN);
  check(new com.jobpilot.telegram.polling.TelegramPollingLease(new org.springframework.jdbc.core.JdbcTemplate(data),env).acquire()==null,"actual polling lease protocol refuses competing app connection before HTTP");
 }
 static boolean schemaRefusal(String logs,int version){return logs.contains("incompatible migration V"+version+" ")||logs.contains("Detected applied migration not resolved locally: "+version+".");}
 static void notificationCheckpoint(Connection c,NotificationRecoveryFixture.Snapshot expected,String name)throws Exception {
  var actual=NotificationRecoveryFixture.snapshot(c);Files.writeString(DIR.resolve(name+"-notifications.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(actual)+"\n");
  check(actual.equals(expected),name+" preserves every notification table/column and delivery-check sequence including SENDING, process nonce and fair ordinal");
 }
 static void settleMatching(Connection c)throws Exception {
  long end=System.nanoTime()+Duration.ofSeconds(30).toNanos();
  while(System.nanoTime()<end){if(NotificationRecoveryFixture.scalar(c,"select count(*) from candidate_matching_state where candidate_id in (select candidate_id from telegram_identities where telegram_user_id in (9002,9003)) and (phase<>'READY' or event_cursor<>(select change_revision from matching_catalogue_state where id=1))")==0)return;Thread.sleep(100);}throw new AssertionError("matching did not settle before preservation snapshot");
 }

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
  values.put("jobpilot.notifications.enabled",!recovery);
  values.put("jobpilot.telegram.bot-token",TelegramInitDataFixture.BOT_TOKEN);
  values.put("jobpilot.telegram.bot-username","JobPilotTestBot");
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
   this.name=name;assertPollHeld(db);
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
    check(container.execInContainer("sh","-c","test ! -e /run/jobpilot-notifications/approval").getExitCode()==0,name+" has no external delivery approval file");
    base="http://"+container.getHost()+":"+container.getMappedPort(8080);
   }catch(Exception ex){Files.writeString(DIR.resolve(name+".log"),container.getLogs());container.close();throw ex;}
  }
  public void close()throws Exception{String logs=container.getLogs();Files.writeString(DIR.resolve(name+".log"),logs);container.close();check(!logs.contains("poll_or_transport_failure"),name+" has no poll transport failure events while synthetic session lease held");}
 }
 static JsonNode profile(String url,String token,long expected,String key,String title)throws Exception {
  return ok(url,"PUT","/api/mini-app/v2/me/search-profile",token,JSON.writeValueAsString(Map.of("expectedRevision",expected,"requestId",key,
    "profile",Map.of("desiredTitles",List.of(title),"targetRegions",List.of("Romania"),"workFormats",List.of("REMOTE")))));
 }
 static String notificationUpdate(boolean immediate,String timezone)throws Exception {
  return JSON.writeValueAsString(Map.of("expectedRevision",0,"requestId",UUID.randomUUID().toString(),"enabled",true,"paused",false,"immediate",immediate,"digest",!immediate,"minimumScore",88,"timezone",timezone));
 }
 public static void main(String[] args)throws Exception {
  if(args.length!=3)throw new IllegalArgumentException("Usage: RecoveryImageRehearsal <V20 image manifest> <new private evidence directory> <preserved V19 manifest>");
  DIR=Path.of(args[1]);Files.createDirectory(DIR);Files.setPosixFilePermissions(DIR,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
  var manifest=manifest(Path.of(args[0]),20);var preserved=manifest(Path.of(args[2]),19);image=manifest.path("image_id").asText();
  try(var network=Network.newNetwork();var db=new PostgreSQLContainer<>("postgres:16-alpine").withNetwork(network).withNetworkAliases("recovery-db")) {
   db.start();
   try(var pollHold=new PollHold(db)) {
   Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).target("13").load().migrate();
   String a,b,owner,undo;long app;
   Map<String,List<List<String>>> before,catalogueBefore;NotificationRecoveryFixture.Snapshot notifications;
   List<List<String>> deliveries,botState,legacyApps,legacyHistory;
   try(var c=connection(db)) {LegacyMigrationFixture.seed(c);}
   try(var normal=new App(network,db,false,false,"image-normal" );var c=connection(db)) {
    check(Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).load().info().current().getVersion().toString().equals("20"),"actual image migrates synthetic V13 through V20");
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
    ok(normal.base,"PUT","/api/mini-app/v2/me/notifications",a,notificationUpdate(true,"UTC"));
    ok(normal.base,"PUT","/api/mini-app/v2/me/notifications",b,notificationUpdate(false,"Europe/Bucharest"));
    ok(normal.base,"POST","/api/mini-app/v2/me/notifications/activation",a,null);
    NotificationRecoveryFixture.seed(c,NotificationRecoveryFixture.candidate(c,9002));settleMatching(c);
    notifications=NotificationRecoveryFixture.snapshot(c);notificationCheckpoint(c,notifications,"before-failed-release");
    Thread.sleep(2200);notificationCheckpoint(c,notifications,"initial-normal-disarmed");
    check(ok(normal.base,"GET","/api/mini-app/v2/me/notifications",a,null).path("pauseReasons").toString().contains("DISPATCH_DISARMED"),"enabled normal image is DISARMED without external approval");
    catalogueBefore=catalogueRows(c);
    before=beta(c);deliveries=rows(c,"telegram_job_delivery","");botState=rows(c,"telegram_bot_state","");
    legacyApps=rows(c,"applications","where id in (201,202)");legacyHistory=rows(c,"application_status_history","where id in (301,302)");
    check(snapshot(normal.base,a).path("workflowCounts").path("applied").asInt()==1&&snapshot(normal.base,b).path("saved").path("total").asInt()==1,"normal image preserves independent apply/dismiss/save/undo");
   }
   String v20Image=image;
   image=preserved.path("image_id").asText();
   boolean oldRefused=false;
   try(var old=new App(network,db,true,false,"v19-image-refuses-v20")){throw new AssertionError("preserved V19 image accepted V20");}
   catch(org.testcontainers.containers.ContainerLaunchException expected){oldRefused=true;}
   finally{image=v20Image;}
   check(oldRefused && schemaRefusal(Files.readString(DIR.resolve("v19-image-refuses-v20.log")),20),"preserved actual V19 image refuses committed V20 for actual schema incompatibility");
   try(var c=connection(db)){notificationCheckpoint(c,notifications,"after-v19-refusal");check(beta(c).equals(before),"old V19 refusal preserves complete beta rows");}
   boolean failed=false;try(var bad=new App(network,db,false,true,"image-failed-release")) {throw new AssertionError("bad owner configuration unexpectedly started");}catch(org.testcontainers.containers.ContainerLaunchException expected){failed=true;}
   check(failed&&Files.readString(DIR.resolve("image-failed-release.log")).toLowerCase().contains("legacy"),"failed release startup stopped before recovery");
   try(var c=connection(db)){notificationCheckpoint(c,notifications,"after-failed-release");}
   try(var recovery=new App(network,db,true,false,"image-recovery");var c=connection(db)) {
    notificationCheckpoint(c,notifications,"recovery-startup");
    check(recovery.container.getLogs().contains("recovery"),"default image profile is recovery");
    check(beta(c).equals(before),"recovery startup preserves every beta column and timestamp, including match history/work state");
    Thread.sleep(1500);
    check(catalogueRows(c).equals(catalogueBefore)&&beta(c).equals(before),"V20 recovery performs no background matching or catalogue bootstrap");
    check(call(recovery.base,"GET","/api/mini-app/v2/snapshot","Authorization","Bearer "+a,null).statusCode()==401,"recovery denies existing beta sessions");
    check(call(recovery.base,"POST","/api/mini-app/v2/auth/telegram","X-Telegram-Init-Data",launch(9003),null).statusCode()==403,"recovery denies fresh beta admission");
    check(call(recovery.base,"GET","/api/mini-app/v2/me/search-profile","Authorization","Bearer "+a,null).statusCode()==401,"V20 recovery denies beta profile access");
    String freshOwner=login(recovery.base,4242);
    check(ok(recovery.base,"GET","/api/mini-app/v2/me/notifications",freshOwner,null).path("connectionStatus").asText().equals("LEGACY_MANAGED"),"recovery owner notification settings remain legacy managed");
    check(ok(recovery.base,"GET","/api/mini-app/v2/me/search-profile",freshOwner,null).path("activeRevision").asLong()==0,"legacy owner requires no search profile to operate");
    profile(recovery.base,freshOwner,0,"image-owner-profile","Owner preference");
    check(snapshot(recovery.base,owner).path("applications").path("total").asInt()==3,"existing owner session still reads own state after beta writes");
    check(call(recovery.base,"PUT","/api/mini-app/v1/jobs/103/workflow","X-Telegram-Init-Data",launch(4242),"{\"status\":\"APPLIED\",\"mutationId\":\"image-recovery-owner\"}").statusCode()==200,"owner v1 mutates during image recovery");
    check(!snapshot(recovery.base,freshOwner).toString().contains("PRIVATE_IMAGE_NOTE"),"owner snapshot does not expose beta notes");
    check(call(recovery.base,"GET","/api/mini-app/v2/applications/"+app,"Authorization","Bearer "+freshOwner,null).statusCode()==404,"owner cannot enumerate beta application");
    check(call(recovery.base,"POST","/api/mini-app/v2/undo","Authorization","Bearer "+freshOwner,JSON.writeValueAsString(Map.of("mutationId","image-foreign-undo","undoToken",undo))).statusCode()==409,"owner cannot replay beta undo");
    check(call(recovery.base,"GET","/internal/v1/applications",null,null,null).statusCode()==403,"image recovery denies public internal application route");
    check(beta(c).equals(before)&&rows(c,"applications","where id in (201,202)").equals(legacyApps)&&rows(c,"application_status_history","where id in (301,302)").equals(legacyHistory),"owner recovery preserves beta IDs/history/ledger and original legacy rows");
    notificationCheckpoint(c,notifications,"recovery-after-owner-operations");
    check(rows(c,"telegram_job_delivery","").equals(deliveries)&&rows(c,"telegram_bot_state","").equals(botState),"recovery image leaves Telegram delivery/offset records unchanged");
   }
   try(var normal=new App(network,db,false,false,"image-resumed");var c=connection(db)) {
    check(beta(c).equals(before),"normal restart retains exact beta rows before new authentication");
    Thread.sleep(2200);notificationCheckpoint(c,notifications,"fresh-normal-disarmed-before-auth");
    NotificationRecoveryFixture.assertDeliverablePending(c,NotificationRecoveryFixture.candidate(c,9002));
    check(rows(c,"telegram_bot_state","").equals(botState),"enabled normal poller preserves bot state under held actual session lease");
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
    try(var browserPollHold=new PollHold(browserDb);var runtime=new App(network,browserDb,false,false,"image-browser")) {
      var process=new ProcessBuilder("node","tests/real-backend-smoke.mjs",runtime.base,DIR.resolve("recommended-mobile.png").toString())
        .directory(Path.of("mini-app").toFile()).redirectErrorStream(true).redirectOutput(DIR.resolve("real-browser.log").toFile()).start();
      if(!process.waitFor(120,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Browser rehearsal timed out");}
      try(var c=connection(browserDb)) {
       check(NotificationRecoveryFixture.scalar(c,"select count(*) from notification_preferences p join telegram_identities i on i.candidate_id=p.candidate_id where i.telegram_user_id=9002 and not p.enabled and not p.paused and p.immediate and p.digest and p.minimum_score=91 and p.timezone='Europe/Bucharest' and p.quiet_start='22:30' and p.quiet_end='07:15'")==1,"browser A persisted exact advanced preferences and disconnect opt-out");
       check(NotificationRecoveryFixture.scalar(c,"select count(*) from notification_preferences p join telegram_identities i on i.candidate_id=p.candidate_id where i.telegram_user_id=9003 and p.enabled and not p.paused and not p.immediate and p.digest and p.minimum_score=87 and p.timezone='Asia/Kathmandu' and p.quiet_start is null and p.quiet_end is null")==1,"browser B retains independent digest/timezone/score preferences");
       check(NotificationRecoveryFixture.scalar(c,"select count(*) from notification_preference_revisions")==4,"real browser mutations retain durable preference revisions");
      }
      check(process.exitValue()==0,"real browsers onboard two users, persist/reload preferences and keep workflows isolated through actual V20 image/PostgreSQL");
    }
   }
   Map<String,List<List<String>>> beforeFutureBeta,beforeFutureCatalogue;
   try(var c=connection(db)){beforeFutureBeta=beta(c);beforeFutureCatalogue=catalogueRows(c);}
   var migration=Files.createTempDirectory("image-future-migration-");
   try {
    Files.writeString(migration.resolve("V21__image_future_probe.sql"),"create table image_future_probe(id bigint);");
    Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).locations("classpath:db/migration","filesystem:"+migration).load().migrate();
    boolean refused=false;try(var future=new App(network,db,true,false,"image-future-refused")){throw new AssertionError("future schema started");}catch(org.testcontainers.containers.ContainerLaunchException expected){refused=true;}
    try(var c=connection(db)){notificationCheckpoint(c,notifications,"after-v21-refusal");check(beta(c).equals(beforeFutureBeta)&&catalogueRows(c).equals(beforeFutureCatalogue),"future V21 refusal preserves current beta and matching data");}
    check(refused&&schemaRefusal(Files.readString(DIR.resolve("image-future-refused.log")),21),"actual saved recovery image refuses committed V21 for actual schema incompatibility");
   }finally{Files.deleteIfExists(migration.resolve("V21__image_future_probe.sql"));Files.deleteIfExists(migration);}
   }
  }
  Files.writeString(DIR.resolve("image-rehearsal.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("image_id",image,"passed",passed,"failed",0,"synthetic_disposable_only",true))+"\n");
  System.out.println("IMAGE REHEARSAL: "+passed+" passed, 0 failed; image="+image);
 }
}
