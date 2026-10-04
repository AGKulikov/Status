/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;

import android.app.Application;
import android.net.Uri;
import android.os.Environment;
import android.widget.ListView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import dezz.status.widget.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class BackupFilePickerTest {
    private ActivityController<AppCompatActivity> activity(){
        ActivityController<AppCompatActivity> value=Robolectric.buildActivity(AppCompatActivity.class);
        value.get().setTheme(R.style.Theme_StatusWidget);return value.setup().visible();
    }
    private void click(ListView list,String label){
        for(int i=0;i<list.getCount();i++)if(label.equals(list.getItemAtPosition(i))){list.performItemClick(null,i,i);return;}
        fail("Missing backup row: "+label);
    }
    @Test public void opensArchiveWithoutDocumentsUiAndDoesNotReportSelectionAsCancellation()throws Exception {
        try(ActivityController<AppCompatActivity> controller=activity()){
            File root=Environment.getExternalStorageDirectory();assertTrue(root.isDirectory()||root.mkdirs());
            File archive=new File(root,"chosen.natrobackup");Files.write(archive.toPath(),new byte[]{1});
            List<Uri> selected=new ArrayList<>();int[] cancellations={0};
            BackupFilePicker picker=new BackupFilePicker(controller.get(),Runnable::run,selected::add,()->cancellations[0]++);picker.show(null);
            ListView list=ReflectionHelpers.getField(picker,"list");click(list,"Память головного устройства");click(list,"chosen.natrobackup");
            assertEquals(Collections.singletonList(Uri.fromFile(archive)),selected);assertEquals(0,cancellations[0]);
            assertNull(Shadows.shadowOf(controller.get()).getNextStartedActivity());
        }
    }
    @Test public void exportSelectionDoesNotCreateAnArchiveBeforeEncryption()throws Exception {
        try(ActivityController<AppCompatActivity> controller=activity()){
            File root=Environment.getExternalStorageDirectory();assertTrue(root.isDirectory()||root.mkdirs());
            List<Uri> selected=new ArrayList<>();
            BackupFilePicker picker=new BackupFilePicker(controller.get(),Runnable::run,selected::add,()->fail("selection cancelled"));picker.show("new.natrobackup");
            click(ReflectionHelpers.getField(picker,"list"),"Память головного устройства");
            File target=new File(root,"Natro-Backups/new.natrobackup");
            assertEquals(Collections.singletonList(Uri.fromFile(target)),selected);assertFalse(target.exists());
        }
    }
    @Test public void cancelledDialogDiscardsLateDirectoryResultsAndClearsPasswordOnce(){
        try(ActivityController<AppCompatActivity> controller=activity()){
            List<Runnable> pending=new ArrayList<>();List<Uri> selected=new ArrayList<>();int[] cancellations={0};
            BackupFilePicker picker=new BackupFilePicker(controller.get(),pending::add,selected::add,()->cancellations[0]++);picker.show(null);
            AlertDialog dialog=ReflectionHelpers.getField(picker,"dialog");dialog.cancel();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            for(Runnable task:pending)task.run();picker.close();
            assertTrue(selected.isEmpty());assertFalse(dialog.isShowing());assertEquals(1,cancellations[0]);
        }
    }
}
