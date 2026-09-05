#!/usr/bin/env python3
"""Synthetic setter bodies verify argument preservation, ordering and baseline rejection."""
import hashlib
import importlib.util
from pathlib import Path
import re
import unittest
from unittest.mock import patch

PATH = Path(__file__).resolve().parents[1] / 'patch_navigation_maneuver_view.py'
spec = importlib.util.spec_from_file_location('maneuver_patch', PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ManeuverPatchTest(unittest.TestCase):
    def fixture(self):
        signatures = {
            'setManeuver': '(LManeuver;Ljava/lang/String;Ljava/lang/String;LAux;)V',
            'setLaneItems': '(Ljava/util/List;)V', 'setDirectionSignItems': '(Ljava/util/List;)V',
            'setNextUpcomingDirectionSignItems': '(Ljava/util/List;)V',
            'setMode': '(LMode;)V', 'setScale': '(F)V', 'setMaxLines': '(I)V',
            'setStyle': '(LStyle;)V', 'setPresenter': '(LPresenter;)V',
        }
        return '\n'.join('.method public ' + name + signatures.get(name, '(Z)V')
                         + '\n    .locals 1\n    const/4 p1, 0x0\n    return-void\n.end method\n'
                         for name in module.SPEC)

    def test_requires_exact_baseline_and_rejects_reapplication(self):
        source = self.fixture()
        with self.assertRaises(ValueError): module.patch(source)
        with patch.object(module, 'EXPECTED_SMALI_SHA256', hashlib.sha256(source.encode()).hexdigest()):
            result = module.patch(source)
            with self.assertRaises(ValueError): module.patch(result)

    def test_keeps_every_original_body_and_only_notifies_after_success(self):
        source = self.fixture()
        with patch.object(module, 'EXPECTED_SMALI_SHA256', hashlib.sha256(source.encode()).hexdigest()):
            result = module.patch(source)
        original = result.split('\n# Natro observation wrappers;')[0]
        self.assertEqual(source.rstrip('\n'), original.replace('.method private natro$original$', '.method public ').rstrip('\n'))
        for name in module.SPEC:
            wrapper = re.search(r'^\.method public ' + name + r'\([^\n]+\n(.*?)\.end method', result, re.M | re.S).group(1)
            self.assertLess(wrapper.index('invoke-direct/range'), wrapper.index(':natro_observe_start'))
            self.assertLess(wrapper.index(':natro_observe_start'), wrapper.index('invoke-static/range'))
            self.assertIn('move-exception v0', wrapper)
        self.assertIn('{p0 .. p4}', result)

    def test_missing_setter_is_not_silently_skipped(self):
        source = self.fixture().replace('setMode(', 'unexpectedMode(')
        with patch.object(module, 'EXPECTED_SMALI_SHA256', hashlib.sha256(source.encode()).hexdigest()):
            with self.assertRaises(ValueError): module.patch(source)


if __name__ == '__main__': unittest.main()
