#!/usr/bin/env python3
"""Verify a dirty source snapshot and create a local-only V19 recovery image.

No registry login/push, deployment or production connection is performed. Run
`verify` first and `image` afterward, or `build` for both. Output must be outside
the checkout and must not already exist when verification begins.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import zipfile


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def capture(command, cwd):
    return subprocess.check_output(command, cwd=cwd, text=True).strip()


def source_inventory(root):
    paths = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=root
    ).decode().split("\0")
    entries = []
    for name in sorted(set(filter(None, paths))):
        if Path(name).name.startswith(".env") or Path(name).suffix.lower() in (".pem", ".key", ".p12", ".pfx"):
            continue
        path = root / name
        if path.is_symlink():
            raise ValueError(f"source symlinks are not supported: {name}")
        if not path.exists():  # A tracked deletion belongs to this snapshot too.
            continue
        if not path.is_file() or Path(name).is_absolute() or ".." in Path(name).parts:
            raise ValueError(f"unsafe source input: {name}")
        entries.append({"path": name, "sha256": sha(path),
                        "mode": 0o755 if path.stat().st_mode & 0o111 else 0o644})
    encoded = json.dumps(entries, sort_keys=True, separators=(",", ":")).encode()
    return entries, hashlib.sha256(encoded).hexdigest()


def pinned_base(value):
    # This Dockerfile uses Alpine adduser/addgroup and requires this JRE family.
    if not re.fullmatch(r"eclipse-temurin(?::21-jre-alpine)?@sha256:[0-9a-f]{64}", value):
        raise argparse.ArgumentTypeError("use eclipse-temurin[:21-jre-alpine]@sha256:<64 lowercase hex>")
    return value


def run(command, cwd, output, name, env=None):
    print(f"Running {name}; log: {output / (name + '.log')}", flush=True)
    with (output / (name + ".log")).open("w") as log:
        subprocess.run(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT,
                       check=True, env=env)


def validate_jar(jar, frontend):
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        required = {
            "BOOT-INF/classes/application-recovery.yml",
            "BOOT-INF/classes/com/jobpilot/config/RecoveryConfigurationGuard.class",
            "BOOT-INF/classes/com/jobpilot/config/SchemaCompatibilityGuard.class",
            "BOOT-INF/classes/com/jobpilot/miniapp/application/CandidateWorkflowService.class",
            "BOOT-INF/classes/db/migration/V17__candidate_workflow_ownership.class",
            "BOOT-INF/classes/db/ownership/V17-postgresql.sql",
            "BOOT-INF/classes/db/migration/V18__candidate_search_profiles.class",
            "BOOT-INF/classes/db/search/V18-schema.sql",
            "BOOT-INF/classes/db/search/V18-postgresql.sql",
            "BOOT-INF/classes/com/jobpilot/searchprofile/SearchProfileService.class",
            "BOOT-INF/classes/db/migration/V19__personalized_matching.class",
            "BOOT-INF/classes/db/personalized/V19-schema.sql",
            "BOOT-INF/classes/db/personalized/V19-postgresql.sql",
            "BOOT-INF/classes/com/jobpilot/personalizedmatching/MatchingWorkService.class",
            "BOOT-INF/classes/com/jobpilot/personalizedmatching/PersonalizedMatcher.class",
            "BOOT-INF/classes/static/mini-app/index.html",
        }
        if not required <= names or not any(n.startswith("BOOT-INF/lib/") for n in names):
            raise ValueError(f"not a candidate-aware recovery fat JAR; missing {sorted(required - names)}")
        assets = [p for p in frontend.rglob("*") if p.is_file()]
        if not any(p.suffix == ".js" for p in assets) or not any(p.suffix == ".css" for p in assets):
            raise ValueError("API frontend must contain JavaScript and CSS assets")
        for path in assets:
            name = "BOOT-INF/classes/static/mini-app/" + path.relative_to(frontend).as_posix()
            if name not in names or archive.read(name) != path.read_bytes():
                raise ValueError(f"frontend asset differs from verified API build: {name}")


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def frontend_environment(mode):
    return dict(os.environ, CI="true", VITE_JOBPILOT_MODE=mode)


def verify(root, output):
    if output == root or root in output.parents or output.exists():
        raise ValueError("verification output must be a new directory outside the checkout")
    entries, identity = source_inventory(root)
    java_version = subprocess.run(
        ["java", "-version"], cwd=root, capture_output=True, text=True, check=True).stderr.strip()
    node_version = capture(["node", "--version"], root)
    node_match = re.fullmatch(r"v22\.(\d+)\.\d+", node_version)
    if not re.search(r'version "21[.\"]', java_version) or not node_match or int(node_match[1]) < 12:
        raise ValueError("verification requires Java 21 and Node 22 on PATH")
    output.mkdir(parents=True)
    os.chmod(output, 0o700)
    stage = output / "source"
    stage.mkdir()
    for entry in entries:
        destination = stage / entry["path"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / entry["path"], destination)
        destination.chmod(entry["mode"])
        if sha(destination) != entry["sha256"]:
            raise ValueError("source changed while copying; use a new output directory")
    if source_inventory(root)[1] != identity:
        raise ValueError("source changed during snapshot; use a new output directory")
    write_json(output / "source-files.json", entries)
    # Stable metadata makes the source archive reproducible for identical inputs.
    with tarfile.open(output / "source.tar", "w", format=tarfile.PAX_FORMAT) as archive:
        for entry in entries:
            info = tarfile.TarInfo(entry["path"])
            info.size = (stage / entry["path"]).stat().st_size
            info.mode = entry["mode"]
            with (stage / entry["path"]).open("rb") as stream:
                archive.addfile(info, stream)
    # The default Playwright server needs fixtures; its second server sets API mode itself.
    env = frontend_environment("mock")
    frontend = stage / "mini-app"
    for command, name in [(["npm", "ci"], "npm-ci"),
                          (["npm", "run", "typecheck"], "frontend-typecheck"),
                          (["npm", "run", "lint"], "frontend-lint"),
                          (["npm", "test"], "frontend-tests")]:
        run(command, frontend, output, name, env)
    run(["npm", "run", "build"], frontend, output, "frontend-api-build", frontend_environment("api"))
    packaged_frontend = stage / "src/main/resources/static/mini-app"
    if packaged_frontend.exists():
        shutil.rmtree(packaged_frontend)
    shutil.copytree(frontend / "dist", packaged_frontend)
    run(["./mvnw", "-B", "--no-transfer-progress", "-Dapi.version=1.44", "clean", "verify"],
        stage, output, "backend-verify")
    run([sys.executable, "scripts/check-integration-results.py", "target/failsafe-reports"],
        stage, output, "required-postgres-suites")
    jars = list((stage / "target").glob("jobpilot-*.jar"))
    if len(jars) != 1:
        raise ValueError("expected exactly one Spring Boot JAR")
    validate_jar(jars[0], frontend / "dist")
    shutil.copyfile(jars[0], output / "jobpilot.jar")
    shutil.copyfile(stage / "Dockerfile.recovery", output / "Dockerfile.recovery")
    files = ["source-files.json", "source.tar", "jobpilot.jar", "Dockerfile.recovery"]
    files += sorted(p.name for p in output.glob("*.log"))
    proof = {"format": 1, "source_sha256": identity,
             "git_head": capture(["git", "rev-parse", "HEAD"], root),
             "git_status": capture(["git", "status", "--short"], root),
             "java": java_version, "node": node_version,
             "files": {name: sha(output / name) for name in files},
             "schema": 19, "frontend_mode": "api", "verification": "passed"}
    write_json(output / "verification.json", proof)
    print(f"Verified source {identity}; proof: {output / 'verification.json'}")
    return output / "verification.json"


def validate_proof(root, proof_path):
    proof = json.loads(proof_path.read_text())
    if (proof.get("format") != 1 or proof.get("schema") != 19
            or proof.get("frontend_mode") != "api" or proof.get("verification") != "passed"):
        raise ValueError("unsupported or failed verification proof")
    if source_inventory(root)[1] != proof["source_sha256"]:
        raise ValueError("source has changed since verification; reverify this candidate")
    required = {"source.tar", "source-files.json", "jobpilot.jar", "Dockerfile.recovery",
                "backend-verify.log", "required-postgres-suites.log", "frontend-tests.log",
                "frontend-api-build.log", "frontend-typecheck.log", "frontend-lint.log", "npm-ci.log"}
    if not required <= proof["files"].keys():
        raise ValueError("verification proof omits required evidence")
    for name, expected in proof["files"].items():
        if Path(name).name != name or sha(proof_path.parent / name) != expected:
            raise ValueError(f"verification evidence changed or unsafe path: {name}")
    return proof


def image(root, proof_path, base, platform):
    proof = validate_proof(root, proof_path)
    output = proof_path.parent
    if (output / "image-manifest.json").exists():
        raise ValueError("image manifest already exists; preserve it and use a new verification directory")
    context = output / "image-context"
    context.mkdir()
    for name in ("jobpilot.jar", "Dockerfile.recovery"):
        shutil.copyfile(output / name, context / name)
    tag = "jobpilot:recovery-" + proof["source_sha256"][:16] + "-" + proof["files"]["jobpilot.jar"][:12]
    command = ["docker", "build", "--platform", platform, "--pull", "--file", "Dockerfile.recovery",
               "--tag", tag, "--iidfile", str(output / "image-id.txt")]
    for key, value in {"BASE_IMAGE": base, "JAR_SHA256": proof["files"]["jobpilot.jar"],
                       "SOURCE_SHA256": proof["source_sha256"], "BUILD_COMMIT": proof["git_head"]}.items():
        command.extend(["--build-arg", key + "=" + value])
    run(command + ["."], context, output, "docker-build")
    image_id = (output / "image-id.txt").read_text().strip()
    inspection = json.loads(capture(["docker", "image", "inspect", image_id], root))[0]
    if inspection["Os"] + "/" + inspection["Architecture"] != platform:
        raise ValueError("built image architecture differs from target")
    archive = output / "jobpilot-recovery-image.tar"
    run(["docker", "image", "save", "--output", str(archive), image_id], root, output, "docker-save")
    write_json(output / "image-manifest.json", {
        "source_sha256": proof["source_sha256"], "git_head": proof["git_head"],
        "jar_sha256": proof["files"]["jobpilot.jar"], "base_image": base,
        "platform": platform, "local_tag": tag, "image_id": image_id,
        "archive": archive.name, "archive_sha256": sha(archive),
        "verification_sha256": sha(proof_path), "registry_digest": None,
        "default_profile": "recovery", "schema": 19,
        "note": "Local image ID and archive SHA are not a registry RepoDigest. No push performed."})
    print(f"Local recovery artifact: {output / 'image-manifest.json'}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("source-hash", "verify", "image", "build"))
    parser.add_argument("--output", type=Path, default=Path("/private/tmp/jobpilot-phase3/artifact"))
    parser.add_argument("--verification", type=Path)
    parser.add_argument("--base-image", type=pinned_base)
    parser.add_argument("--platform", choices=("linux/amd64",), default="linux/amd64")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if args.action in ("image", "build") and not args.base_image:
        parser.error("--base-image with an immutable Alpine JRE 21 digest is required")
    if args.action == "image" and not args.verification:
        parser.error("image requires --verification from this source's successful verify run")
    try:
        if args.action == "source-hash":
            print(source_inventory(root)[1])
            return
        proof_path = (verify(root, args.output.resolve()) if args.action in ("verify", "build")
                      else args.verification.resolve())
        if args.action in ("image", "build"):
            image(root, proof_path, args.base_image, args.platform)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Recovery build refused/failed: {error}\n")


if __name__ == "__main__":
    main()
