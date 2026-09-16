/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

/** Same package processes as MConfig's restart, without its arbitrary substring pkill match. */
final class InputServiceProcessIdentity {
    private static final String PACKAGE = "ecarx.xsf.inputservice";
    static boolean matches(String name) {
        return PACKAGE.equals(name) || (name != null && name.startsWith(PACKAGE + ":")
                && name.substring(PACKAGE.length() + 1).matches("[A-Za-z0-9_.]+"));
    }
}
