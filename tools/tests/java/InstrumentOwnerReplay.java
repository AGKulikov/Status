/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;
public final class InstrumentOwnerReplay {
    static String stack(int display, String owner, boolean visible) {
        return "Stack id=4 bounds=[0,0][1920,720] displayId="+display+" userId=0\n"
            +" taskId=17: Natro visible="+visible+" topActivity=ComponentInfo{"+owner+"}\n";
    }
    static void check(boolean ok) { if(!ok)throw new AssertionError(); }
    public static void main(String[] args) {
        String pkg="ru.natro.statuswidget", own=pkg+"/dezz.status.widget.instrument.InstrumentPanelActivity";
        String good=stack(0,"other/.Home",true)+stack(2,own,true);
        check(InstrumentDisplayOwner.owns(good,2,pkg));
        check(InstrumentDisplayOwner.owns(good.replace("\n","\r\n"),2,pkg));
        for(String bad:new String[]{"","Permission denied",stack(3,own,true),stack(2,own,false),
            stack(2,pkg+"/.Other",true),good+stack(2,"other/.Home",true),good.replace("topActivity=","unknown="),
            good.replace("ComponentInfo{","ComponentInfo"),good.replace("}",""),good+"x".repeat(128*1024)})
            check(!InstrumentDisplayOwner.owns(bad,2,pkg));
        check(!InstrumentDisplayOwner.owns(null,2,pkg));
        check(!InstrumentDisplayOwner.owns(good,-1,pkg));
        String description=InstrumentDisplayOwner.describe(good+stack(2,"other/.Home",true),2,pkg);
        check(description.equals("target_stacks=2, visible_panel=1, visible_other=1, unparsed_visible=0, overlapping_other=1, outside_panel=0, unknown_bounds=0, scope=panel_stack_bounds"));
        check(!description.contains("other/.Home"));
        check(InstrumentDisplayOwner.describe(good.replace("topActivity=","unknown="),2,pkg).contains("unparsed_visible=1"));
        check(InstrumentDisplayOwner.describe(null,2,pkg).equals("snapshot=invalid"));
        String hud=stack(2,"com.ecarx.hud/.HomeActivity",true).replace("[0,0][1920,720]","[0,720][728,906]");
        String menu=stack(2,"com.ecarx.dimmenu/.MainActivity",true).replace("[0,0][1920,720]","[728,720][1568,1032]");
        check(InstrumentDisplayOwner.owns(good+hud+menu,2,pkg));
        check(InstrumentDisplayOwner.owns(menu+hud+good,2,pkg));
        check(InstrumentDisplayOwner.describe(good+hud+menu,2,pkg).contains("overlapping_other=0, outside_panel=2"));
        for(String geometry:new String[]{"[0,719][728,906]","[0,0][1920,1080]","[0,720][0,906]",
                "[0,720][728,720]","[0,720][2147483648,906]","missing"})
            check(!InstrumentDisplayOwner.owns(good+hud.replace("[0,720][728,906]",geometry),2,pkg));
        check(!InstrumentDisplayOwner.owns(good+hud.replace("bounds=","unknown="),2,pkg));
        check(!InstrumentDisplayOwner.owns(good+stack(2,own,true),2,pkg));
        check(!InstrumentDisplayOwner.owns(good+" taskId=18 visible=true\n",2,pkg));
        check(InstrumentDisplayOwner.ownerAfterClose(good,2,pkg)==InstrumentPanelCloseCoordinator.Owner.FREE);
        check(InstrumentDisplayOwner.ownerAfterClose(hud+menu,2,pkg)==InstrumentPanelCloseCoordinator.Owner.FREE);
        check(InstrumentDisplayOwner.ownerAfterClose(good+stack(2,"com.auto_soft.monjaro_dashboard/.Main",true),2,pkg)
                ==InstrumentPanelCloseCoordinator.Owner.OTHER);
        check(InstrumentDisplayOwner.ownerAfterClose(stack(2,"plus.monjaro/.Main",true),2,pkg)
                ==InstrumentPanelCloseCoordinator.Owner.OTHER);
        for(String bad:new String[]{"", "Permission denied", "Stack id=4 bounds=[0,0][1920,720] displayId=2 userId=0\n",
                good.replace("visible=true", "visibility=unknown"), good.replace("topActivity=", "unknown=")})
            check(InstrumentDisplayOwner.ownerAfterClose(bad,2,pkg)==InstrumentPanelCloseCoordinator.Owner.UNKNOWN);
        check(InstrumentDisplayOwner.ownerAfterClose(null,2,pkg)==InstrumentPanelCloseCoordinator.Owner.UNKNOWN);
        check(InstrumentDisplayOwner.owns(good+hud+" taskId=19 bounds=[0,0][1920,1080] visible=true topActivity=ComponentInfo{com.ecarx.hud/.HomeActivity}\n",2,pkg));
        System.out.println("PASS exact display/activity, regional HUD separation, overlaps, geometry, duplicated stack top, CRLF, hidden/unknown/ambiguous/malformed/oversize");
    }
}
