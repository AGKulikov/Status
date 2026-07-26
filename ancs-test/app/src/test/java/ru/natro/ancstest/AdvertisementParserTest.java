package ru.natro.ancstest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.UUID;

public final class AdvertisementParserTest {
    @Test
    public void parsesAndroid9RawAncsServiceSolicitation() {
        UUID ancs = AncsProtocol.SERVICE;
        byte[] record = new byte[] {
                0x02, 0x01, 0x06,
                0x11, 0x15,
                (byte) 0xD0, 0x00, 0x2D, 0x12, 0x1E, 0x4B, 0x0F, (byte) 0xA4,
                (byte) 0x99, 0x4E, (byte) 0xCE, (byte) 0xB5, 0x31,
                (byte) 0xF4, 0x05, 0x79,
                0x05, 0x09, 'K', 'X', '1', '1',
                0x00
        };

        AdvertisementParser.Parsed parsed = AdvertisementParser.parse(record);

        assertTrue(parsed.solicits(ancs));
        assertEquals("KX11", parsed.localName);
        assertTrue(parsed.hex.contains("11 15 D0 00 2D 12"));
    }

    @Test
    public void normalServiceUuidIsNotMistakenForSolicitation() {
        byte[] record = AdvertisementParser.concat(
                new byte[] {0x11, 0x07},
                AdvertisementParser.uuidToBluetoothLittleEndian(AncsProtocol.SERVICE),
                new byte[] {0x00});

        assertFalse(AdvertisementParser.parse(record).solicits(AncsProtocol.SERVICE));
    }

    @Test
    public void truncatedElementFailsClosed() {
        byte[] record = new byte[] {0x11, 0x15, 0x01, 0x02, 0x03};
        assertTrue(AdvertisementParser.parse(record).solicitationUuids.isEmpty());
    }
}
