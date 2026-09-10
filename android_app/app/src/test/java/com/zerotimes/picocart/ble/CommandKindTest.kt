package com.zerotimes.picocart.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandKindTest {
    @Test
    fun stopOutranksQueuedMotionAndExport() {
        val kinds = listOf("hwlog dump", "f 0.16", "keepalive", "stop").map { commandKind(it) }
        assertEquals(
            listOf("regular", "movement", "movement", "hard_stop"),
            kinds,
        )
    }

    companion object {
        fun commandKind(command: String): String = when (command.substringBefore(' ').lowercase()) {
            "stop", "s" -> "hard_stop"
            "softstop" -> "soft_stop"
            "f", "b", "l", "r", "drive", "keepalive", "motor", "motortest" -> "movement"
            else -> "regular"
        }
    }
}
