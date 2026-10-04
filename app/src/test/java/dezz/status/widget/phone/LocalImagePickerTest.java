/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;
import android.app.Application;
import android.net.Uri;
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
public class LocalImagePickerTest {
    private ActivityController<AppCompatActivity> activity(){
        ActivityController<AppCompatActivity> value=Robolectric.buildActivity(AppCompatActivity.class);
        value.get().setTheme(R.style.Theme_StatusWidget);return value.setup().visible();
    }
    private void click(ListView list,String label){
        for(int i=0;i<list.getCount();i++)if(label.equals(list.getItemAtPosition(i))){list.performItemClick(null,i,i);return;}
        fail("Missing picker row: "+label);
    }
    @Test public void selectsOwnFileWithoutDocumentsUiOrStartingAnyActivity()throws Exception {
        try(ActivityController<AppCompatActivity> controller=activity()){
            File root=controller.get().getExternalFilesDir(null);assertNotNull(root);
            File image=new File(root,"chosen.PNG");Files.write(image.toPath(),new byte[]{1});
            List<Uri> selected=new ArrayList<>();
            LocalImagePicker picker=new LocalImagePicker(controller.get(),Runnable::run,selected::add);picker.show();
            ListView list=ReflectionHelpers.getField(picker,"list");click(list,"Файлы Natro");click(list,"chosen.PNG");
            assertEquals(Collections.singletonList(Uri.fromFile(image)),selected);
            assertNull(Shadows.shadowOf(controller.get()).getNextStartedActivity());
        }
    }
    @Test public void cancelledDialogDiscardsLateDirectoryResults(){
        try(ActivityController<AppCompatActivity> controller=activity()){
            List<Runnable> pending=new ArrayList<>();List<Uri> selected=new ArrayList<>();
            LocalImagePicker picker=new LocalImagePicker(controller.get(),pending::add,selected::add);picker.show();
            AlertDialog dialog=ReflectionHelpers.getField(picker,"dialog");dialog.cancel();
            for(Runnable task:pending)task.run();
            assertTrue(selected.isEmpty());assertFalse(dialog.isShowing());
        }
    }
}
