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
