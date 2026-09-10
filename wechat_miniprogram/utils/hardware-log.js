const { parseLine } = require('./protocol')

const MAX_LINE_LENGTH = 400
const LOG_CAPACITY = 192

function integer(value, minimum) {
  if (!/^\d+$/.test(String(value))) return null
  const result = Number(value)
  return Number.isSafeInteger(result) && result >= minimum ? result : null
}

// Preserve fragmented BLE lines; discard an oversized line in full, never its prefix.
function createLineReader(onLine, onOverflow) {
  let pending = ''
  let dropping = false
  return {
    push(chunk) {
      for (const character of String(chunk)) {
        if (character === '\r' || character === '\n') {
          if (!dropping && pending.trim()) onLine(pending.trim())
          pending = ''
          dropping = false
        } else if (!dropping) {
          pending += character
          if (pending.length > MAX_LINE_LENGTH) {
            pending = ''
            dropping = true
            onOverflow()
          }
        }
      }
    }
  }
}

function createExport() {
  return { header: null, records: [], errors: [], ended: false }
}

function addError(state, reason) {
  if (state.errors.indexOf(reason) === -1) state.errors.push(reason)
}

function acceptExportLine(state, line) {
  if (state.ended) return 'ignored'
  const parsed = parseLine(line)
  if (/^ok hwlog_export(?:\s|$)/.test(line)) {
    if (state.header) return 'ignored'
    const count = integer(parsed.n, 0)
    const id = integer(parsed.x, 1)
    const last = integer(parsed.last, 0)
    if (count === null || count > LOG_CAPACITY || id === null || last === null || !parsed.fw) {
      addError(state, '导出起始信息无效')
      return 'invalid'
    }
    state.header = { count, id, last, firmware: parsed.fw, raw: line }
    return 'progress'
  }
  if (parsed.type !== 'hwlog' && parsed.type !== 'hwlog_end') return 'ignored'
  if (!state.header || integer(parsed.x, 1) !== state.header.id) return 'ignored'
  const header = state.header
  if (integer(parsed.n, 0) !== header.count || parsed.fw !== header.firmware) {
    addError(state, '日志总数或固件版本不一致')
  }
  if (parsed.type === 'hwlog_end') {
    state.ended = true
    state.endLine = line
    if (integer(parsed.last, 0) !== header.last) addError(state, '结束标记序号不一致')
    if (state.records.length !== header.count) addError(state, '记录缺失或总数不匹配')
    const sorted = state.records.slice().sort((a, b) => a.index - b.index)
    sorted.forEach((record, index) => {
      if (record.index !== index + 1) addError(state, '日志序号缺失')
      if (record.sequence !== header.last - header.count + index + 1) addError(state, '硬件记录序号缺失或顺序错误')
    })
    return state.errors.length ? 'incomplete' : 'complete'
  }
  const index = integer(parsed.i, 1)
  const sequence = integer(parsed.s, 1)
  if (index === null || index > header.count || sequence === null ||
      integer(parsed.t, 0) === null || integer(parsed.u, 0) === null || !parsed.e) {
    addError(state, '日志字段无效')
    return 'invalid'
  }
  if (state.records.some((record) => record.index === index || record.sequence === sequence)) {
    addError(state, '收到重复日志序号')
    return 'invalid'
  }
  if (state.records.length >= LOG_CAPACITY) {
    addError(state, '日志超过缓存容量')
    return 'invalid'
  }
  state.records.push({ index, sequence, raw: line })
  return 'progress'
}

function finishExport(state, reason) {
  if (!state.ended) addError(state, reason || '未收到结束标记')
  state.ended = true
  return {
    complete: !!state.header && state.errors.length === 0,
    header: state.header,
    endLine: state.endLine || '',
    records: state.records.slice().sort((a, b) => a.index - b.index),
    errors: state.errors.slice()
  }
}

function readHardwareStatus(parsed) {
  const count = integer(parsed.n, 0)
  const capacity = integer(parsed.cap, 1)
  const percent = integer(parsed.used_pct, 0)
  const overwritten = integer(parsed.overwritten, 0)
  if (capacity === null || count === null || count > capacity || percent === null || percent > 100 ||
      overwritten === null || !parsed.fw || !/^[01]$/.test(parsed.synced) || !/^[01]$/.test(parsed.exporting)) return null
  return { count, capacity, percent, overwritten, firmware: parsed.fw, synced: parsed.synced === '1', exporting: parsed.exporting === '1' }
}

module.exports = { createLineReader, createExport, acceptExportLine, finishExport, addError, readHardwareStatus }
