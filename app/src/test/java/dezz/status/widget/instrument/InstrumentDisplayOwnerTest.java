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
}
