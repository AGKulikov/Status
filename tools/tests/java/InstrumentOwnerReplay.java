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
        System.out.println("PASS exact display/activity, CRLF, hidden/unknown/ambiguous/malformed/oversize");
    }
}
