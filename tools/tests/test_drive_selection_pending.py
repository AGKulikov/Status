"""Execute production selection state machine; no car or APK involved."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

class DriveSelectionPendingTest(unittest.TestCase):
    def test_pending_selection_and_deadlines(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp)
            (p/'Replay.java').write_text(r'''
import java.util.*;
import java.util.function.*;
import dezz.status.widget.drivemode.car.DriveModeSelection;
public class Replay {
 static long now;
 static final List<Integer> order=Arrays.asList(1,2,3,4);
 static class Host implements DriveModeSelection.Host {
  List<Integer> writes=new ArrayList<>(),shown=new ArrayList<>();
  List<Boolean> taps=new ArrayList<>(); Consumer<Boolean> callback;int failures;
  public void write(int mode,Consumer<Boolean> cb){writes.add(mode);callback=cb;}
  public void confirmed(int mode,boolean tapped){shown.add(mode);taps.add(tapped);}
  public void rejected(){failures++;}
  void ack(boolean ok){Consumer<Boolean> cb=callback;callback=null;cb.accept(ok);}
 }
 static void eq(Object a,Object b){if(!a.equals(b))throw new AssertionError(a+" != "+b);}
 static DriveModeSelection fresh(Host h){now=0;DriveModeSelection s=new DriveModeSelection(h,()->now);s.observe(1);return s;}
 public static void main(String[] args){
  Host h=new Host();DriveModeSelection s=fresh(h);
  s.step(order,1,750);s.step(order,1,750);s.step(order,1,750);
  eq(h.writes,Arrays.asList(2));eq(s.selected(),4);eq(h.shown.size(),0);
  now=100;h.ack(true);eq(h.writes,Arrays.asList(2,4));eq(h.shown,Arrays.asList(2));
  h.ack(true);eq(s.selected(),4);eq(s.busy(),false);
  h=new Host();s=fresh(h);s.step(order,1,750);s.step(order,-1,750);h.ack(true);
  eq(h.writes,Arrays.asList(2,1));h.ack(true);eq(s.selected(),1);
  h=new Host();s=fresh(h);s.step(order,1,750);s.step(order,1,750);h.ack(false);
  eq(h.writes,Arrays.asList(2));eq(h.failures,1);eq(s.selected(),-1);s.observe(1);eq(s.selected(),1);
  h=new Host();s=fresh(h);s.step(order,1,750);s.step(order,1,750);now=751;h.ack(true);
  eq(h.writes,Arrays.asList(2));eq(s.selected(),2);
  h=new Host();s=fresh(h);now=800;s.step(order,1,750);eq(h.writes.size(),0);
  h=new Host();s=fresh(h);s.step(order,1,750);Consumer<Boolean> late=h.callback;s.cancel();late.accept(true);
  eq(h.shown.size(),0);eq(s.busy(),false);
  h=new Host();s=fresh(h);s.observe(255);s.observe(-1);eq(s.selected(),1);
  s.step(order,-1,750);eq(h.writes,Arrays.asList(4));h.ack(true);
  h=new Host();s=fresh(h);s.select(3,750,true);h.ack(true);eq(h.taps,Arrays.asList(true));
  h=new Host();s=fresh(h);s.step(order,1,750);s.step(order,1,750);s.observe(2);s.step(order,1,750);
  eq(s.selected(),4);h.ack(true);eq(h.writes,Arrays.asList(2,4));
  h=new Host();s=fresh(h);s.step(order,1,750);now=700;s.step(order,1,1450);now=900;h.ack(true);
  eq(h.writes,Arrays.asList(2,3));
  h=new Host();s=fresh(h);s.select(1,750,true);eq(h.writes.size(),0);eq(h.shown,Arrays.asList(1));
  System.out.println("11 selection scenarios passed");
 }
}''')
            source=ROOT/'app/src/main/java/dezz/status/widget/drivemode/car/DriveModeSelection.java'
            subprocess.run(['java','com.sun.tools.javac.Main','-d',str(p),str(source),str(p/'Replay.java')],check=True,capture_output=True,text=True)
            result=subprocess.run(['java','-cp',str(p),'Replay'],check=True,capture_output=True,text=True)
            self.assertIn('11 selection scenarios passed',result.stdout)
