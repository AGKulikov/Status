/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

/** Flavor-neutral, asynchronous ownership of the stock TSR preference. */
public interface InstrumentTsrAccess {
    interface Result { void complete(boolean success, String detail); }
    void setHidden(boolean hidden, Result result);
    void refresh();
    void close();
}
