package dezz.status.widget.servicemode;
import android.content.*;
import android.content.pm.PackageManager;
public final class ServiceModeRecoveryReplay {
    static void check(boolean x,String why) { if(!x)throw new AssertionError(why); }
    interface Attempt { void run() throws Exception; }
    static void reject(Attempt f) throws Exception { try { f.run();throw new AssertionError("unsafe operation accepted"); } catch(java.io.IOException good){} }
    public static void main(String[] args) throws Exception {
        Context c=new Context();PackageManager pm=c.packages;
        for(String pkg:new String[]{"third.default","third.enabled","dialer.app","com.ecarx.media","ru.natro.statuswidget","system.app"})pm.add(pkg,0,pkg.equals("system.app")?1:0);
        pm.state("third.enabled",1);
        ServiceModeJournal journal=new ServiceModeJournal(c);AppsToHideStorage tracked=new AppsToHideStorage(c);
        journal.validateTarget("third.default",true);journal.beforeDisable("third.default");
        check(tracked.hasHiddenApps()&&journal.hasBaseline("third.default"),"write-ahead durable before PM command");
        check(journal.command("third.default",true).equals("pm disable-user --user 0 third.default"),"exact user0 command");
        check(!journal.confirm("third.default",true),"API response is not enough without disabled readback");
        pm.state("third.default",3);check(journal.confirm("third.default",true),"disabled confirmed");
        journal=new ServiceModeJournal(c);
        check(journal.command("third.default",false).equals("pm default-state --user 0 third.default"),"restart restores DEFAULT, not ENABLED");
        check(!journal.confirm("third.default",false)&&tracked.hasHiddenApps(),"failed restore keeps recovery record");
        pm.state("third.default",0);check(journal.confirm("third.default",false),"restore confirmed");
        check(!tracked.hasHiddenApps()&&!journal.hasBaseline("third.default")&&pm.launcherState==1&&KeepAliveService.stops>0,"last restore reveals launcher and releases service");
        journal.beforeDisable("third.enabled");pm.state("third.enabled",3);
        check(journal.command("third.enabled",false).startsWith("pm enable "),"explicit ENABLED preserved");
        pm.state("third.enabled",1);c.getSharedPreferences(c.getPackageName()+"_apps_to_hide",0).failCommits=1;
        check(!journal.confirm("third.enabled",false)&&tracked.hasHiddenApps(),"disk failure does not claim complete recovery");
        check(journal.confirm("third.enabled",false),"retry safely finishes journal");
        ServiceModeJournal stable=journal;
        for(String pkg:new String[]{"dialer.app","com.ecarx.media","ru.natro.statuswidget","system.app","third.app;reboot"})reject(()->stable.validateTarget(pkg,true));
        c.getSharedPreferences("natro_service_mode_original",0).failCommits=1;
        reject(()->stable.beforeDisable("third.default"));check(!stable.hasBaseline("third.default"),"commit refusal prevents hide");
        PinStorage pin=new PinStorage(c);check(!pin.hasPin(),"custom PIN required by hide flow");
        check(pin.validate("12")==PinStorage.TOO_SHORT&&pin.validate("012")==PinStorage.STARTS_WITH_ZERO,"PIN shape");
        check(pin.validate("١٢٣")==PinStorage.INVALID&&pin.validate("1".repeat(33))==PinStorage.INVALID,"ASCII bounded PIN");
        check(pin.save("987654")&&new PinStorage(c).verify("987654")&&!pin.verify("987653"),"PIN survives new storage owner");
        c.getSharedPreferences(c.getPackageName()+"_pin",0).failCommits=1;
        check(!pin.save("456789")&&pin.verify("987654"),"failed PIN save does not erase recovery PIN");
        System.out.println("Service recovery: PASS");
    }
}
