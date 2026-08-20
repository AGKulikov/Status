/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

public final class AncsDeliveryTraceV2 {
    private boolean cccdsReady;
    private long controlPointWrites;
    private long dataSourceBytes;
    private long dataSourceFragments;
    private long decodedNotifications;
    private long preExistingRecords;
    private long sessionId;
    private long sourceRecords;

    public void begin(long j) {
        this.sessionId = j;
        this.sourceRecords = 0L;
        this.preExistingRecords = 0L;
        this.controlPointWrites = 0L;
        this.dataSourceFragments = 0L;
        this.dataSourceBytes = 0L;
        this.decodedNotifications = 0L;
        this.cccdsReady = false;
    }

    public java.lang.String cccdsReady() {
        this.cccdsReady = true;
        return prefix() + "cccd ns=confirmed ds=confirmed parser=armed; waiting_first_event";
    }

    public java.lang.String notificationSource(byte[] bArr) {
        if (bArr == null || bArr.length == 0 || bArr.length % 8 != 0) {
            return prefix() + "source malformed bytes=" + (bArr != null ? bArr.length : 0);
        }
        int length = bArr.length / 8;
        this.sourceRecords = saturatedAdd(this.sourceRecords, length);
        int i = 0;
        int i2 = 0;
        int i3 = 0;
        int i4 = 0;
        int i5 = -1;
        int i6 = 0;
        for (int i7 = 0; i7 < bArr.length; i7 += 8) {
            dezz.status.widget.phone.transport.AncsProtocol.Event event = dezz.status.widget.phone.transport.AncsProtocol.parseEvent(bArr, i7);
            if (event != null) {
                if (event.eventId == 0) {
                    i++;
                } else if (event.eventId == 1) {
                    i2++;
                } else if (event.eventId == 2) {
                    i3++;
                }
                if (dezz.status.widget.phone.transport.AncsProtocol.isPreExisting(event)) {
                    i6++;
                }
                if (i5 < 0) {
                    i5 = event.categoryId;
                }
                i4 |= event.flags;
            }
        }
        this.preExistingRecords = saturatedAdd(this.preExistingRecords, i6);
        return prefix() + "source records=" + this.sourceRecords + " packetRecords=" + length + " added=" + i + " modified=" + i2 + " removed=" + i3 + " preExisting=" + i6 + " category=" + i5 + " flags=0x" + java.lang.Integer.toHexString(i4).toUpperCase(java.util.Locale.ROOT);
    }

    public java.lang.String controlPointWrite(dezz.status.widget.phone.transport.v2.AncsRequestTokenV2.Kind kind, boolean z) {
        if (z) {
            this.controlPointWrites = saturatedAdd(this.controlPointWrites, 1L);
        }
        return prefix() + "control_point kind=" + (kind == null ? "unknown" : kind.name()) + " started=" + z + " writes=" + this.controlPointWrites;
    }

    public java.lang.String controlPointResult(dezz.status.widget.phone.transport.v2.AncsRequestTokenV2.Kind kind, int i) {
        return prefix() + "control_point_result kind=" + (kind == null ? "unknown" : kind.name()) + " status=" + i;
    }

    public java.lang.String dataSource(byte[] bArr) {
        int length = bArr == null ? 0 : bArr.length;
        this.dataSourceFragments = saturatedAdd(this.dataSourceFragments, 1L);
        this.dataSourceBytes = saturatedAdd(this.dataSourceBytes, length);
        return prefix() + "data_source fragments=" + this.dataSourceFragments + " fragmentBytes=" + length + " totalBytes=" + this.dataSourceBytes;
    }

    public java.lang.String decodedNotification() {
        this.decodedNotifications = saturatedAdd(this.decodedNotifications, 1L);
        return prefix() + "decoded_notifications=" + this.decodedNotifications;
    }

    public java.lang.String terminal(java.lang.String str) {
        return prefix() + "terminal stage=" + safe(str) + " " + counters();
    }

    public java.lang.String closed() {
        return prefix() + "closed " + counters();
    }

    private java.lang.String counters() {
        return "cccdReady=" + this.cccdsReady + " source=" + this.sourceRecords + " preExisting=" + this.preExistingRecords + " cpWrites=" + this.controlPointWrites + " dsFragments=" + this.dataSourceFragments + " dsBytes=" + this.dataSourceBytes + " decoded=" + this.decodedNotifications;
    }

    private java.lang.String prefix() {
        return "ancs session=" + this.sessionId + " ";
    }

    private static java.lang.String safe(java.lang.String str) {
        if (str == null) {
            return "unknown";
        }
        java.lang.String strReplace = str.trim().replace('\n', ' ').replace('\r', ' ');
        return strReplace.length() > 48 ? strReplace.substring(0, 48) : strReplace;
    }

    private static long saturatedAdd(long j, long j2) {
        if (j2 <= 0) {
            return j;
        }
        if (j > Long.MAX_VALUE - j2) {
            return Long.MAX_VALUE;
        }
        return j + j2;
    }
}
