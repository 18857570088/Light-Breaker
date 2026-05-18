package com.zclei.lightbreaker.ble

enum class GloveHand(val displayName: String, val devicePrefix: String) {
    Left("左手", "BOXING#L"),
    Right("右手", "BOXING#R"),
    Unknown("未知", "BOXING#"),
}

data class GlovePacket(
    val hand: GloveHand,
    val packetNo: Int,
    val battery: Int,
    val gyroPunchCount: Int,
    val pressurePunchCount: Int,
    val gyroPower: Int,
    val pressurePower: Int,
    val rawHex: String,
) {
    val batteryText: String
        get() =
            when (battery) {
                101 -> "正在充电"
                102 -> "已充满"
                in 0..100 -> "$battery%"
                else -> "未知"
            }

    val effectivePower: Int
        get() = maxOf(gyroPower, pressurePower.takeIf { it > 0 } ?: 0)
}

object GlovePacketParser {
    private const val PACKET_LENGTH = 11
    private const val HEAD_0 = 0xD5
    private const val HEAD_1 = 0x5D
    private const val COMMAND_STATUS = 0x03

    val enableGyroCommand: ByteArray = byteArrayOf(0xC5.toByte(), 0x5C, 0x04, 0x01)
    val disableGyroCommand: ByteArray = byteArrayOf(0xC5.toByte(), 0x5C, 0x04, 0x00)

    fun handFromName(name: String?): GloveHand =
        when {
            LEFT_NAME_PATTERN.matches(name.orEmpty()) -> GloveHand.Left
            RIGHT_NAME_PATTERN.matches(name.orEmpty()) -> GloveHand.Right
            else -> GloveHand.Unknown
        }

    fun isSupportedName(name: String?): Boolean =
        handFromName(name) != GloveHand.Unknown

    fun parseOrNull(
        payload: ByteArray,
        hand: GloveHand,
    ): GlovePacket? {
        if (payload.size < PACKET_LENGTH) {
            return null
        }
        val offset = findPacketOffset(payload) ?: return null
        if (payload.size - offset < PACKET_LENGTH) {
            return null
        }
        val bytes = payload.copyOfRange(offset, offset + PACKET_LENGTH)
        if (u(bytes[2]) != COMMAND_STATUS) {
            return null
        }
        return GlovePacket(
            hand = hand,
            packetNo = u(bytes[3]),
            battery = u(bytes[4]),
            gyroPunchCount = u(bytes[5]),
            pressurePunchCount = u(bytes[6]),
            gyroPower = u(bytes[7]),
            pressurePower = u(bytes[8]),
            rawHex = bytes.toHexString(),
        )
    }

    fun parse(
        payload: ByteArray,
        hand: GloveHand,
    ): GlovePacket =
        parseOrNull(payload, hand)
            ?: throw IllegalArgumentException("Invalid glove packet: ${payload.toHexString()}")

    fun countDelta(
        previous: Int,
        current: Int,
    ): Int = (current - previous + 256) and 0xFF

    private fun findPacketOffset(payload: ByteArray): Int? {
        for (idx in 0..(payload.size - PACKET_LENGTH).coerceAtLeast(0)) {
            if (u(payload[idx]) == HEAD_0 && u(payload[idx + 1]) == HEAD_1) {
                return idx
            }
        }
        return null
    }

    private fun u(byte: Byte): Int = byte.toInt() and 0xFF

    private val LEFT_NAME_PATTERN = Regex("^BOXING#L[0-9A-Za-z]{6}$", RegexOption.IGNORE_CASE)
    private val RIGHT_NAME_PATTERN = Regex("^BOXING#R[0-9A-Za-z]{6}$", RegexOption.IGNORE_CASE)
}

fun ByteArray.toHexString(): String =
    joinToString(separator = " ") { byte -> "%02X".format(byte.toInt() and 0xFF) }
