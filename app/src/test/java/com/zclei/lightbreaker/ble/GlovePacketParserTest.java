package com.zclei.lightbreaker.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GlovePacketParserTest {
    @Test
    public void parsesProtocolPacket() {
        byte[] payload = new byte[] {
            (byte) 0xD5,
            0x5D,
            0x03,
            0x7A,
            88,
            12,
            0,
            (byte) 170,
            0,
            0,
            0,
        };

        GlovePacket packet = GlovePacketParser.INSTANCE.parse(payload, GloveHand.Left);

        assertEquals(GloveHand.Left, packet.getHand());
        assertEquals(0x7A, packet.getPacketNo());
        assertEquals(88, packet.getBattery());
        assertEquals(12, packet.getGyroPunchCount());
        assertEquals(0, packet.getPressurePunchCount());
        assertEquals(170, packet.getGyroPower());
        assertEquals("88%", packet.getBatteryText());
        assertEquals("D5 5D 03 7A 58 0C 00 AA 00 00 00", packet.getRawHex());
    }

    @Test
    public void rejectsBadHeaderAndCommand() {
        assertNull(GlovePacketParser.INSTANCE.parseOrNull(new byte[] {0, 1, 2}, GloveHand.Left));
        assertNull(
            GlovePacketParser.INSTANCE.parseOrNull(
                new byte[] {(byte) 0xD5, 0x5D, 0x04, 0, 0, 0, 0, 0, 0, 0, 0},
                GloveHand.Left
            )
        );
    }

    @Test
    public void handlesBatteryStatesAndCountWrap() {
        GlovePacket charging = GlovePacketParser.INSTANCE.parse(packetWithBattery(101), GloveHand.Right);
        GlovePacket full = GlovePacketParser.INSTANCE.parse(packetWithBattery(102), GloveHand.Right);

        assertEquals("正在充电", charging.getBatteryText());
        assertEquals("已充满", full.getBatteryText());
        assertEquals(3, GlovePacketParser.INSTANCE.countDelta(254, 1));
        assertEquals(1, GlovePacketParser.INSTANCE.countDelta(255, 0));
        assertTrue(GlovePacketParser.INSTANCE.isSupportedName("BOXING#PL000001"));
        assertTrue(GlovePacketParser.INSTANCE.isSupportedName("BOXING#PR000001"));
    }

    private byte[] packetWithBattery(int battery) {
        return new byte[] {(byte) 0xD5, 0x5D, 0x03, 0, (byte) battery, 0, 0, 0, 0, 0, 0};
    }
}
