/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import dezz.status.widget.driver.*;
import dezz.status.widget.media.ButtonAction;
import dezz.status.widget.launcher.LauncherGlobalElementLayoutStore;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Former portable replays, now exercising the actual SettingsPreferences and Android storage. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class PassengerPreferencesIntegrationTest {
    private int scope;
    private Context newScope() {
        final String prefix="passenger-replay-"+(scope++)+"-";
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext(){return this;}
            @Override public Context createDeviceProtectedStorageContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){
                return super.getSharedPreferences(prefix+name,mode);
            }
        };
    }
    private static void check(boolean condition,String message){
        org.junit.Assert.assertTrue(message,condition);
    }
    private static void check(boolean condition){org.junit.Assert.assertTrue(condition);}
    @Test public void launcherPreferencesKeepProfilesFutureKeysAndMigrations() throws Exception {
  Context c=newScope(); Preferences driver=new Preferences(c);
  driver.launcherLayoutJson.set("driver layout");
  driver.launcherClimateConfigJson.set("driver climate");
  driver.launcherAllAppsColumns.set(4);
  driver.launcherImmersive.set(false);
  Preferences passenger=Preferences.forPassengerLauncher(c);
  check(passenger.launcherLayoutJson.get().isEmpty(),"independent initial layout");
  passenger.launcherLayoutJson.set("passenger layout");
  passenger.launcherClimateConfigJson.set("passenger climate");
  passenger.launcherAllAppsColumns.set(8);
  passenger.launcherImmersive.set(true);
  passenger.launcherAllAppsHiddenComponents.set(new HashSet<>(Arrays.asList("p/a")));
  check(driver.launcherLayoutJson.get().equals("driver layout"),"layout isolation");
  check(driver.launcherClimateConfigJson.get().equals("driver climate"),"climate isolation");
  check(driver.launcherAllAppsColumns.get()==4 && !driver.launcherImmersive.get(),"scalar isolation");
  check(driver.launcherAllAppsHiddenComponents.get().isEmpty(),"set isolation");
  for(java.lang.reflect.Field f:Preferences.class.getFields()) {
   if(!f.getName().startsWith("launcher") || !Preferences.Preference.class.isAssignableFrom(f.getType()))continue;
   String main=((Preferences.Preference)f.get(driver)).key;
   String other=((Preferences.Preference)f.get(passenger)).key;
   if(f.getName().startsWith("launcherMediaAutoResume") || f.getName().startsWith("launcherMediaFixedPlayer")
     || f.getName().equals("launcherHideSystemStatusBar") || f.getName().equals("launcherSystemStatusBarOriginalPolicy"))
    check(main.equals(other),"device policy must stay shared: "+main);
   else check(!main.equals(other),"profile key collision: "+main);
  }
  new Preferences.Str(passenger,"launcherFutureDocument","").set("future value");
  check(new Preferences.Str(driver,"launcherFutureDocument","").get().isEmpty(),"future key isolation");
  passenger.launcherMediaFixedPlayerPackage.set("player");
  check(driver.launcherMediaFixedPlayerPackage.get().equals("player"),"global media policy");
  LauncherGlobalElementLayoutStore widgets=new LauncherGlobalElementLayoutStore(passenger);
  widgets.load(1920,720);
  widgets.put("clock",new LauncherGlobalElementLayoutStore.Geometry(10,10,100,100));
  LauncherGlobalElementLayoutStore.Appearance appearance=widgets.getAppearance("clock");
  appearance.tapAction=LauncherGlobalElementLayoutStore.TapAction.PASSENGER_HOME;
  widgets.putAppearance("clock",appearance);
  Context restored=newScope(); Preferences restoredDriver=new Preferences(restored);
  restoredDriver.importFromJson(driver.exportToJson());
  Preferences restoredPassenger=Preferences.forPassengerLauncher(restored);
  check(restoredDriver.launcherLayoutJson.get().equals("driver layout"),"restore driver");
  check(restoredPassenger.launcherLayoutJson.get().equals("passenger layout"),"restore passenger");
  check(restoredPassenger.launcherAllAppsHiddenComponents.get().contains("p/a"),"restore set");
  check(new Preferences.Str(restoredPassenger,"launcherFutureDocument","").get().equals("future value"),"restore future key");
  LauncherGlobalElementLayoutStore restoredWidgets=new LauncherGlobalElementLayoutStore(restoredPassenger);
  restoredWidgets.load(1920,720);
  check(restoredWidgets.getAppearance("clock").tapAction==LauncherGlobalElementLayoutStore.TapAction.PASSENGER_HOME,"restore widget action");
  Context legacy=newScope();
  SharedPreferences raw=legacy.getSharedPreferences("ru.natro.statuswidget_preferences",0);
  raw.edit().putString("launcherClimateConfigJson","old driver climate").commit();
  Preferences.forPassengerLauncher(legacy);
  check(!raw.contains("floatingClimateConfigJson"),"passenger must not consume main migration");
  Preferences migrated=new Preferences(legacy);
  check(migrated.floatingClimateConfigJson.get().equals("old driver climate"),"main migration preserved");
  check(ButtonAction.fromId(10)==ButtonAction.PASSENGER_HOME,"PHOME compatibility");
  check(ButtonAction.fromId(108)==ButtonAction.NATRO_PASSENGER_HOME,"new physical action");
  Set<Integer> ids=new HashSet<>();
  for(ButtonAction a:ButtonAction.values())check(ids.add(a.id),"duplicate button id");
    }

    @Test public void panelPreferencesKeepFavoritesAndBackupRoundtrip() throws Exception {
  Context c=newScope(); Preferences p=new Preferences(c);
  check(!p.passengerPanelEnabled.get());
  check(p.passengerPanel.side.get()==1 && p.activeDriverPanelProfile().side.get()==0);
  p.passengerPanelEnabled.set(true); p.passengerPanel.widthPx.set(230);
  p.passengerPanel.shortcutsJson.set("[{\"id\":\"passenger\"}]");
  check(p.activeDriverPanelProfile().widthPx.get()!=230);
  check(!p.activeDriverPanelProfile().shortcutsJson.get().contains("passenger"));
  DriverFavoritesPanelStore d=new DriverFavoritesPanelStore(p), s=new DriverFavoritesPanelStore(p,true);
  DriverFavoritesPanelConfig dc=d.create("Driver"), sc=s.create("Passenger");
  check(d.find(sc.id)==null && s.find(dc.id)==null);
  sc.columns=7;sc.autoCloseSeconds=19;s.upsert(sc);
  p.passengerFavoritesShortcuts(sc.id).set("passenger cells");
  p.driverFavoritesShortcuts(sc.id).set("driver cells");
  check(p.passengerFavoritesShortcuts(sc.id).get().equals("passenger cells"));
  s.remove(sc.id);check(p.passengerFavoritesShortcuts(sc.id).get().equals("passenger cells"));
  s.upsert(sc);
  p.passengerAllAppsColumns.set(8);
  p.passengerAllAppsHiddenComponents.set(new HashSet<>(Arrays.asList("pkg/activity")));
  check(p.launcherAllAppsColumns.get()==5 && p.launcherAllAppsHiddenComponents.get().isEmpty());
  Preferences q=new Preferences(newScope());q.importFromJson(p.exportToJson());
  check(q.passengerPanelEnabled.get() && q.passengerPanel.widthPx.get()==230);
  check(q.passengerPanel.shortcutsJson.get().equals(p.passengerPanel.shortcutsJson.get()));
  check(new DriverFavoritesPanelStore(q,true).find(sc.id).autoCloseSeconds==19);
  check(q.passengerFavoritesShortcuts(sc.id).get().equals("passenger cells"));
  check(q.driverFavoritesShortcuts(sc.id).get().equals("driver cells"));
  check(q.passengerAllAppsHiddenComponents.get().contains("pkg/activity"));
  check(q.passengerAllAppsColumns.get()==8);
  for(int width:new int[]{800,1280,1920,2560}) {
   check(PassengerPanelPlacement.x(width,150,false)==0);
   check(PassengerPanelPlacement.x(width,150,true)==width-150);
  }
  check(PassengerPanelPlacement.x(80,150,true)==0);
  try {p.passengerFavoritesShortcuts("../bad");throw new AssertionError();}catch(IllegalArgumentException expected){}
  System.out.println("Passenger profiles/favorites/catalog/JSON round-trip/geometry PASS");
    }
}
