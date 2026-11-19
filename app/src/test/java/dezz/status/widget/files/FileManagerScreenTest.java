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
        assertTrue(download.setLastModified(System.currentTimeMillis() - 1000));
        try (ActivityController<FileManagerActivity> controller = Robolectric.buildActivity(FileManagerActivity.class).setup().visible()) {
            FileManagerActivity activity = controller.get();
            click(activity, "Недавние");
            AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            View decor = dialog.getWindow().getDecorView();
            EditText filter = findType(decor, EditText.class);
            filter.setText(download.getName());
            ListView recent = findType(decor, ListView.class);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
            awaitCount(recent, 1);
            RecentFiles.Group group = (RecentFiles.Group) recent.getItemAtPosition(0);
            assertEquals("/Download/Natro-test/recent-ui-test.apk", group.preview.get(0).file.path);
            recent.performItemClick(null, 0, 0);
            AlertDialog detail = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            ListView members = findType(detail.getWindow().getDecorView(), ListView.class);
            awaitCount(members, 1);
            members.getOnItemLongClickListener().onItemLongClick(members, null, 0, 0);
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
    @Test public void recentCardsExpandAndRememberDocumentTypes() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE);
        File folder = new File(Environment.getExternalStorageDirectory(), "Pictures/Screenshots/Natro-cards-test");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        List<File> created = new ArrayList<>();
        try (ActivityController<FileManagerActivity> controller = Robolectric.buildActivity(FileManagerActivity.class).setup().visible()) {
            FileManagerActivity activity = controller.get();
            View screen = activity.getWindow().getDecorView();
            Bitmap original = Bitmap.createBitmap(1760, 656, Bitmap.Config.ARGB_8888);
            layout(screen, 1760, 656);
            screen.draw(new Canvas(original));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, png));
            original.recycle();
            long modified = System.currentTimeMillis() - 2000;
            for (int i = 0; i < 9; i++) {
                File file = new File(folder, "Снимок Natro " + i + (i == 8 ? ".txt" : ".png"));
                assertTrue(file.createNewFile());
                created.add(file);
                if (i < 8) try (OutputStream output = new FileOutputStream(file)) { png.writeTo(output); }
                assertTrue(file.setLastModified(modified));
            }

            click(activity, "Недавние");
            AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            View view = dialog.getWindow().getDecorView();
            findType(view, EditText.class).setText("Снимок Natro");
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
            ListView groups = findType(view, ListView.class);
            awaitCount(groups, 1);
            RecentFiles.Group group = (RecentFiles.Group) groups.getItemAtPosition(0);
            assertEquals(8, group.count);
            assertEquals(4, group.preview.size());
            saveRecentCardScreen(screen, view, groups);
            groups.performItemClick(null, 0, 0);
            AlertDialog detail = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            ListView members = findType(detail.getWindow().getDecorView(), ListView.class);
            awaitCount(members, 8);
            assertEquals("Снимок Natro 7.png", ((RecentFiles.Entry) members.getItemAtPosition(7)).file.name);
            detail.dismiss();
            find(view, "Типы файлов").performClick();
            AlertDialog types = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            int txt = RecentFiles.Category.TXT.ordinal();
            assertFalse(types.getListView().isItemChecked(txt));
            types.getListView().performItemClick(null, txt, txt);
            types.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            awaitCount(groups, 2);
            dialog.dismiss();
            click(activity, "Недавние");
            AlertDialog reopened = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            View reopenedView = reopened.getWindow().getDecorView();
            findType(reopenedView, EditText.class).setText("Снимок Natro");
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
            awaitCount(findType(reopenedView, ListView.class), 2);
        } finally {
            for (File file : created) file.delete();
            folder.delete();
        }
    }

    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static int thumbnails(View view) {
        if (view instanceof ImageView && ((ImageView) view).getDrawable() != null) return 1;
        if (!(view instanceof ViewGroup)) return 0;
        ViewGroup group = (ViewGroup) view;
        int count = 0;
        for (int i = 0; i < group.getChildCount(); i++) count += thumbnails(group.getChildAt(i));
        return count;
    }

    private static void saveRecentCardScreen(View activity, View dialog, ListView groups) throws Exception {
        int width = 1760, height = 656;
        int windowWidth = (int) (width * .90), windowHeight = (int) (height * .88);
        layout(dialog, windowWidth, windowHeight);
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (thumbnails(groups) != 4 && System.nanoTime() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertEquals(4, thumbnails(groups));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        activity.draw(canvas);
        canvas.drawColor(0x66000000);
        canvas.translate((width - windowWidth) / 2f, (height - windowHeight) / 2f);
        dialog.draw(canvas);
        File folder = new File("build/settings-screenshots");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (OutputStream output = new FileOutputStream(new File(folder, "file-manager-recents-kx11.png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }

    private static void awaitCount(ListView view, int count) throws InterruptedException {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (view.getCount() != count && System.nanoTime() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertEquals(count, view.getCount());
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
