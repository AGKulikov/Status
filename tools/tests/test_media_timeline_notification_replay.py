"""Actual notification parser, identity policy and publication method with Android data doubles.

No player/device behaviour is emulated: this isolates the title-notice rejection and
proves that fixing it does not admit a stale adjacent track or another session.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_media_notification_payload import SOURCES
from test_map_visibility_recovery import method

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/dezz/status/widget/launcher"


class MediaTimelineNotificationReplay(unittest.TestCase):
    def test_notice_publication_and_adjacent_track_fences(self):
        publisher = method((JAVA / "LauncherMediaController.java").read_text(),
                           "private boolean publishLiveNotification(")
        code = r'''package dezz.status.widget.launcher;
import android.app.Notification;
import android.graphics.Bitmap;
import android.media.session.MediaSession;
import android.service.notification.StatusBarNotification;
public class TimelineNotificationReplay {
 static class MediaState {
  String packageName="ru.yandex.music",title="Лапами вверх",artist="Звери";
  long durationMs=215000,positionMs=74000;boolean playing=true;Bitmap artwork=new Bitmap();
  long currentPosition(long now){return positionMs;}
 }
 static class Current {MediaSession.Token token;MediaSession.Token getSessionToken(){return token;}}
 static class VolumeState {int steps=5,maximum=10;int percent(){return 50;}}
 static class LikeUiState {boolean available=true,active=true;}
 static class Snapshot {
  final String title;final long duration,position;final Bitmap artwork;
  Snapshot(String t,String a,String album,String app,Bitmap art,long d,long p,boolean play,
   boolean present,boolean like,boolean liked,int volume,int steps,int max){title=t;duration=d;position=p;artwork=art;}
 }
 static class Listener {Snapshot snapshot;void onMediaChanged(Snapshot s){snapshot=s;}}
 static class MediaPlaybackHistoryStore {static void record(Object c,String p,boolean playing){}}
 static class Publisher {
  MediaDisplayNotification displayNotification;String timelineMediaId="";
  Current current=new Current();Bitmap notificationArtwork;
  String visiblePackage,visibleTitle,visibleArtist,evidence;
  Object context=new Object();Listener listener=new Listener();
  boolean samePackage(String a,String b){return a.equals(b);}
  String applicationLabel(String pkg){return pkg;}
  LikeUiState resolveLikeUiState(String pkg){return new LikeUiState();}
  void scheduleTicker(boolean playing){}
  void traceTimeline(String e,long d,long p,boolean owner){evidence=e;}
  PUBLISH_METHOD
 }
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 static MediaDisplayNotification notice(MediaSession.Token token,String title,String artist,String id){
  Notification n=new Notification();n.extras.put(Notification.EXTRA_MEDIA_SESSION,token);
  n.extras.put(Notification.EXTRA_TITLE,title);n.extras.put(Notification.EXTRA_TEXT,artist);
  if(!id.isEmpty()){
   android.os.Bundle m=new android.os.Bundle();m.put(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID,id);
   n.extras.put("android.mediaMetadata",m);
  }
  return MediaDisplayNotification.read(new StatusBarNotification("ru.yandex.music",n),1);
 }
 public static void main(String[] args){
  Publisher p=new Publisher();MediaState session=new MediaState();VolumeState volume=new VolumeState();
  MediaSession.Token token=new MediaSession.Token();p.current.token=token;
  for(String prefix:new String[]{"ⓘ ","ℹ️ ","⚠\u00a0"}){
   p.displayNotification=notice(token,prefix+session.title,session.artist,"");
   check(p.publishLiveNotification(session,volume),"publication");
   Snapshot s=p.listener.snapshot;
   check(s.duration==215000 && s.position==74000,"notice must not erase real session time");
   check(s.title.equals(prefix+session.title),"visible notice must remain intact");
   check(s.artwork==session.artwork,"proven same track may use session artwork");
   check(p.evidence.equals("track_text_notice"),"bounded diagnostic reason");
  }
  p.displayNotification=notice(token,"ⓘ Next song",session.artist,"");
  p.publishLiveNotification(session,volume);
  check(p.listener.snapshot.duration==0 && p.listener.snapshot.position==0,"late previous session must not bleed");
  session.title="Next song";session.durationMs=123000;session.positionMs=1000;
  p.publishLiveNotification(session,volume);
  check(p.listener.snapshot.duration==123000 && p.listener.snapshot.position==1000,"new metadata recovers timeline");
  p.timelineMediaId="old";p.displayNotification=notice(token,"ⓘ Next song",session.artist,"new");
  p.publishLiveNotification(session,volume);
  check(p.evidence.equals("media_id_mismatch") && p.listener.snapshot.duration==0,"ID mismatch wins");
  p.timelineMediaId="";p.displayNotification=notice(new MediaSession.Token(),"ⓘ Next song",session.artist,"");
  p.publishLiveNotification(session,volume);
  check(p.evidence.equals("owner_mismatch") && p.listener.snapshot.position==0,"new owner wins");
  p.displayNotification=notice(token,"ⓘ Next song","Other performer","");p.publishLiveNotification(session,volume);
  check(p.listener.snapshot.duration==0,"different performer remains rejected");
  p.displayNotification=notice(token,"ⓘ Next song",session.artist,"");
  p.publishLiveNotification(null,volume);
  check(p.listener.snapshot.duration==0,"no source must not invent time");
  session.durationMs=0;session.positionMs=24000;p.publishLiveNotification(session,volume);
  check(p.listener.snapshot.duration==0 && p.listener.snapshot.position==24000,"partial real time remains usable");
 }
}'''.replace("PUBLISH_METHOD", publisher)
        with tempfile.TemporaryDirectory(prefix="natro-timeline-") as tmp:
            folder = Path(tmp)
            files = []
            for name, text in SOURCES.items():
                if name == "PayloadReplay.java":
                    continue
                path = folder / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
                files.append(str(path))
            harness = folder / "TimelineNotificationReplay.java"
            harness.write_text(code)
            files += [str(harness), str(JAVA / "MediaTimelineIdentity.java"),
                      str(JAVA / "MediaDisplayNotification.java")]
            compiled = subprocess.run(["java", "com.sun.tools.javac.Main", "-encoding", "UTF-8",
                                       "-d", tmp, *files], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            run = subprocess.run(["java", "-cp", tmp,
                                  "dezz.status.widget.launcher.TimelineNotificationReplay"],
                                 capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)


if __name__ == "__main__":
    unittest.main()
