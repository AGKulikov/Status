/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;
import android.view.View;
/** Read-only canvas supplied by a concrete editor, never guessed from arbitrary child views. */
public interface SettingsPreviewProvider { View settingsPreview(); }
