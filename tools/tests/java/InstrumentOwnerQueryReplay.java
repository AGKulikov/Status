/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import java.util.regex.*;

public final class InstrumentOwnerQueryReplay {
    static final String PKG="ru.natro.statuswidget";
    static final String CLEAR="Stack id=0 bounds=[0,0][1920,720] displayId=0 userId=0\n"
            +"  taskId=17 visible=true topActivity=ComponentInfo{"+PKG+"/.Settings}\n";
    static final class Input {
        final InstrumentOwnerQuery query=new InstrumentOwnerQuery();
        final String token;
        Input() {
            Matcher match=Pattern.compile("NATRO_EXIT_[a-f0-9]{32}").matcher(query.command(100));
            if(!match.find())throw new AssertionError("missing unique frame"); token=match.group();
        }
        String frame(String body,int code) { return token+"_BEGIN\n"+body+"\n"+token+":"+code; }
        InstrumentOwnerQuery.Observation read(String output,String error,long now) {
            return query.finish(output,error,now,2,PKG);
        }
    }
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args) {
        Input input=new Input();
        InstrumentOwnerQuery.Observation result=input.read(input.frame(CLEAR,0),null,150);
        check(result.owner==InstrumentPanelCloseCoordinator.Owner.FREE,"complete empty target after trim");
        check(result.observedAt==100 && result.description.contains("age_ms=50"),"execution-time freshness");
        check(!result.description.contains(".Settings") && !result.description.contains(input.token),"counts only");
        input=new Input();
        check(input.read("shell prompt/command echo\n"+input.frame(CLEAR,0).replace("\n","\r\n"),null,150).owner
                ==InstrumentPanelCloseCoordinator.Owner.FREE,"framed echo and CRLF");
        input=new Input();
        check(input.read("\r\n"+input.frame(CLEAR,0)+"\r\n",null,150).owner
                ==InstrumentPanelCloseCoordinator.Owner.FREE,"transport preserving outer newlines");
        for(int failure=0;failure<8;failure++) {
            input=new Input(); String output=input.frame(CLEAR,0),error=null;long now=150;
            if(failure==0)output=CLEAR; // transport EOF without status
            if(failure==1)output=input.frame(CLEAR,1);
            if(failure==2)output=CLEAR+"\n"+input.token+":0"; // missing beginning / lost head
            if(failure==3)error="disconnected";
            if(failure==4)now=1601;
            if(failure==5)now=99;
            if(failure==6)output=input.frame(CLEAR+"x".repeat(130*1024),0);
            if(failure==7)output=input.frame(CLEAR,0)+"trailing prompt";
            check(input.read(output,error,now).owner==InstrumentPanelCloseCoordinator.Owner.UNKNOWN,"failure "+failure);
        }
        input=new Input();
        String own=CLEAR.replace("displayId=0","displayId=2").replace("/.Settings","/dezz.status.widget.instrument.InstrumentPanelActivity");
        check(input.read(input.frame(own,0),null,150).description.contains("reason=panel_still_visible"),"finishing window must settle");
        System.out.println("PASS framed owner query: trims/echo/CRLF, EOF/nonzero/lost-head/stale/error/oversize, private counts");
    }
}
