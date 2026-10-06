/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class ButtonRouteResultTest {
 final List<VehicleButton> buttons=Arrays.asList(VehicleButton.MEDIA,VehicleButton.VA,VehicleButton.DM);
 final String good="NATRO_MEDIA_ROUTE="+MediaKeyPolicy.NATRO+"\nNATRO_MEDIA_ROUTE=VA:disabled\nNATRO_MEDIA_ROUTE=DM:disabled\n";
 @Test public void requiresEveryExactReadback(){assertTrue(ButtonRouteResult.allRestored(buttons,good,null));assertFalse(ButtonRouteResult.allRestored(buttons,good.replace("VA:disabled","VA:stock"),null));assertFalse(ButtonRouteResult.allRestored(buttons,"prefix"+good,null));assertFalse(ButtonRouteResult.allRestored(buttons,good,"closed"));}
 @Test public void partialVaFailureIsNotSuccessfulAndDoesNotRestartSiblingsAgain(){
  String output=good.replace("NATRO_MEDIA_ROUTE=VA:disabled","NATRO_BUTTON_ERROR=VA:Unknown firmware");
  assertFalse(ButtonRouteResult.allRestored(buttons,output,null));assertFalse(ButtonRouteResult.retryable(output,null));
  ButtonRestoreGate gate=new ButtonRestoreGate();assertTrue(gate.begin(false));assertEquals(ButtonRestoreGate.Next.NONE,gate.finish(false,false));assertFalse(gate.begin(false));assertTrue(gate.begin(true));
 }
 @Test public void transientFailureRetainsBoundedRetriesAndReplacement(){
  ButtonRestoreGate gate=new ButtonRestoreGate();for(int i=0;i<3;i++){assertTrue(gate.begin(false));assertEquals(i<2?ButtonRestoreGate.Next.RETRY:ButtonRestoreGate.Next.NONE,gate.finish(false,true));}assertFalse(gate.begin(false));assertTrue(gate.begin(true));assertFalse(gate.begin(true));assertEquals(ButtonRestoreGate.Next.FORCE,gate.finish(false,false));
 }
}
