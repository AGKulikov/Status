/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

public final class PostBondFacadeResolverV2 {
    public static final int TYPE_CLASSIC = 1;
    public static final int TYPE_DUAL = 3;
    public static final int TYPE_LE = 2;

    public enum Path {
        ACTIVE_FACADE_BONDED,
        SELECTED_CLASSIC_COALESCED_TO_LE,
        UNIQUE_NEW_BONDED_FACADE,
        WAITING,
        AMBIGUOUS
    }

    public static final class Facade {
        public final java.lang.String address;
        public final boolean bonded;
        public final int type;

        public Facade(java.lang.String str, int i, boolean z) {
            this.address = dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.canonical(str);
            this.type = i;
            this.bonded = z;
        }

        boolean leCapable() {
            int i = this.type;
            return i == 2 || i == 3;
        }
    }

    public static final class Result {
        public final dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Path path;
        public final java.lang.String postBondAddress;

        private Result(dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Path path, java.lang.String str) {
            this.path = path;
            this.postBondAddress = str;
        }

        public boolean resolved() {
            return !this.postBondAddress.isEmpty();
        }
    }

    private PostBondFacadeResolverV2() {
    }

    public static dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Result resolve(java.lang.String str, java.lang.String str2, java.util.Set<java.lang.String> set, java.util.Set<java.lang.String> set2, java.util.List<dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Facade> list) {
        java.lang.String strCanonical = canonical(str);
        java.lang.String strCanonical2 = canonical(str2);
        java.util.Set<java.lang.String> setCanonicalSet = canonicalSet(set);
        java.util.Set<java.lang.String> setCanonicalSet2 = canonicalSet(set2);
        if (list == null) {
            list = java.util.Collections.emptyList();
        }
        dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Facade facadeUnique = unique(list, strCanonical, false);
        if (facadeUnique != null && facadeUnique.bonded && facadeUnique.leCapable()) {
            return new dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Result(dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Path.ACTIVE_FACADE_BONDED, facadeUnique.address);
        }
        dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Facade facadeUnique2 = unique(list, strCanonical2, false);
        if (facadeUnique2 != null && facadeUnique2.bonded && facadeUnique2.leCapable()) {
            return new dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Result(dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Path.SELECTED_CLASSIC_COALESCED_TO_LE, facadeUnique2.address);
        }
        java.util.ArrayList arrayList = new java.util.ArrayList();
        for (dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Facade facade : list) {
            if (facade.bonded && facade.leCapable() && !facade.address.isEmpty() && !setCanonicalSet.contains(facade.address) && setCanonicalSet2.contains(facade.address)) {
                arrayList.add(facade);
            }
        }
        if (arrayList.size() == 1) {
            return new dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Result(dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Path.UNIQUE_NEW_BONDED_FACADE, ((dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Facade) arrayList.get(0)).address);
        }
        return new dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Result(arrayList.size() > 1 ? dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Path.AMBIGUOUS : dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Path.WAITING, "");
    }

    private static dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Facade unique(java.util.List<dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Facade> list, java.lang.String str, boolean z) {
        if (str.isEmpty()) {
            return null;
        }
        int i = 0;
        dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Facade facade = null;
        for (dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Facade facade2 : list) {
            if (str.equals(facade2.address)) {
                i++;
                facade = facade2;
            }
        }
        if (i == 1) {
            return facade;
        }
        return null;
    }

    private static java.util.Set<java.lang.String> canonicalSet(java.util.Set<java.lang.String> set) {
        if (set == null || set.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        java.util.HashSet hashSet = new java.util.HashSet();
        java.util.Iterator<java.lang.String> it = set.iterator();
        while (it.hasNext()) {
            java.lang.String strCanonical = canonical(it.next());
            if (!strCanonical.isEmpty()) {
                hashSet.add(strCanonical);
            }
        }
        return hashSet;
    }

    public static java.lang.String canonical(java.lang.String str) {
        if (str == null) {
            return "";
        }
        java.lang.String upperCase = str.trim().toUpperCase(java.util.Locale.US);
        if (upperCase.length() != 17) {
            return "";
        }
        for (int i = 0; i < upperCase.length(); i++) {
            char cCharAt = upperCase.charAt(i);
            if (i % 3 == 2) {
                if (cCharAt != ':') {
                    return "";
                }
            } else if ((cCharAt < '0' || cCharAt > '9') && (cCharAt < 'A' || cCharAt > 'F')) {
                return "";
            }
        }
        return upperCase;
    }
}
