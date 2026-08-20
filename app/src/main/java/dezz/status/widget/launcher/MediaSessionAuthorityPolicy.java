/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

final class MediaSessionAuthorityPolicy {
    static final java.lang.String BLUETOOTH_PACKAGE = "com.android.bluetooth";
    static final int NONE = -1;

    static final class Candidate {
        final boolean current;
        final long generation;
        final java.lang.String id;
        final boolean playing;
        final long postTime;

        Candidate(java.lang.String str, boolean z, boolean z2, long j, long j2) {
            this.id = str;
            this.current = z;
            this.playing = z2;
            this.postTime = j;
            this.generation = j2;
        }
    }

    private MediaSessionAuthorityPolicy() {
    }

    static int choose(boolean z, boolean z2, java.util.List<dezz.status.widget.launcher.MediaSessionAuthorityPolicy.Candidate> list) {
        int iNewest;
        int i = 0;
        while (true) {
            if (i >= list.size()) {
                i = -1;
                break;
            }
            if (list.get(i).current) {
                break;
            }
            i++;
        }
        if (z || i == -1) {
            return newest(list, false, false);
        }
        return (z2 || (iNewest = newest(list, true, true)) == -1) ? i : iNewest;
    }

    private static int newest(java.util.List<dezz.status.widget.launcher.MediaSessionAuthorityPolicy.Candidate> list, boolean z, boolean z2) {
        int i = -1;
        for (int i2 = 0; i2 < list.size(); i2++) {
            dezz.status.widget.launcher.MediaSessionAuthorityPolicy.Candidate candidate = list.get(i2);
            if ((!z || candidate.playing) && ((!z2 || !candidate.current) && (i == -1 || newer(candidate, list.get(i))))) {
                i = i2;
            }
        }
        return i;
    }

    private static boolean newer(dezz.status.widget.launcher.MediaSessionAuthorityPolicy.Candidate candidate, dezz.status.widget.launcher.MediaSessionAuthorityPolicy.Candidate candidate2) {
        if (candidate.postTime != candidate2.postTime) {
            return candidate.postTime > candidate2.postTime;
        }
        return candidate.generation > candidate2.generation;
    }

    static boolean isBluetoothPackage(java.lang.String str) {
        return BLUETOOTH_PACKAGE.equals(str);
    }

    static boolean accepts(java.lang.String str, java.lang.String str2, boolean z) {
        if (str.equals(str2)) {
            return isBluetoothPackage(str) || z;
        }
        return false;
    }
}
