/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;
import org.junit.Test;
import static org.junit.Assert.*;
public class InstrumentOemRecoveryTest {
 @Test public void allIndependentOwnershipChecksAreRequired(){
  for(int flags=0;flags<16;flags++)assertEquals(flags==15,InstrumentOemPolicy.restoreNavigation((flags&1)!=0,1,(flags&2)!=0,(flags&4)!=0,(flags&8)!=0));
  for(int mode:new int[]{-1,0,2,3,4})assertFalse(InstrumentOemPolicy.restoreNavigation(true,mode,true,true,true));
 }
 @Test public void stockOverlayIsStillReleasedOutsideVerifiedNavigation(){
  for(int mode:new int[]{-1,0,1,2,3,4}){assertEquals(mode==3,InstrumentOemPolicy.suppressWhiteBar(true,mode));assertFalse(InstrumentOemPolicy.suppressWhiteBar(false,mode));}
 }
}
