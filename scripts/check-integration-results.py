#!/usr/bin/env python3
"""Fail CI when required PostgreSQL suites are absent, failed, or silently skipped."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

required = {
    "com.jobpilot.personalizedmatching.MatchingCapacityIT",
    "com.jobpilot.personalizedmatching.PersonalizedMatchingIT",
    "com.jobpilot.identity.V19MatchingMigrationIT",
    "com.jobpilot.jobs.PostgresPersistenceIT",
    "com.jobpilot.identity.IdentityBoundaryIT",
    "com.jobpilot.identity.V16IdentityMigrationIT",
    "com.jobpilot.identity.Phase2IsolationIT",
    "com.jobpilot.identity.PublicMiniAppBoundaryIT",
    "com.jobpilot.identity.V17WorkflowMigrationIT",
    "com.jobpilot.identity.SearchProfileIT",
    "com.jobpilot.identity.V18SearchProfileMigrationIT",
    "com.jobpilot.identity.RecoveryBoundaryIT",
    "com.jobpilot.recovery.RecoverySchemaCompatibilityIT",
}
reports = [ET.parse(path).getroot() for path in Path(sys.argv[1]).glob("TEST-*.xml")]
present = {report.get("name") for report in reports if int(report.get("tests", 0)) > 0}
bad = [report.get("name") for report in reports
       if any(int(report.get(key, 0)) for key in ("skipped", "failures", "errors"))]
missing = sorted(required - present)
if missing or bad:
    print(f"Required integration verification failed: missing={missing}, failed_or_skipped={bad}", file=sys.stderr)
    sys.exit(1)
print(f"Verified {sum(int(report.get('tests', 0)) for report in reports)} integration tests with no skips.")
