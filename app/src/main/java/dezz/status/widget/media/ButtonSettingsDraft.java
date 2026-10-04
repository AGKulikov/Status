/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import dezz.status.widget.settings.SettingsEditSession;
import dezz.status.widget.settings.SettingsPreferences;
import java.util.HashSet;
import java.util.Set;

/** Edits the existing namespaces without changing the live input owner's cached assignments. */
public final class ButtonSettingsDraft {
    private final Activity owner;
    private final SharedPreferences buttons,media;
    private final Set<String> changedKeys=new HashSet<>();
    public ButtonSettingsDraft(Activity owner){
        this.owner=owner;Context storage=owner.createDeviceProtectedStorageContext();
        buttons=SettingsPreferences.wrap(owner,dezz.status.widget.backup.BackupPreferences.open(storage,"vehicle_buttons",0),"vehicle_buttons",true);
        media=SettingsPreferences.wrap(owner,dezz.status.widget.backup.BackupPreferences.open(storage,"media_buttons",0),"media_buttons",true);
    }
    public boolean enabled(VehicleButton button){return button==VehicleButton.MEDIA?media.getBoolean("enabled",false):bool(button.key+".enabled");}
    public boolean bool(String key){return buttons.getBoolean(key,false);}
    public int integer(String key,int fallback){return buttons.getInt(key,fallback);}
    public String string(String key){return buttons.getString(key,"");}
    public boolean knobVolume(){return bool("knob.volume");}
    public int volumeSteps(){return integer("volume_steps",1);}
    public ButtonBinding binding(String group,String gesture){
        String key="binding."+group+"."+gesture+".";
        return new ButtonBinding(integer(key+"action",0),string(key+"app"),string(key+"command"),string(key+"package"),string(key+"shortcut"));
    }
    public void setEnabled(VehicleButton button,boolean enabled){
        if(button==VehicleButton.MEDIA){media.edit().putBoolean("enabled",enabled).commit();changed();}
        else put(button.key+".enabled",enabled);
    }
    public void setVolume(boolean enabled,int steps){
        if(steps<1||steps>20)throw new IllegalArgumentException("Шаг громкости: от 1 до 20");
        put("knob.volume",enabled);put("volume_steps",steps);
    }
    public void put(String key,Object value){
        SharedPreferences.Editor edit=buttons.edit();
        if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
        else if(value instanceof Integer)edit.putInt(key,(Integer)value);
        else if(value instanceof String)edit.putString(key,(String)value);
        else throw new IllegalArgumentException("Unsupported button setting");
        edit.commit();changedKeys.add(key);changed();
    }
    public void saveBinding(String group,String gesture,ButtonBinding binding){
        String error=binding.validationError();if(!error.isEmpty())throw new IllegalArgumentException(error);
        String key="binding."+group+"."+gesture+".";
        put(key+"action",binding.action.id);put(key+"app",binding.application);put(key+"command",binding.command);
        put(key+"package",binding.packageName);put(key+"shortcut",binding.shortcutJson);
    }
    private void changed(){
        Context app=owner.getApplicationContext();Set<String> keys=new HashSet<>(changedKeys);
        keys.addAll(SettingsEditSession.pendingKeys(owner,"button-settings"));
        SettingsEditSession.afterApply(owner,"button-settings",keys,()->{
            VehicleButtonController.get(app).reloadSettings(keys);
            MediaButtonController.get(app).reloadEnabledSetting();
        });
    }
}
