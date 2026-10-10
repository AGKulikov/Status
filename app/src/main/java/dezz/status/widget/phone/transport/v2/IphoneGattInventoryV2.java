/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/** Capability inventory produced after one exact service-discovery operation. */
public final class IphoneGattInventoryV2 {
    public final boolean helperV2Service;
    public final boolean peerProofReadable;
    public final boolean telemetryNotifiable;
    public final boolean routeControlWritable;
    public final boolean routeControlIndicatable;
    public final boolean ancsService;
    public final boolean notificationSourceNotifiable;
    public final boolean controlPointWritable;
    public final boolean dataSourceNotifiable;
    public final boolean serviceChangedIndicatable;

    public IphoneGattInventoryV2(boolean helperV2Service, boolean peerProofReadable,
                                 boolean telemetryNotifiable,
                                 boolean routeControlWritable,
                                 boolean routeControlIndicatable,
                                 boolean ancsService,
                                 boolean notificationSourceNotifiable,
                                 boolean controlPointWritable,
                                 boolean dataSourceNotifiable,
                                 boolean serviceChangedIndicatable) {
        this.helperV2Service = helperV2Service;
        this.peerProofReadable = peerProofReadable;
        this.telemetryNotifiable = telemetryNotifiable;
        this.routeControlWritable = routeControlWritable;
        this.routeControlIndicatable = routeControlIndicatable;
        this.ancsService = ancsService;
        this.notificationSourceNotifiable = notificationSourceNotifiable;
        this.controlPointWritable = controlPointWritable;
        this.dataSourceNotifiable = dataSourceNotifiable;
        this.serviceChangedIndicatable = serviceChangedIndicatable;
    }

    public boolean completeForAndroidCentral() {
        return completeHelperV2() && completeAncs();
    }

    /** Only known capability flags; no addresses, identifiers or notification payloads. */
    public String diagnosticCapabilities() {
        return "helper:" + helperV2Service + "/proof_read:" + peerProofReadable
                + "/telemetry_notify:" + telemetryNotifiable
                + "/control_write:" + routeControlWritable
                + "/control_indicate:" + routeControlIndicatable
                + "/ancs:" + ancsService + "/notification_notify:" + notificationSourceNotifiable
                + "/control_point_write:" + controlPointWritable
                + "/data_notify:" + dataSourceNotifiable;
    }

    public boolean completeHelperV2() {
        return helperV2Service && peerProofReadable
                && routeControlWritable && routeControlIndicatable;
    }

    public boolean completeAncs() {
        return ancsService && notificationSourceNotifiable
                && controlPointWritable && dataSourceNotifiable;
    }
}
