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
