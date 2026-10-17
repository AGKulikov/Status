package dezz.status.widget.instrument;
import org.junit.Test;
import static org.junit.Assert.*;

public class InstrumentDisplayOwnerTest {
    private static final String PKG = "ru.natro.statuswidget";
    private static final String OWN = PKG + "/dezz.status.widget.instrument.InstrumentPanelActivity";
    private static String stack(int display, boolean visible, String component) {
        return "Stack id=4 bounds=[0,0][1920,720] displayId=" + display + " userId=0\n"
                + " configuration={}\n  taskId=17: Natro bounds=[0,0][1920,720] userId=0 visible="
                + visible + " topActivity=ComponentInfo{" + component + "}\n";
    }
    @Test public void exactVisibleSecondaryActivityIsAcceptedWithoutDefaultDisplayFocus() {
        assertTrue(InstrumentDisplayOwner.owns(stack(0,true,"other/.Home")+stack(2,true,OWN),2,PKG));
        assertTrue(InstrumentDisplayOwner.owns(stack(2,true,OWN).replace("\n","\r\n"),2,PKG));
    }
    @Test public void hiddenWrongDisplayWrongActivityAndAmbiguityAreRejected() {
        for(String input:new String[]{"Permission denied", "", stack(3,true,OWN),stack(2,false,OWN),
                stack(2,true,PKG+"/.Settings"), stack(2,true,OWN)+stack(2,true,"other/.Home"),
                stack(2,true,OWN).replace("topActivity=", "unknown=")})
            assertFalse(input,InstrumentDisplayOwner.owns(input,2,PKG));
    }
    @Test public void hudAndLowerMenuStacksDoNotOwnInstrumentRegion() {
        String panel = stack(2,true,OWN);
        String hud = stack(2,true,"com.ecarx.hud/.HomeActivity")
                .replace("[0,0][1920,720]","[0,720][728,906]");
        String menu = stack(2,true,"com.ecarx.dimmenu/.MainActivity")
                .replace("[0,0][1920,720]","[728,720][1568,1032]");
        assertTrue(InstrumentDisplayOwner.owns(hud + panel + menu,2,PKG));
        assertTrue(InstrumentDisplayOwner.owns(menu + hud + panel,2,PKG));
        assertFalse("Even one pixel of overlap retains OEM priority",
                InstrumentDisplayOwner.owns(panel + hud.replace("[0,720]","[0,719]"),2,PKG));
        assertFalse("Unknown geometry never grants ownership",
                InstrumentDisplayOwner.owns(panel + hud.replace("bounds=","unknown="),2,PKG));
    }
    @Test public void stackTopRepeatedForBackgroundTasksUsesStackGeometry() {
        String panel = stack(2,true,OWN);
        String hud = stack(2,true,"com.ecarx.hud/.HomeActivity")
                .replace("[0,0][1920,720]","[0,720][728,906]");
        // AOSP StackInfo prints the same topActivity/visible fields on every task row.
        hud += "  taskId=18: old bounds=[0,0][1920,1080] userId=0 visible=true "
                + "topActivity=ComponentInfo{com.ecarx.hud/.HomeActivity}\n";
        assertTrue(InstrumentDisplayOwner.owns(panel + hud,2,PKG));
        assertFalse(InstrumentDisplayOwner.owns(panel + hud
                + "  taskId=19: missing visible=true\n",2,PKG));
        assertFalse(InstrumentDisplayOwner.owns(panel + panel,2,PKG));
    }
}
