package com.zerotimes.picocart.ble

import java.io.ByteArrayOutputStream

/** UART lines can span many notifications, including boundaries inside a UTF-8 character. */
internal class BleLineAssembler(private val maxLineBytes: Int = 4096) {
    private val pending = ByteArrayOutputStream()
    private var discarding = false

    fun reset() {
        pending.reset()
        discarding = false
    }

    fun accept(bytes: ByteArray, onLine: (String) -> Unit, onOverflow: () -> Unit) {
        bytes.forEach { byte ->
            if (byte == 10.toByte() || byte == 13.toByte()) {
                if (!discarding && pending.size() > 0) {
                    pending.toByteArray().toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }?.let(onLine)
                }
                reset()
            } else if (!discarding) {
                if (pending.size() == maxLineBytes) {
                    pending.reset()
                    discarding = true
                    onOverflow()
                } else {
                    pending.write(byte.toInt())
                }
            }
        }
    }
}
