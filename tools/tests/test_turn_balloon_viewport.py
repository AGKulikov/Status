"""Exercise actual destination geometry and collision decisions at edges and different zooms."""
from pathlib import Path
import subprocess, tempfile, unittest
ROOT=Path(__file__).resolve().parents[2]
class TurnViewportTest(unittest.TestCase):
 def test_viewport_edges_sizes_and_independent_projections(self):
  sources={
   "RectF.java": """package android.graphics;
public class RectF {public float left,top,right,bottom;
 public RectF(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}
 public RectF(RectF r){this(r.left,r.top,r.right,r.bottom);}
 public void inset(float x,float y){left+=x;right-=x;top+=y;bottom-=y;}}""",
   "Point.java": """package com.yandex.mapkit.geometry;
public class Point {public Point(double x,double y){}}""",
   "ViewportReplay.java": """package ru.natro.navigation;
import android.graphics.RectF;import java.util.*;import com.yandex.mapkit.geometry.Point;
public class ViewportReplay {
 public static class Screen {float x,y;Screen(float x,float y){this.x=x;this.y=y;}
 public float getX(){return x;}public float getY(){return y;}}
 public static class Window {float x,y;Window(float x,float y){this.x=x;this.y=y;}
 public Screen worldToScreen(Point p){return new Screen(x,y);}}
 static void check(boolean b){if(!b)throw new AssertionError();}
 static boolean run(int vw,int vh,float x,float y,float requested){
  return run(vw,vh,x,y,requested,false);
 }
 static boolean run(int vw,int vh,float x,float y,float requested,boolean obstruction){
  MapOverlayPlacementCoordinator c=new MapOverlayPlacementCoordinator();c.attach(new Window(x,y),vw,vh);
  if(obstruction)c.reserveCentered("camera","obstacle",55,37,34,34);
  float scale=TurnBalloonLayout.fitScale(100,140,vw,vh,requested);
  int w=Math.round(100*scale),h=Math.round(140*scale),gap=Math.max(3,Math.round(8*scale));
  List<MapOverlayPlacementCoordinator.Footprint> f=new ArrayList<>();
  for(String side:MapOverlayPlacementCoordinator.placementLegNames()){
   for(int v=0;v<3;v++){TurnBalloonLayout l=new TurnBalloonLayout(w,h,gap,side,v);
   f.add(new MapOverlayPlacementCoordinator.Footprint(side,l.width,l.height,l.anchorX(),l.anchorY(),new RectF(l.left,l.top,l.right,l.bottom),v));}
  }
  MapOverlayPlacementCoordinator.Placement p=c.reserveIfClear("turn","same",55,37,w,h,true,10,0,null,f);
  if(p==null)return false;
  if(obstruction)check(p.variant>0);
  TurnBalloonLayout l=new TurnBalloonLayout(w,h,gap,p.legName,p.variant);
  float bx=x-l.tipX+l.left,by=y-l.tipY+l.top;
  check(bx>=5.99 && by>=5.99 && bx+w<=vw-5.99 && by+h<=vh-5.99);
  check(c.isBodyInsideViewport(55,37,l.tipX,l.tipY,l.left,l.top,l.right,l.bottom));
  return true;
 }
 public static void main(String[] args){
  int count=0;
  for(int[] size:new int[][]{{728,190},{800,360},{1280,480},{200,90}})
   for(float scale:new float[]{.5f,1f,1.5f,2.5f})
    for(float x:new float[]{-200,0,3,size[0]/2f,size[0]-3,size[0],size[0]+200})
     for(float y:new float[]{-200,0,3,size[1]/2f,size[1]-3,size[1],size[1]+200}){run(size[0],size[1],x,y,scale);count++;}
  check(count==784);
  check(!run(728,190,364,-50,1f));
  check(run(800,360,400,180,1f));
  check(run(728,190,364,95,1f,true));
 }
}"""}
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);files=[]
   for name,body in sources.items():
    (p/name).write_text(body);files.append(str(p/name))
   base=ROOT/'navigator-mod/src/main/java/ru/natro/navigation'
   files += [str(base/n) for n in ['TurnBalloonLayout.java','MapOverlayPlacementCoordinator.java','ReflectMethods.java']]
   r=subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*files],capture_output=True,text=True)
   self.assertEqual(r.returncode,0,r.stderr)
   r=subprocess.run(['java','-cp',d,'ru.natro.navigation.ViewportReplay'],capture_output=True,text=True)
   self.assertEqual(r.returncode,0,r.stderr)
