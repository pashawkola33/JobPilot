package com.jobpilot.identity;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryImageHarnessTest {
 @TempDir Path directory;
 Path fixture(int schema)throws Exception {
  var json=RecoveryImageRehearsal.JSON;
  ObjectNode proof=json.createObjectNode().put("schema",schema).put("verification","passed").put("frontend_mode","api").put("source_sha256","synthetic-source");
  ObjectNode files=proof.putObject("files");
  for(String name:List.of("source.tar","source-files.json","jobpilot.jar","Dockerfile.recovery","backend-verify.log","required-postgres-suites.log","frontend-tests.log","frontend-api-build.log","frontend-typecheck.log","frontend-lint.log","npm-ci.log","artifact-self-tests.log","integration-guard-self-tests.log")) {
   Files.writeString(directory.resolve(name),"synthetic "+name);files.put(name,RecoveryImageRehearsal.sha(directory.resolve(name)));
  }
  Files.writeString(directory.resolve("verification.json"),json.writeValueAsString(proof));Files.writeString(directory.resolve("image.tar"),"synthetic saved image");
  var manifest=json.createObjectNode().put("schema",schema).put("default_profile","recovery").put("base_image",RecoveryImageRehearsal.PINNED_BASE).put("platform","linux/amd64")
   .put("source_sha256","synthetic-source").put("jar_sha256",files.path("jobpilot.jar").asText()).put("verification_sha256",RecoveryImageRehearsal.sha(directory.resolve("verification.json")))
   .put("archive","image.tar").put("archive_sha256",RecoveryImageRehearsal.sha(directory.resolve("image.tar")));
  Path path=directory.resolve("image-manifest.json");Files.writeString(path,json.writeValueAsString(manifest));return path;
 }
 @Test void schemaRefusalRequiresExplicitVersionedFlywayOrRuntimeEvidence() {
  assertThat(RecoveryImageRehearsal.schemaRefusal("Validate failed: Migrations have failed validation\nDetected applied migration not resolved locally: 20.",20)).isTrue();
  assertThat(RecoveryImageRehearsal.schemaRefusal("Candidate-aware runtime requires validated schema V20; incompatible migration V21 state=Future",21)).isTrue();
  assertThat(RecoveryImageRehearsal.schemaRefusal("Image not found 20",20)).isFalse();
  assertThat(RecoveryImageRehearsal.schemaRefusal("Read timed out after 21 seconds",21)).isFalse();
  assertThat(RecoveryImageRehearsal.schemaRefusal("Detected applied migration not resolved locally: 21.",20)).isFalse();
 }
 @Test void validV20AndRetainedV19ProofsPass()throws Exception {
  assertThat(RecoveryImageRehearsal.manifest(fixture(20),20).path("schema").asInt()).isEqualTo(20);
  assertThat(RecoveryImageRehearsal.manifest(fixture(19),19).path("schema").asInt()).isEqualTo(19);
 }
 @Test void changedArchiveIsRejectedBeforeImageBoot()throws Exception {
  Path path=fixture(20);Files.writeString(directory.resolve("image.tar"),"changed");
  assertThatThrownBy(()->RecoveryImageRehearsal.manifest(path,20)).isInstanceOf(AssertionError.class).hasMessageContaining("archive hash");
 }
 @Test void changedEvidenceIsRejectedBeforeImageBoot()throws Exception {
  Path path=fixture(20);Files.writeString(directory.resolve("jobpilot.jar"),"changed");
  assertThatThrownBy(()->RecoveryImageRehearsal.manifest(path,20)).isInstanceOf(AssertionError.class).hasMessageContaining("evidence hash");
 }
 @Test void changedProofIsRejectedBeforeImageBoot()throws Exception {
  Path path=fixture(20);Files.writeString(directory.resolve("verification.json"),"{}");
  assertThatThrownBy(()->RecoveryImageRehearsal.manifest(path,20)).isInstanceOf(AssertionError.class).hasMessageContaining("verification hash");
 }
 @Test void wrongGenerationCannotImpersonateNewArtifact()throws Exception {
  Path path=fixture(19);
  assertThatThrownBy(()->RecoveryImageRehearsal.manifest(path,20)).isInstanceOf(AssertionError.class).hasMessageContaining("V20");
 }
}
