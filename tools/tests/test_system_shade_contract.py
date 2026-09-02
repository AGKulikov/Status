import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SHADE = ROOT / "app" / "src" / "main" / "java" / "dezz" / "status" / "widget" / "shade"


class SystemShadeWindowContractTest(unittest.TestCase):
    def test_closed_trigger_does_not_expand_during_action_down(self):
        source = (SHADE / "SystemShadeRootLayout.java").read_text()
        down = source.split("case MotionEvent.ACTION_DOWN:", 1)[1]
        down = down.split("case MotionEvent.ACTION_MOVE:", 1)[0]
        self.assertNotIn("onWindowExpansionRequested", down)
        self.assertIn("expandWindowBeforeSettle(open, targetOpen)", source)

    def test_every_stable_closed_result_repairs_physical_window_height(self):
        source = (SHADE / "SystemShadeRootLayout.java").read_text()
        finish = source.split("private void finishState(boolean value)", 1)[1]
        finish = finish.split("private void setReveal", 1)[0]
        self.assertIn("listener.onOpenStateChanged(open)", finish)
        self.assertNotIn("if (changed", finish)

    def test_controller_expands_only_on_accepted_open_and_collapses_on_closed(self):
        source = (SHADE / "SystemShadeOverlayController.java").read_text()
        self.assertIn("onWindowExpansionRequested()", source)
        self.assertIn("WindowManager.LayoutParams.MATCH_PARENT", source)
        self.assertIn(": config.gestureHandleHeightPx", source)
        self.assertIn("params.height = previousHeight", source)
        self.assertIn("!host.isOpen()) host.close(false)", source)

    def test_critical_kx11_regression_is_in_requirement_ledger(self):
        ledger = (ROOT / "PROJECT_REQUIREMENTS_RU.md").read_text()
        self.assertIn("SHADE-008", ledger)
        self.assertIn("полноэкранное touch-окно", ledger)


if __name__ == "__main__":
    unittest.main()
