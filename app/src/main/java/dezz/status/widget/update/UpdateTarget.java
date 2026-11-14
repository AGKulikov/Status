/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.update;

/** Fixed project channels. No user-supplied packages, URLs or signing keys. */
public enum UpdateTarget {
    NATRO("Natro", "ru.natro.statuswidget", "natro-update.json", 128),
    NAVIGATOR("Навигатор", "ru.yandex.yandexnavi", "navigator-update.json", 256),
    MUSIC("Музыка", "ru.yandex.music", "music-update.json", 128);

    public final String title, packageName, manifestName;
    public final long maxApkBytes;
    UpdateTarget(String title, String packageName, String manifestName, int maxMiB) {
        this.title=title; this.packageName=packageName; this.manifestName=manifestName;
        this.maxApkBytes=maxMiB*1024L*1024L;
    }
}
