#!/usr/bin/env python3
"""Fast real-filesystem checks; does not build, start Docker or access a database."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).with_name("build-recovery-artifact.py")
SPEC = importlib.util.spec_from_file_location("recovery_build", SCRIPT)
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)


class RecoveryArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "repository"
        self.root.mkdir()
        subprocess.run(["git", "init", "--quiet", str(self.root)], check=True)
        (self.root / ".gitignore").write_text("target/\n")
        (self.root / "tracked.txt").write_text("original\n")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)

    def identity(self):
        return BUILD.source_inventory(self.root)[1]

    def test_hash_includes_uncommitted_tracked_and_untracked_files(self):
        original = self.identity()
        (self.root / "tracked.txt").write_text("dirty source\n")
        dirty = self.identity()
        self.assertNotEqual(original, dirty)
        (self.root / "new-source.java").write_text("untracked source\n")
        self.assertNotEqual(dirty, self.identity())

    def test_deletion_and_executable_bit_change_identity(self):
        original = self.identity()
        (self.root / "tracked.txt").chmod(0o755)
        self.assertNotEqual(original, self.identity())
        (self.root / "tracked.txt").unlink()
        self.assertNotEqual(original, self.identity())

    def test_environment_files_and_private_keys_are_excluded(self):
        original = self.identity()
        (self.root / ".env").write_text("SYNTHETIC_SECRET=do-not-copy")
        (self.root / ".env.local").write_text("SYNTHETIC_SECRET=do-not-copy")
        (self.root / "private.pem").write_text("synthetic private key")
        self.assertEqual(original, self.identity())

    def test_ignored_outputs_do_not_change_identity(self):
        original = self.identity()
        (self.root / "target").mkdir()
        (self.root / "target/jobpilot.jar").write_bytes(b"generated")
        self.assertEqual(original, self.identity())
        self.assertEqual(self.identity(), self.identity())

    def test_frontend_modes_override_a_callers_api_environment(self):
        previous = os.environ.get("VITE_JOBPILOT_MODE")
        os.environ["VITE_JOBPILOT_MODE"] = "api"
        try:
            tests = BUILD.frontend_environment("mock")
            production = BUILD.frontend_environment("api")
            self.assertEqual(tests["VITE_JOBPILOT_MODE"], "mock")
            self.assertEqual(production["VITE_JOBPILOT_MODE"], "api")
            self.assertEqual(tests["CI"], "true")
            self.assertEqual(os.environ["VITE_JOBPILOT_MODE"], "api")
        finally:
            if previous is None:
                del os.environ["VITE_JOBPILOT_MODE"]
            else:
                os.environ["VITE_JOBPILOT_MODE"] = previous

    def test_source_symlink_is_rejected(self):
        (self.root / "linked-source").symlink_to(self.root / "tracked.txt")
        with self.assertRaisesRegex(ValueError, "symlinks"):
            self.identity()

    def test_mutable_or_arbitrary_runtime_base_is_rejected(self):
        for value in ("eclipse-temurin:21-jre-alpine", "eclipse-temurin@sha256:abc",
                      "other@sha256:" + "a" * 64, "eclipse-temurin:17@sha256:" + "a" * 64):
            with self.assertRaises(argparse.ArgumentTypeError):
                BUILD.pinned_base(value)
        digest = "eclipse-temurin@sha256:" + "a" * 64
        self.assertEqual(digest, BUILD.pinned_base(digest))

    def test_existing_or_in_checkout_output_is_rejected_before_any_build(self):
        for output in (self.root, self.root / "artifact"):
            with self.assertRaisesRegex(ValueError, "new directory outside"):
                BUILD.verify(self.root, output)

    def test_wrong_or_incomplete_verification_is_rejected(self):
        proof = self.root.parent / "proof.json"
        proof.write_text(json.dumps({"format": 1, "schema": 20}))
        with self.assertRaisesRegex(ValueError, "unsupported"):
            BUILD.validate_proof(self.root, proof)
        proof.write_text(json.dumps({"format": 1, "schema": 20, "frontend_mode": "api",
                                     "verification": "passed", "source_sha256": "0" * 64}))
        with self.assertRaisesRegex(ValueError, "source has changed"):
            BUILD.validate_proof(self.root, proof)
        proof.write_text(json.dumps({"format": 1, "schema": 20, "frontend_mode": "api",
                                     "verification": "passed", "source_sha256": self.identity(), "files": {}}))
        with self.assertRaisesRegex(ValueError, "omits required evidence"):
            BUILD.validate_proof(self.root, proof)

    def test_evidence_mutation_and_path_escape_are_rejected(self):
        names = ("source.tar", "source-files.json", "jobpilot.jar", "Dockerfile.recovery",
                 "backend-verify.log", "required-postgres-suites.log", "frontend-tests.log",
                 "artifact-self-tests.log", "integration-guard-self-tests.log",
                 "frontend-api-build.log", "frontend-typecheck.log", "frontend-lint.log", "npm-ci.log")
        evidence = self.root.parent / "evidence"
        evidence.mkdir()
        for name in names:
            (evidence / name).write_text("test evidence\n")
        proof = {"format": 1, "schema": 20, "frontend_mode": "api", "verification": "passed",
                 "source_sha256": self.identity(), "files": {n: BUILD.sha(evidence / n) for n in names}}
        path = evidence / "verification.json"
        path.write_text(json.dumps(proof))
        self.assertEqual(BUILD.validate_proof(self.root, path), proof)
        (evidence / "jobpilot.jar").write_text("changed after verification\n")
        with self.assertRaisesRegex(ValueError, "evidence changed"):
            BUILD.validate_proof(self.root, path)
        proof["files"]["jobpilot.jar"] = BUILD.sha(evidence / "jobpilot.jar")
        proof["files"]["../outside"] = "0" * 64
        path.write_text(json.dumps(proof))
        with self.assertRaisesRegex(ValueError, "unsafe path"):
            BUILD.validate_proof(self.root, path)

    def test_runtime_metadata_identifies_v20_recovery(self):
        dockerfile = SCRIPT.parent.parent / "Dockerfile.recovery"
        source = dockerfile.read_text()
        self.assertIn("JOBPILOT_VERSION=phase5-recovery", source)
        self.assertIn("com.jobpilot.schema.version=20", source)
        self.assertIn("SPRING_PROFILES_ACTIVE=recovery", source)
        self.assertIn("USER jobpilot", source)

    def test_thin_or_old_jar_is_rejected(self):
        jar = self.root / "old.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("BOOT-INF/classes/static/mini-app/index.html", "old")
        with self.assertRaisesRegex(ValueError, "not a candidate-aware recovery fat JAR"):
            BUILD.validate_jar(jar, self.root / "dist")

    def test_v19_proof_cannot_be_reused(self):
        proof = self.root.parent / "v19.json"
        proof.write_text(json.dumps({"format": 1, "schema": 19, "frontend_mode": "api",
                                     "verification": "passed", "source_sha256": self.identity(), "files": {}}))
        with self.assertRaisesRegex(ValueError, "unsupported"):
            BUILD.validate_proof(self.root, proof)

    def test_v20_requirements_and_exact_frontend_are_enforced(self):
        # A complete pre-V20 fat JAR is materially different from a thin JAR.
        required = BUILD.REQUIRED_JAR_ENTRIES
        dist = self.root / "dist"
        dist.mkdir()
        for name in ("index.html", "app.js", "app.css"):
            (dist / name).write_text("verified-" + name)
        def jar_without(missing=None, corrupt=False):
            jar = self.root / "candidate.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                for name in required - ({missing} if missing else set()) - {"BOOT-INF/classes/static/mini-app/index.html"}:
                    archive.writestr(name, "class")
                archive.writestr("BOOT-INF/lib/spring.jar", "library")
                for path in dist.iterdir():
                    archive.writestr("BOOT-INF/classes/static/mini-app/" + path.name,
                                     "wrong" if corrupt else path.read_text())
            return jar
        BUILD.validate_jar(jar_without(), dist)
        v20 = {n for n in required if "V20" in n or "/notifications/" in n or "TelegramPollingLease" in n}
        self.assertGreaterEqual(len(v20), 10)
        for missing in v20:
            with self.subTest(missing=missing), self.assertRaisesRegex(ValueError, "not a candidate-aware"):
                BUILD.validate_jar(jar_without(missing), dist)
        with self.assertRaisesRegex(ValueError, "frontend asset differs"):
            BUILD.validate_jar(jar_without(corrupt=True), dist)

    @unittest.skipUnless(os.environ.get("JOBPILOT_PRESERVED_V19_JAR"), "optional retained V19 artifact input")
    def test_actual_preserved_v19_fat_jar_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "not a candidate-aware"):
            BUILD.validate_jar(Path(os.environ["JOBPILOT_PRESERVED_V19_JAR"]), self.root / "unused-dist")

    def test_cli_rejects_unsafe_inputs_without_running_docker(self):
        for args in (["image"], ["build", "--base-image", "eclipse-temurin:latest"],
                     ["build", "--base-image", "eclipse-temurin@sha256:" + "a" * 64,
                      "--platform", "linux/arm64"]):
            result = subprocess.run([os.sys.executable, str(SCRIPT), *args], capture_output=True)
            self.assertEqual(result.returncode, 2, result.stderr)


if __name__ == "__main__":
    unittest.main()
