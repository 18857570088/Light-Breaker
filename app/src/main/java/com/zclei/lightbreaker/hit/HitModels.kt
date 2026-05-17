package com.zclei.lightbreaker.hit

import com.zclei.lightbreaker.ble.GloveHand
import com.zclei.lightbreaker.ble.GlovePacket
import com.zclei.lightbreaker.ble.GlovePacketParser

data class HitEvent(
    val hand: GloveHand,
    val timestampMs: Long,
    val intensity: Int,
    val sourceCount: Int,
)

class GloveHitAccumulator(
    private val maxEventsPerPacket: Int = 12,
) {
    private val lastCounts = mutableMapOf<GloveHand, Int>()

    fun accept(
        packet: GlovePacket,
        nowMs: Long,
    ): List<HitEvent> {
        val previous = lastCounts.put(packet.hand, packet.gyroPunchCount)
        if (previous == null) {
            return emptyList()
        }

        val delta = GlovePacketParser.countDelta(previous, packet.gyroPunchCount)
        if (delta <= 0 || delta > maxEventsPerPacket) {
            return emptyList()
        }

        return List(delta) { index ->
            HitEvent(
                hand = packet.hand,
                timestampMs = nowMs + index,
                intensity = packet.effectivePower,
                sourceCount = (previous + index + 1) and 0xFF,
            )
        }
    }

    fun reset() {
        lastCounts.clear()
    }
}
