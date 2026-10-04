/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class SettingsApplyJournalTest {
    private static final class ProcessStopped extends Error {}
    @Test public void everyDurableBoundaryRecoversOneWholeGeneration()throws Exception {
        for(String stop:new String[]{"prepared","store:0","store:1","applied"}){
            Context context=RuntimeEnvironment.getApplication();Context device=context.createDeviceProtectedStorageContext();
            SharedPreferences a=device.getSharedPreferences("journal-a",0),b=device.getSharedPreferences("journal-b",0);
            a.edit().clear().putLong("long",1L).putString("remove","present").commit();
            b.edit().clear().putFloat("float",Float.intBitsToFloat(0x80000000)).putStringSet("set",new HashSet<>(Arrays.asList("я","雪"))).commit();
            SettingsApplyJournal.register(a,"journal-a",true);SettingsApplyJournal.register(b,"journal-b",true);
            Map<String,?> oldA=a.getAll(),oldB=b.getAll();
            Map<String,Object> nextA=new LinkedHashMap<>();nextA.put("long",2L);nextA.put("new",false);nextA.put("remove",null);
            Map<String,Object> nextB=new LinkedHashMap<>();nextB.put("float",1.25f);nextB.put("set",new HashSet<>(Arrays.asList("новый","")));
            Map<SharedPreferences,Map<String,Object>> changes=new LinkedHashMap<>();changes.put(a,nextA);changes.put(b,nextB);
            try{SettingsApplyJournal.commit(context,changes,stage->{if(stage.equals(stop))throw new ProcessStopped();});fail("Fault point not reached: "+stop);}
            catch(ProcessStopped expected){}
            // An unrelated writer after process death must not be rolled back with layout keys.
            a.edit().putString("runtime","after failure").commit();
            SettingsApplyJournal.recover(context);SettingsApplyJournal.recover(context);
            assertEquals("after failure",a.getString("runtime",null));
            Map<String,Object> restoredA=new LinkedHashMap<>(a.getAll());restoredA.remove("runtime");
            if(stop.equals("applied")){
                assertEquals(2L,a.getLong("long",0));assertTrue(a.contains("new"));assertFalse(a.getBoolean("new",true));assertFalse(a.contains("remove"));
                assertEquals(1.25f,b.getFloat("float",0),0);assertEquals(nextB.get("set"),b.getStringSet("set",null));
            }else{assertEquals(stop,oldA,restoredA);assertEquals(stop,oldB,b.getAll());}
        }
    }
    @Test public void ordinaryFailureRollsBackBeforeReturning()throws Exception {
        Context context=RuntimeEnvironment.getApplication();SharedPreferences store=context.createDeviceProtectedStorageContext().getSharedPreferences("journal-single",0);
        store.edit().putInt("width",10).commit();SettingsApplyJournal.register(store,"journal-single",true);
        Map<SharedPreferences,Map<String,Object>> changes=new LinkedHashMap<>();changes.put(store,Collections.singletonMap("width",20));
        try{SettingsApplyJournal.commit(context,changes,stage->{if(stage.equals("store:0"))throw new IllegalStateException("simulated failure");});fail();}
        catch(IllegalStateException expected){}
        assertEquals(10,store.getInt("width",0));SettingsApplyJournal.recover(context);assertEquals(10,store.getInt("width",0));
    }
    @Test public void credentialAndDeviceStoresWithSameNameRecoverIndependently()throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        SharedPreferences credential=context.getSharedPreferences("journal-domains",0);
        SharedPreferences device=context.createDeviceProtectedStorageContext().getSharedPreferences("journal-domains",0);
        credential.edit().clear().putInt("width",10).commit();
        device.edit().clear().putInt("width",30).commit();
        SettingsApplyJournal.register(credential,"journal-domains",false);
        SettingsApplyJournal.register(device,"journal-domains",true);
        Map<SharedPreferences,Map<String,Object>> changes=new LinkedHashMap<>();
        changes.put(credential,Collections.singletonMap("width",20));
        changes.put(device,Collections.singletonMap("width",40));
        try{SettingsApplyJournal.commit(context,changes,stage->{if(stage.equals("store:1"))throw new ProcessStopped();});fail();}
        catch(ProcessStopped expected){}
        // Even when the caller holds a DE context, a CE participant stays CE.
        SettingsApplyJournal.recover(context.createDeviceProtectedStorageContext());
        assertEquals(10,credential.getInt("width",0));
        assertEquals(30,device.getInt("width",0));
    }
}
