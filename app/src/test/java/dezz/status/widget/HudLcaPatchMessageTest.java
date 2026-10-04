/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class HudLcaPatchMessageTest {
    @Test public void installButtonsStartLockedUntilModuleVerification(){
        try(org.robolectric.android.controller.ActivityController<HudLcaPatchActivity> controller=
                org.robolectric.Robolectric.buildActivity(HudLcaPatchActivity.class).setup()){
            android.view.ViewGroup content=controller.get().findViewById(android.R.id.content);
            int[] patchButtons={0};checkButtons(content,patchButtons);
            assertEquals(4,patchButtons[0]);
        }
    }
    private void checkButtons(android.view.View view,int[] found){
        if(view instanceof android.widget.Button){
            String label=((android.widget.Button)view).getText().toString();
            if(label.startsWith("Применить ")||label.equals("Восстановить оригинал")){assertFalse(view.isEnabled());found[0]++;}
            if(label.equals("Проверить модуль"))assertTrue(view.isEnabled());
        }
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup parent=(android.view.ViewGroup)view;
            for(int i=0;i<parent.getChildCount();i++)checkButtons(parent.getChildAt(i),found);}
    }
    @Test public void failedInspectionDoesNotAskToInspectAgainOrClaimBackupExists(){
        String text=HudLcaPatchActivity.failureMessage("Проверка модуля",new IOException("Код завершения: 40"));
        assertTrue(text.contains("Код завершения: 40"));
        assertTrue(text.contains("установка не выполнялась"));
        assertTrue(text.contains("наличие не подтверждено"));
        assertFalse(text.contains("Сначала выполните проверку"));
    }
    @Test public void failedInstallDoesNotClaimFileUnchanged(){
        String text=HudLcaPatchActivity.failureMessage("Применение Simple",new IOException("Соединение закрыто"));
        assertTrue(text.contains("Соединение закрыто"));
        assertTrue(text.contains("Автоматического повтора нет"));
        assertFalse(text.contains("установка не выполнялась"));
    }
}
