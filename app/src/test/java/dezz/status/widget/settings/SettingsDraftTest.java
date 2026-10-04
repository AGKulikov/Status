/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SettingsDraftTest {
    @Test public void cancelDropsOnlyDraftAndLateLifecycleCannotResurrectIt(){
        Map<String,Object> disk=new HashMap<>();disk.put("color","#123456");disk.put("runtime",3);
        SettingsDraft draft=new SettingsDraft();draft.put("color","#ABCDEF",disk);
        assertEquals("#ABCDEF",draft.overlay(disk).get("color"));assertEquals("#123456",disk.get("color"));
        disk.put("runtime",4);draft.close();draft.put("color","#ABCDEF",disk);
        assertEquals(disk,draft.overlay(disk));assertFalse(draft.dirty());
    }
    @Test public void conflictDetectionIsScopedAndAllowsAnIdenticalConcurrentResult(){
        Map<String,Object> disk=new HashMap<>();disk.put("x",10);disk.put("other",1);
        SettingsDraft draft=new SettingsDraft();draft.put("x",20,disk);disk.put("other",2);
        assertTrue(draft.conflicts(disk).isEmpty());disk.put("x",15);assertEquals(Collections.singleton("x"),draft.conflicts(disk));
        disk.put("x",20);assertTrue(draft.conflicts(disk).isEmpty());
    }
    @Test public void missingFalseZeroEmptyAndSetsRemainDistinct(){
        Map<String,Object> disk=new HashMap<>();SettingsDraft draft=new SettingsDraft();
        draft.put("false",false,disk);draft.put("zero",0,disk);draft.put("empty","",disk);
        Set<String> input=new HashSet<>(Arrays.asList("a","b"));draft.put("set",input,disk);input.clear();
        Map<String,Object> effective=draft.overlay(disk);assertEquals(false,effective.get("false"));
        assertEquals(0,effective.get("zero"));assertEquals("",effective.get("empty"));
        assertEquals(new HashSet<>(Arrays.asList("a","b")),effective.get("set"));
        ((Set<?>)effective.get("set")).clear();assertEquals(2,((Set<?>)draft.overlay(disk).get("set")).size());
    }
    @Test public void revertingTheValueRemovesThePendingWrite(){
        Map<String,Object> disk=new HashMap<>();disk.put("x",10);
        SettingsDraft draft=new SettingsDraft();draft.put("x",20,disk);draft.put("x",10,disk);assertFalse(draft.dirty());
        draft.put("new",false,disk);draft.put("new",null,disk);assertFalse(draft.dirty());
    }
    @Test public void directEntryAndStepsUseTheSameRange(){
        SettingsNumericRange range=new SettingsNumericRange(-40,.5,160);
        assertEquals("-39.5",range.format(1));assertEquals(81,range.progress("0,5"));
        assertEquals(40,range.value(999),0);assertEquals(-40,range.value(-1),0);
        for(String invalid:Arrays.asList("NaN","Infinity","-40.1","40.1","")){
            try{range.progress(invalid);fail(invalid);}catch(IllegalArgumentException expected){}
        }
    }
}
