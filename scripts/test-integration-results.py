#!/usr/bin/env python3
"""Portable fail-closed required PostgreSQL report gate checks."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).with_name("check-integration-results.py")
PHASE5 = {"com.jobpilot.identity.V20NotificationMigrationIT"} | {
    "com.jobpilot.notifications." + name for name in
    ("NotificationSettingsIT", "NotificationActivationIT", "NotificationDeliveryIT", "NotificationConcurrencyIT", "NotificationCapacityIT")}
HISTORICAL = {"com.jobpilot.personalizedmatching.MatchingCapacityIT", "com.jobpilot.personalizedmatching.PersonalizedMatchingIT",
 "com.jobpilot.identity.V19MatchingMigrationIT", "com.jobpilot.jobs.PostgresPersistenceIT", "com.jobpilot.identity.IdentityBoundaryIT",
 "com.jobpilot.identity.V16IdentityMigrationIT", "com.jobpilot.identity.Phase2IsolationIT", "com.jobpilot.identity.PublicMiniAppBoundaryIT",
 "com.jobpilot.identity.V17WorkflowMigrationIT", "com.jobpilot.identity.SearchProfileIT", "com.jobpilot.identity.V18SearchProfileMigrationIT",
 "com.jobpilot.identity.RecoveryBoundaryIT", "com.jobpilot.recovery.RecoverySchemaCompatibilityIT"}
class IntegrationGuardTest(unittest.TestCase):
 def run_gate(self, missing=None, override=None, phase5=True):
  with tempfile.TemporaryDirectory() as directory:
   for name in HISTORICAL | (PHASE5 if phase5 else set()):
    if name == missing: continue
    attrs=dict(name=name,tests="1",failures="0",errors="0",skipped="0")
    if override and name==override[0]: attrs.update(override[1])
    ET.ElementTree(ET.Element("testsuite", attrs)).write(Path(directory)/("TEST-"+name+".xml"))
   return subprocess.run([sys.executable,str(SCRIPT),directory],capture_output=True,text=True)
 def test_complete_required_suites_pass(self): self.assertEqual(self.run_gate().returncode,0)
 def test_historical_only_cannot_pass(self): self.assertNotEqual(self.run_gate(phase5=False).returncode,0)
 def test_every_phase5_suite_is_required(self):
  for suite in PHASE5:
   with self.subTest(suite=suite): self.assertNotEqual(self.run_gate(missing=suite).returncode,0)
 def test_empty_skipped_failed_or_error_phase5_suite_fails(self):
  for suite in PHASE5:
   for attributes in ({"tests":"0"},{"skipped":"1"},{"failures":"1"},{"errors":"1"}):
    with self.subTest(suite=suite,attributes=attributes): self.assertNotEqual(self.run_gate(override=(suite,attributes)).returncode,0)
if __name__ == "__main__": unittest.main()
