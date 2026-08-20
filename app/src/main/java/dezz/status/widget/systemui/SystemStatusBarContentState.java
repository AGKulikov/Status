/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.systemui;

final class SystemStatusBarContentState {
    private static final java.util.Set<java.lang.String> ANDROID_P_DEFAULTS = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet(java.util.Arrays.asList("rotate", "headset")));
    static final java.lang.String ORIGINAL_NULL_MARKER = "__natro_original_icon_blacklist_null__";

    enum DirectShellPreflight {
        WRITE_PLANNED,
        ALREADY_DESIRED,
        CHANGED_BY_OTHER,
        UNSAFE
    }

    static boolean isSafeExplicitRaw(java.lang.String str) {
        if (str != null && !str.isEmpty()) {
            int length = str.length();
            if (length <= 4096) {
                for (int i = 0; i < length; i++) {
                    char cCharAt = str.charAt(i);
                    if (cCharAt < ' ' || cCharAt > '~' || cCharAt == '\'') {
                        return false;
                    }
                }
                for (java.lang.String str2 : str.split(",", -1)) {
                    if (str2.length() > 128) {
                        return false;
                    }
                }
            }
            return false;
        }
        return true;
    }

    private SystemStatusBarContentState() {
    }

    static java.util.Set<java.lang.String> androidPDefaults() {
        return new java.util.LinkedHashSet(ANDROID_P_DEFAULTS);
    }

    static dezz.status.widget.systemui.SystemStatusBarContentState.Plan plan(java.lang.String str, java.util.Set<java.lang.String> set, java.util.Set<java.lang.String> set2, java.util.Set<java.lang.String> set3, boolean z) {
        java.util.Set explicit;
        if (!isSafeExplicitRaw(str)) {
            throw new java.lang.IllegalArgumentException("Unsafe explicit SystemUI icon blacklist");
        }
        java.util.Set<java.lang.String> setCleanSlots = cleanSlots(set);
        java.util.Set<java.lang.String> setCleanSlots2 = cleanSlots(set2);
        if (str == null) {
            explicit = new java.util.LinkedHashSet(setCleanSlots);
        } else {
            explicit = parseExplicit(str);
        }
        java.util.Set<java.lang.String> setCleanOwnedSlots = cleanOwnedSlots(set3);
        boolean z2 = set3 != null && set3.contains(ORIGINAL_NULL_MARKER);
        if (z) {
            boolean z3 = str != null ? z2 : true;
            for (java.lang.String str2 : setCleanSlots2) {
                if (explicit.add(str2)) {
                    setCleanOwnedSlots.add(str2);
                }
            }
            java.util.LinkedHashSet linkedHashSet = new java.util.LinkedHashSet(setCleanOwnedSlots);
            if (z3) {
                linkedHashSet.add(ORIGINAL_NULL_MARKER);
            }
            return new dezz.status.widget.systemui.SystemStatusBarContentState.Plan(serializeExplicit(explicit), explicit, linkedHashSet);
        }
        if (setCleanOwnedSlots.isEmpty() && !z2) {
            return new dezz.status.widget.systemui.SystemStatusBarContentState.Plan(str, explicit, java.util.Collections.emptySet());
        }
        explicit.removeAll(setCleanOwnedSlots);
        return new dezz.status.widget.systemui.SystemStatusBarContentState.Plan((z2 && explicit.equals(setCleanSlots)) ? null : serializeExplicit(explicit), explicit, java.util.Collections.emptySet());
    }

    static boolean readBackMatches(java.lang.String str, java.lang.String str2) {
        if (str2 == null) {
            return str == null;
        }
        if (str != null && isSafeExplicitRaw(str) && isSafeExplicitRaw(str2)) {
            return parseExplicit(str).equals(parseExplicit(str2));
        }
        return false;
    }

    static dezz.status.widget.systemui.SystemStatusBarContentState.DirectShellPreflight directShellPreflight(java.lang.String str, java.lang.String str2, java.lang.String str3) {
        if (!isSafeExplicitRaw(str)) {
            return dezz.status.widget.systemui.SystemStatusBarContentState.DirectShellPreflight.UNSAFE;
        }
        if (readBackMatches(str, str3)) {
            return dezz.status.widget.systemui.SystemStatusBarContentState.DirectShellPreflight.ALREADY_DESIRED;
        }
        if (readBackMatches(str, str2)) {
            return dezz.status.widget.systemui.SystemStatusBarContentState.DirectShellPreflight.WRITE_PLANNED;
        }
        return dezz.status.widget.systemui.SystemStatusBarContentState.DirectShellPreflight.CHANGED_BY_OTHER;
    }

    static java.lang.String pendingRollbackRaw(java.lang.String str, java.util.Set<java.lang.String> set, java.lang.String str2, java.lang.String str3) {
        if (!isSafeExplicitRaw(str) || !isSafeExplicitRaw(str2) || !isSafeExplicitRaw(str3)) {
            throw new java.lang.IllegalArgumentException("Unsafe pending SystemUI state");
        }
        java.util.Set<java.lang.String> setCleanSlots = cleanSlots(set);
        java.util.Set<java.lang.String> setEffectiveSlots = effectiveSlots(str2, setCleanSlots);
        java.util.Set<java.lang.String> setEffectiveSlots2 = effectiveSlots(str3, setCleanSlots);
        if (readBackMatches(str, str2)) {
            return str;
        }
        if (!readBackMatches(str, str3)) {
            java.util.Set<java.lang.String> setEffectiveSlots3 = effectiveSlots(str, setCleanSlots);
            java.util.LinkedHashSet linkedHashSet = new java.util.LinkedHashSet(setEffectiveSlots2);
            linkedHashSet.removeAll(setEffectiveSlots);
            java.util.LinkedHashSet linkedHashSet2 = new java.util.LinkedHashSet(setEffectiveSlots);
            linkedHashSet2.removeAll(setEffectiveSlots2);
            setEffectiveSlots3.removeAll(linkedHashSet);
            setEffectiveSlots3.addAll(linkedHashSet2);
            if (str2 == null && setEffectiveSlots3.equals(setCleanSlots)) {
                return null;
            }
            if (str2 == null || !setEffectiveSlots3.equals(setEffectiveSlots)) {
                return serializeExplicit(setEffectiveSlots3);
            }
        }
        return str2;
    }

    private static java.util.Set<java.lang.String> effectiveSlots(java.lang.String str, java.util.Set<java.lang.String> set) {
        return str == null ? new java.util.LinkedHashSet(set) : parseExplicit(str);
    }

    static java.lang.String rawDiagnostic(java.lang.String str) {
        if (str == null) {
            return "kind=absent";
        }
        java.lang.String str2 = ",length=" + str.length() + ",fingerprint=" + java.lang.Integer.toHexString(str.hashCode());
        if (str.isEmpty()) {
            return "kind=explicit-empty" + str2;
        }
        if (str.length() > 4096) {
            return "reason=too-long" + str2;
        }
        java.util.LinkedHashSet linkedHashSet = new java.util.LinkedHashSet();
        java.lang.String[] strArrSplit = str.split(",", -1);
        for (int i = 0; i < strArrSplit.length; i++) {
            java.lang.String str3 = strArrSplit[i];
            if (str3.isEmpty()) {
                return "reason=empty-token,token=" + i + str2;
            }
            if (str3.length() > 128) {
                return "reason=token-too-long,token=" + i + str2;
            }
            for (int i2 = 0; i2 < str3.length(); i2++) {
                char cCharAt = str3.charAt(i2);
                if ((cCharAt < 'A' || cCharAt > 'Z') && ((cCharAt < 'a' || cCharAt > 'z') && !((cCharAt >= '0' && cCharAt <= '9') || cCharAt == '_' || cCharAt == '.' || cCharAt == '-'))) {
                    return "reason=unsupported-character,token=" + i + ",offset=" + i2 + ",codepoint=" + ((int) cCharAt) + str2;
                }
            }
            if (!linkedHashSet.add(str3)) {
                return "reason=duplicate-token,token=" + i + str2;
            }
        }
        return "kind=safe,tokens=" + strArrSplit.length + str2;
    }

    static boolean isSafeSlot(java.lang.String str) {
        return (str == null || ORIGINAL_NULL_MARKER.equals(str) || !str.matches("[A-Za-z0-9_.-]{1,128}")) ? false : true;
    }

    static java.util.Set<java.lang.String> parseExplicit(java.lang.String str) {
        java.util.LinkedHashSet linkedHashSet = new java.util.LinkedHashSet();
        if (str != null) {
            for (java.lang.String str2 : str.split(",", -1)) {
                if (!str2.isEmpty()) {
                    linkedHashSet.add(str2);
                }
            }
        }
        return linkedHashSet;
    }

    static java.lang.String serializeExplicit(java.util.Set<java.lang.String> set) {
        return java.lang.String.join(",", set);
    }

    private static java.util.Set<java.lang.String> cleanSlots(java.util.Set<java.lang.String> set) {
        java.util.LinkedHashSet linkedHashSet = new java.util.LinkedHashSet();
        if (set != null) {
            for (java.lang.String str : set) {
                if (str != null) {
                    if (!isSafeSlot(str)) {
                        throw new java.lang.IllegalArgumentException("Unsafe SystemUI slot name");
                    }
                    linkedHashSet.add(str);
                }
            }
        }
        return linkedHashSet;
    }

    private static java.util.Set<java.lang.String> cleanOwnedSlots(java.util.Set<java.lang.String> set) {
        java.util.LinkedHashSet linkedHashSet = new java.util.LinkedHashSet();
        if (set != null) {
            for (java.lang.String str : set) {
                if (isSafeSlot(str)) {
                    linkedHashSet.add(str);
                }
            }
        }
        return linkedHashSet;
    }

    static final class Plan {
        final java.lang.String desiredRaw;
        final java.util.Set<java.lang.String> effectiveHiddenSlots;
        final java.util.Set<java.lang.String> storedOwnership;

        Plan(java.lang.String str, java.util.Set<java.lang.String> set, java.util.Set<java.lang.String> set2) {
            this.desiredRaw = str;
            this.effectiveHiddenSlots = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet(set));
            this.storedOwnership = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet(set2));
        }
    }
}
