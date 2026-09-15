"""Notification payload parsing against small Android data adapters, not Android rendering."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "androidx/annotation/Nullable.java": "package androidx.annotation;public @interface Nullable {}",
    "android/os/SystemClock.java": "package android.os;public class SystemClock {public static long elapsedRealtime(){return 5000;}}",
    "android/os/Bundle.java": """package android.os;public class Bundle extends java.util.HashMap<String,Object>{
 public static final Bundle EMPTY=new Bundle();public Bundle getBundle(String k){return (Bundle)get(k);}
 public CharSequence[] getCharSequenceArray(String k){return (CharSequence[])get(k);}}""",
    "android/graphics/Bitmap.java": "package android.graphics;public class Bitmap {}",
    "android/graphics/drawable/Icon.java": "package android.graphics.drawable;public class Icon {}",
    "android/media/session/MediaSession.java": "package android.media.session;public class MediaSession {public static class Token {}}",
    "android/media/MediaMetadata.java": """package android.media;public class MediaMetadata {
 public static final String METADATA_KEY_TITLE="title",METADATA_KEY_ARTIST="artist",METADATA_KEY_AUTHOR="author",
 METADATA_KEY_WRITER="writer",METADATA_KEY_COMPOSER="composer",METADATA_KEY_ALBUM="album";}""",
    "android/app/Notification.java": """package android.app;public class Notification {
 public static final String EXTRA_MEDIA_SESSION="token",EXTRA_TITLE="title",EXTRA_TEXT="text",EXTRA_SUB_TEXT="subtext",EXTRA_TEXT_LINES="lines",EXTRA_LARGE_ICON="large",CATEGORY_TRANSPORT="transport";
 public android.os.Bundle extras=new android.os.Bundle();public String category;
 public android.graphics.drawable.Icon getLargeIcon(){return null;}}""",
    "android/service/notification/StatusBarNotification.java": """package android.service.notification;public class StatusBarNotification {
 final String pkg;final android.app.Notification n;public StatusBarNotification(String p,android.app.Notification n){pkg=p;this.n=n;}
 public android.app.Notification getNotification(){return n;}public String getKey(){return pkg+":media";}
 public String getPackageName(){return pkg;}public long getPostTime(){return 1000;}}""",
    "PayloadReplay.java": """import dezz.status.widget.launcher.MediaDisplayNotification;
import android.app.Notification;import android.service.notification.StatusBarNotification;import android.media.session.MediaSession;
public class PayloadReplay {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static MediaDisplayNotification parse(String pkg,Notification n,long sequence){return MediaDisplayNotification.read(new StatusBarNotification(pkg,n),sequence);}
 public static void main(String[] ignored){
  Notification n=new Notification();n.extras.put(Notification.EXTRA_TITLE,"Turn right");
  check(parse("ru.yandex.yandexnavi",n,1)==null,"ordinary guidance is not music");
  MediaSession.Token token=new MediaSession.Token();n.extras.put(Notification.EXTRA_MEDIA_SESSION,token);
  check(parse("ru.yandex.yandexnavi",n,2).title.isEmpty(),"navigation token uses session song text");
  n.extras.put(Notification.EXTRA_TITLE,"Song B");n.extras.put(Notification.EXTRA_TEXT,"Artist");n.extras.put(Notification.EXTRA_SUB_TEXT,"Album");
  MediaDisplayNotification spotify=parse("com.spotify.music",n,3);
  check(spotify.title.equals("Song B")&&spotify.artist.equals("Artist • Album"),"Spotify display text");
  check(spotify.trackTitle.equals("Song B")&&spotify.trackArtist.equals("Artist"),"decorated artist must still correlate the timeline");
  android.graphics.Bitmap cover=new android.graphics.Bitmap();n.extras.put(Notification.EXTRA_LARGE_ICON,cover);
  MediaDisplayNotification a=parse("ru.yandex.music",n,4);n.extras.put(Notification.EXTRA_TITLE,"Song C");
  MediaDisplayNotification b=parse("ru.yandex.music",n,5);
  check(!a.samePublication(b),"new title is new publication even with unchanged post time");
  check(a.artwork==b.artwork&&b.artwork==cover,"same album cover on adjacent songs remains valid");
  check(b.samePublication(parse("ru.yandex.music",n,6)),"recovery scan reuses unchanged publication");
  n.extras.put(Notification.EXTRA_MEDIA_SESSION,new MediaSession.Token());
  check(!b.samePublication(parse("ru.yandex.music",n,7)),"new token replaces old owner");
  n.extras.put(Notification.EXTRA_TEXT_LINES,new CharSequence[]{"Artist - Song D"});
  MediaDisplayNotification lines=parse("player",n,8);check(lines.title.equals("Song D")&&lines.artist.equals("Artist"),"one-line format");
  n.extras.put(Notification.EXTRA_TEXT_LINES,42);check(parse("player",n,9)==null,"malformed Bundle cannot crash listener");
 }
}"""
}


class MediaNotificationPayload(unittest.TestCase):
    def test_player_formats_track_identity_and_malformed_payload(self):
        with tempfile.TemporaryDirectory(prefix="natro-media-payload-") as tmp:
            folder = Path(tmp)
            paths = []
            for name, source in SOURCES.items():
                path = folder / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
                paths.append(str(path))
            paths.append(str(ROOT / "app/src/main/java/dezz/status/widget/launcher/MediaDisplayNotification.java"))
            result = subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(folder), *paths], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", str(folder), "PayloadReplay"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
