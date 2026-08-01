/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

/** Pure presence-aware merge rules for partial mHUD/mSaver media broadcasts. */
final class MediaBroadcastMergePolicy {
    private MediaBroadcastMergePolicy() {}

    static boolean sameSource(boolean hasPrevious, boolean packagePresent,
                              String incomingPackage, String previousPackage) {
        // An empty package is "unknown", not proof that the player changed. Several head-unit
        // publishers add package_name only after their first metadata packet.
        return hasPrevious && (!packagePresent || incomingPackage.isEmpty()
                || previousPackage.isEmpty() || incomingPackage.equals(previousPackage));
    }

    static boolean sameTrack(boolean sameSource, boolean titlePresent,
                             String incomingTitle, String previousTitle) {
        return sameSource && (!titlePresent || incomingTitle.equals(previousTitle));
    }

    static String text(boolean present, String incoming, boolean sameSource, String previous) {
        return !present && sameSource ? previous : incoming;
    }

    static long number(boolean present, long incoming, boolean sameSource, long previous) {
        return !present && sameSource ? previous : incoming;
    }

    static boolean flag(boolean present, boolean incoming, boolean sameSource, boolean previous) {
        return !present && sameSource ? previous : incoming;
    }
}
