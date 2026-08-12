/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/** Controller boundary for the complete two-route runtime. */
public interface IphoneDualTransportListenerV2 extends IphoneTransportListenerV2 {
    void onDualTransportStatus(IphoneDualTransportStatusV2 status);
}
