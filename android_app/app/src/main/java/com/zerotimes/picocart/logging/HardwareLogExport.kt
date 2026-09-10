package com.zerotimes.picocart.logging

import com.zerotimes.picocart.protocol.PicoProtocol

/** A snapshot is complete only after its header, every unique record, and matching end arrive. */
internal class HardwareLogExport(private val retiredIds: Set<Long> = emptySet()) {
    var exportId: Long? = null
        private set
    var total = 0
        private set
    var lastSequence = 0L
        private set
    var finished = false
        private set
    var error: String? = null
        private set
    private val records = mutableMapOf<Int, String>()
    private val sequences = mutableSetOf<Long>()
    private val receivedLines = mutableListOf<String>()
    private var firmwareVersion = ""
    val received: Int get() = records.size
    val rawLines: List<String> get() = receivedLines.toList()
    val complete: Boolean get() = finished && error == null

    enum class Result { IGNORED, PROGRESS, COMPLETE, FAILED }

    fun accept(raw: String): Result {
        if (finished) return Result.IGNORED
        val parsed = PicoProtocol.parseLine(raw)
        val header = raw.startsWith("ok hwlog_export ")
        if (!header && parsed.type !in setOf("hwlog", "hwlog_end")) return Result.IGNORED
        val id = parsed["x"]?.toLongOrNull()
        if (id != null && (id in retiredIds || (exportId != null && id != exportId))) {
            return Result.IGNORED
        }
        if (header) {
            if (exportId != null) {
                return if (receivedLines.firstOrNull() == raw) Result.IGNORED else fail("冲突的导出起始标记", raw)
            }
            val count = parsed["n"]?.toIntOrNull()
            val last = parsed["last"]?.toLongOrNull()
            val firmware = parsed["fw"]
            if (id == null || id <= 0 || count == null || count !in 0..4096 || last == null || last < count || firmware.isNullOrBlank()) {
                return fail("导出起始标记无效", raw)
            }
            exportId = id
            total = count
            lastSequence = last
            firmwareVersion = firmware
            receivedLines += raw
            return Result.PROGRESS
        }
        if (exportId == null) return fail("未收到导出起始标记", raw)
        if (id == null || parsed["n"]?.toIntOrNull() != total || parsed["fw"] != firmwareVersion) {
            return fail("导出标记、条数或固件版本不一致", raw)
        }
        if (parsed.type == "hwlog_end") {
            receivedLines += raw
            if (parsed["last"]?.toLongOrNull() != lastSequence || received != total) {
                return fail("日志不完整：收到 $received/$total 条")
            }
            finished = true
            return Result.COMPLETE
        }
        val index = parsed["i"]?.toIntOrNull()
        val sequence = parsed["s"]?.toLongOrNull()
        if (index == null || index !in 1..total || sequence != lastSequence - total + index) {
            return fail("日志序号缺失或不一致", raw)
        }
        if ((parsed["t"]?.toLongOrNull() ?: -1) < 0 || (parsed["u"]?.toLongOrNull() ?: -1) < 0 || parsed["e"].isNullOrBlank()) {
            return fail("日志时间或事件字段不完整", raw)
        }
        records[index]?.let { previous ->
            return if (previous == raw) Result.IGNORED else fail("重复序号的日志内容冲突", raw)
        }
        if (!sequences.add(sequence)) return fail("日志序列号重复", raw)
        records[index] = raw
        receivedLines += raw
        return Result.PROGRESS
    }

    fun fail(reason: String, raw: String? = null): Result {
        if (raw != null) receivedLines += raw
        error = reason
        finished = true
        return Result.FAILED
    }
}
