/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.transfer;

import android.content.Context;
import android.content.SharedPreferences;
import dezz.status.widget.backup.BackupPreferences;
import java.util.*;

/** CE, private, installation-bound trust. Only hashes; never bearer credentials or clipboard. */
public final class LanTrustStore implements LanPairing.TrustStore {
    public static final String NAME="natro_lan_trust_v1";
    private static final Object LOCK=new Object();
    private final SharedPreferences prefs;
    private static volatile boolean storageFailed;
    public LanTrustStore(Context context) {
        prefs=BackupPreferences.open(context.getApplicationContext(),NAME,Context.MODE_PRIVATE);
    }
    @Override public Set<String> hashes() {
        synchronized(LOCK) {
            if(storageFailed)return Collections.emptySet();
            try {
                Set<String> values=new HashSet<>(prefs.getStringSet("hashes",Collections.emptySet()));
                if(values.size()>4||values.stream().anyMatch(s->s==null||!s.matches("[a-f0-9]{64}")))return Collections.emptySet();
                return values;
            }catch(RuntimeException invalid){return Collections.emptySet();}
        }
    }
    @Override public boolean save(Set<String> values) {
        synchronized(LOCK) {
            if(storageFailed||values.size()>4||values.stream().anyMatch(s->s==null||!s.matches("[a-f0-9]{64}")))return false;
            // Enrolment explicitly opts into boot startup; revoking the last peer disables it.
            try {
                SharedPreferences.Editor edit=prefs.edit().putStringSet("hashes",new HashSet<>(values));
                if(values.size()>hashes().size())edit.putBoolean("auto_start",true);
                if(values.isEmpty())edit.putBoolean("auto_start",false).putBoolean("remote_commands",false);
                boolean success=edit.commit();
                if(!success)storageFailed=true;
                return success;
            }catch(RuntimeException error){storageFailed=true;return false;}
        }
    }
    public boolean autoStart() {
        synchronized(LOCK) {
            try{return !storageFailed&&prefs.getBoolean("auto_start",false)&&!hashes().isEmpty();}
            catch(RuntimeException invalid){return false;}
        }
    }
    public boolean setAutoStart(boolean enabled) {
        synchronized(LOCK) {
            if(storageFailed)return false;
            try {
                boolean success=prefs.edit().putBoolean("auto_start",enabled).commit();
                if(!success)storageFailed=true;
                return success;
            }catch(RuntimeException error){storageFailed=true;return false;}
        }
    }
    public boolean commandsAllowed() {
        synchronized(LOCK) {
            try{return !storageFailed&&!hashes().isEmpty()&&prefs.getBoolean("remote_commands",false);}
            catch(RuntimeException invalid){return false;}
        }
    }
    public boolean setCommandsAllowed(boolean enabled) {
        synchronized(LOCK) {
            if(storageFailed)return false;
            try {
                boolean success=prefs.edit().putBoolean("remote_commands",enabled).commit();
                if(!success)storageFailed=true;
                return success;
            }catch(RuntimeException error){storageFailed=true;return false;}
        }
    }
}
