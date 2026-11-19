/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import android.Manifest;
import android.app.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import dezz.status.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;
import java.io.*;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w1760dp-h656dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FileManagerScreenTest {
    @Test public void twoPanesAndRealSelectionRenderAtKx11Size()throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE);
        try(ActivityController<FileManagerActivity> controller=Robolectric.buildActivity(FileManagerActivity.class)) {
            FileManagerActivity activity=controller.setup().visible().get();
            Object[] panes=ReflectionHelpers.getField(activity,"panes");
            List<FileEntry> entries=Arrays.asList(new FileEntry("/Загрузки","Загрузки",true,0,0),new FileEntry("/Музыка","Музыка",true,0,0),new FileEntry("/Natro-3.4.0.apk","Natro-3.4.0.apk",false,30923456,1793977200000L));
            for(Object pane:panes){java.util.concurrent.Future<?> loading=ReflectionHelpers.getField(pane,"loading");if(loading!=null)loading.cancel(true);long gen=ReflectionHelpers.getField(pane,"generation");ReflectionHelpers.setField(pane,"generation",gen+1);ReflectionHelpers.setField(pane,"entries",entries);
                ReflectionHelpers.callInstanceMethod(activity,"render",ReflectionHelpers.ClassParameter.from((Class)pane.getClass(),pane));}
            ListView list=ReflectionHelpers.getField(panes[0],"list");assertEquals(3,list.getAdapter().getCount());
            list.getOnItemLongClickListener().onItemLongClick(list,null,0,0);
            assertEquals(1,((Set<?>)ReflectionHelpers.getField(panes[0],"selected")).size());
            click(activity,"Копировать");assertEquals(1,((List<?>)ReflectionHelpers.getField(activity,"clipboard")).size());
            View decor=activity.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(1760,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(656,View.MeasureSpec.EXACTLY));decor.layout(0,0,1760,656);
            for(Object pane:panes){View box=ReflectionHelpers.getField(pane,"box");assertEquals(View.VISIBLE,box.getVisibility());assertTrue(box.getWidth()>600);}
            Bitmap bitmap=Bitmap.createBitmap(1760,656,Bitmap.Config.ARGB_8888);decor.draw(new Canvas(bitmap));
            File folder=new File("build/settings-screenshots");assertTrue(folder.isDirectory()||folder.mkdirs());
            try(FileOutputStream out=new FileOutputStream(new File(folder,"file-manager-kx11.png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();
        }
    }
    @Test @Config(qualifiers="w720dp-h480dp-land-mdpi") public void compactLayoutSwitchesPanesWithoutLosingLocation()throws Exception {
        try(ActivityController<FileManagerActivity> controller=Robolectric.buildActivity(FileManagerActivity.class).setup()) {
            FileManagerActivity activity=controller.get();Object[] panes=ReflectionHelpers.getField(activity,"panes");
            assertEquals(View.GONE,((View)ReflectionHelpers.getField(panes[1],"box")).getVisibility());click(activity,"Другая панель");
            assertEquals(View.GONE,((View)ReflectionHelpers.getField(panes[0],"box")).getVisibility());assertEquals(View.VISIBLE,((View)ReflectionHelpers.getField(panes[1],"box")).getVisibility());
        }
    }
    @Test public void recentDownloadCanBeLocatedAndCopiedFromTheRealPane() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE);
        File folder = new File(Environment.getExternalStorageDirectory(), "Download/Natro-test");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        File download = new File(folder, "recent-ui-test.apk");
        assertTrue(download.createNewFile());
        try (ActivityController<FileManagerActivity> controller = Robolectric.buildActivity(FileManagerActivity.class).setup().visible()) {
            FileManagerActivity activity = controller.get();
            click(activity, "Недавние");
            AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            View decor = dialog.getWindow().getDecorView();
            EditText filter = findType(decor, EditText.class);
            filter.setText(download.getName());
            ListView recent = findType(decor, ListView.class);
            long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (recent.getCount() == 0 && System.nanoTime() < end) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10);
            }
            assertEquals(1, recent.getCount());
            assertEquals("/Download/Natro-test/recent-ui-test.apk", ((RecentFiles.Entry)recent.getItemAtPosition(0)).file.path);
            recent.getOnItemLongClickListener().onItemLongClick(recent, null, 0, 0);
            AlertDialog actions = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            actions.getListView().performItemClick(null, 1, 1);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Object[] panes = ReflectionHelpers.getField(activity, "panes");
            FileLocation location = ReflectionHelpers.getField(panes[0], "location");
            assertEquals("/Download/Natro-test", location.path);
            java.util.concurrent.Future<?> load = ReflectionHelpers.getField(panes[0], "loading");
            load.get(10, java.util.concurrent.TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            click(activity, "Копировать");
            List<FileEntry> clipboard = ReflectionHelpers.getField(activity, "clipboard");
            assertEquals(1, clipboard.size());
            assertEquals("/Download/Natro-test/recent-ui-test.apk", clipboard.get(0).path);
        } finally { download.delete(); folder.delete(); }
    }
    private static <T extends View> T findType(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)root;
            for (int i=0; i<group.getChildCount(); i++) {
                T result = findType(group.getChildAt(i), type);
                if (result != null) return result;
            }
        }
        return null;
    }

    @Test public void loginDoesNotAllowLookalikeHostsOrUnencryptedRedirects() {
        assertTrue(YandexDiskLoginActivity.allowed("https://passport.yandex.ru/auth"));
        assertFalse(YandexDiskLoginActivity.allowed("https://passport.yandex.ru.evil.test/"));
        assertFalse(YandexDiskLoginActivity.allowed("http://passport.yandex.ru/"));
        assertFalse(YandexDiskLoginActivity.allowed("file:///sdcard/a"));
        assertFalse(YandexDiskLoginActivity.allowed("https://user@passport.yandex.ru/"));
    }
    private static void click(Activity activity,String label){View button=find(activity.getWindow().getDecorView(),label);assertNotNull(label,button);button.performClick();}
    private static View find(View root,String label){if(root instanceof Button&&label.equals(((Button)root).getText().toString()))return root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View result=find(group.getChildAt(i),label);if(result!=null)return result;}}return null;}
}
