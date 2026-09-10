const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')
const { createLineReader, createExport, acceptExportLine, finishExport, readHardwareStatus } = require('../utils/hardware-log')
const { parseLine } = require('../utils/protocol')

const header = 'ok hwlog_export n=2 x=17 last=8 fw=0.2.0'
const first = 'hwlog x=17 i=1 n=2 s=7 t=200 u=0 fw=0.2.0 e=boot'
const second = 'hwlog x=17 i=2 n=2 s=8 t=500 u=1789012345500 fw=0.2.0 e=time_sync'
const end = 'hwlog_end n=2 x=17 last=8 fw=0.2.0'
const status = 'ok hwlog_status n=180 cap=192 used_pct=93 overwritten=10 exporting=0 fw=0.2.0 synced=1'

function result(lines, reason) {
  const state = createExport()
  lines.forEach((line) => acceptExportLine(state, line))
  return finishExport(state, reason)
}

test('complete snapshot checks IDs, counts and sequence; unrelated export packets are ignored', () => {
  const value = result([header, first.replace('x=17', 'x=16'), second, first, end.replace('x=17', 'x=16'), end])
  assert.equal(value.complete, true)
  assert.deepEqual(value.records.map((record) => record.sequence), [7, 8])
  assert.equal(value.endLine, end)
  assert.ok(value.records[0].raw.includes('u=0'))
})

test('empty snapshot is complete with an explicit end marker', () => {
  assert.equal(result(['ok hwlog_export n=0 x=1 last=0 fw=0.2.0', 'hwlog_end n=0 x=1 last=0 fw=0.2.0']).complete, true)
})

test('missing, duplicate, corrupted, oversized counts and missing end never pass integrity', () => {
  const cases = [
    [header, first, end], [header, first, second], [header, first, first, second, end],
    [header, first, second.replace('s=8', 's=7'), end],
    [header, first, second.replace('i=2', 'i=3'), end],
    [header, first, second.replace('s=8', 's=9'), end],
    [header, first, second, end.replace('n=2', 'n=1')],
    [header, first, second, end.replace('last=8', 'last=9')],
    [header, first, second.replace('u=1789012345500', 'u=bad'), end],
    [header.replace('n=2', 'n=193'), first, second, end],
    [header, first, second.replace('fw=0.2.0', 'fw=0.1.0'), end]
  ]
  cases.forEach((lines) => assert.equal(result(lines).complete, false, lines.join('\n')))
})

test('BLE line reader keeps a fragmented 400-character line and reports/discards overflow', () => {
  const lines = []
  let errors = 0
  const reader = createLineReader((line) => lines.push(line), () => { errors += 1 })
  const large = `hwlog ${'x'.repeat(394)}`
  reader.push(large.slice(0, 240))
  reader.push(large.slice(240) + '\r\n')
  reader.push('y'.repeat(401))
  reader.push('truncated_tail\nstat mode=idle\n')
  assert.deepEqual(lines, [large, 'stat mode=idle'])
  assert.equal(errors, 1)
})

test('cache status requires valid counts, percent, booleans and firmware', () => {
  assert.equal(readHardwareStatus(parseLine(status)).percent, 93)
  assert.equal(readHardwareStatus(parseLine(status.replace('n=180', 'n=193'))), null)
  assert.equal(readHardwareStatus(parseLine(status.replace('used_pct=93', 'used_pct=101'))), null)
  assert.equal(readHardwareStatus(parseLine(status.replace('synced=1', 'synced=yes'))), null)
})

function pageHarness(options) {
  const settings = options || {}
  const timers = new Map()
  let timerId = 0
  const files = []
  const modals = []
  const commands = []
  const listeners = new Map()
  const wx = {
    env: { USER_DATA_PATH: '/phone' },
    canIUse: () => false,
    showToast() {},
    showModal(args) { modals.push(args); args.success({ confirm: !!settings.confirm }) },
    createBLEConnection(args) { args.success({}) },
    notifyBLECharacteristicValueChange(args) { args.success({}) },
    closeBLEConnection(args) { if (args.success) args.success({}) },
    closeBluetoothAdapter() {},
    getFileSystemManager() { return { writeFile(args) {
      files.push(args)
      if (settings.saveFailure) args.fail({ errMsg: 'disk full' })
      else if (!settings.deferSave) args.success({})
    } } }
  }
  for (const name of ['BluetoothDeviceFound', 'BLECharacteristicValueChange', 'BluetoothAdapterStateChange', 'BLEConnectionStateChange']) {
    wx[`on${name}`] = (listener) => listeners.set(name, listener)
    wx[`off${name}`] = (listener) => { assert.equal(listeners.get(name), listener); listeners.delete(name) }
  }
  let page
  const filePath = path.resolve(__dirname, '../pages/index/index.js')
  const context = {
    wx, require: createRequire(filePath), Uint8Array, TextEncoder, Date, Promise,
    Page(definition) { page = definition },
    setTimeout(callback, ms) {
      const id = ++timerId
      if (ms <= 400) queueMicrotask(callback)
      else timers.set(id, { callback, ms, interval: false })
      return id
    },
    clearTimeout(id) { timers.delete(id) },
    setInterval(callback, ms) { const id = ++timerId; timers.set(id, { callback, ms, interval: true }); return id },
    clearInterval(id) { timers.delete(id) }
  }
  vm.runInNewContext(fs.readFileSync(filePath, 'utf8'), context, { filename: filePath })
  page.data = JSON.parse(JSON.stringify(page.data))
  page.setData = (data) => Object.assign(page.data, data)
  page.onLoad()
  page.enqueueCommand = page.sendCommand.bind(page)
  page.sendCommand = async (command) => { commands.push(command); return true }
  page.pickUartChannel = async () => ({ serviceId: 'service', writeCharId: 'write', notifyCharId: 'notify', writeProperties: {} })
  page.data.connected = true
  page.data.deviceId = 'pico'
  page.data.deviceName = 'Test Pico'
  return { page, files, modals, commands, timers, listeners,
    fire(ms) { const entry = Array.from(timers.entries()).find(([, timer]) => timer.ms === ms); assert.ok(entry, `timer ${ms}`); if (!entry[1].interval) timers.delete(entry[0]); entry[1].callback() } }
}

async function settle() { for (let i = 0; i < 10; i += 1) await Promise.resolve() }
async function completeExport(harness) {
  await harness.page.exportHardwareLogs()
  for (const line of [header, first, second, end]) harness.page.applyLine(line)
  await settle()
}

test('notification-ready initialization sends time sync first; reconnect has one poll timer and unload removes listeners', async () => {
  const h = pageHarness()
  h.page.data.connected = false
  assert.equal(await h.page.connectDevice({ deviceId: 'pico', name: 'Test' }), true)
  assert.match(h.commands[0], /^time sync \d+$/)
  assert.deepEqual(h.commands.slice(1), ['info', 'hwlog status', 'status', 'param'])
  h.page.startDiagnosticsPolling()
  assert.equal(Array.from(h.timers.values()).filter((timer) => timer.interval).length, 1)
  h.page.onUnload()
  assert.equal(h.listeners.size, 0)
  assert.equal(h.timers.size, 0)
})

test('time sync waits for the matching acknowledgement; timeout permits retry', async () => {
  const h = pageHarness()
  await h.page.syncTime()
  const unix = h.page.timeSyncPending.unix
  h.page.applyLine(`time synced=1 unix_ms=${unix} uptime_ms=1 fw=0.2.0 request_ms=${unix - 1}`)
  assert.equal(h.page.data.timeSyncState, '同步中')
  h.page.applyLine(`time synced=1 unix_ms=${unix + 10} uptime_ms=11 fw=0.2.0 request_ms=${unix}`)
  assert.equal(h.page.data.timeSyncState, '成功')
  await h.page.syncTime()
  h.fire(8000)
  assert.equal(h.page.data.timeSyncState, '失败')
  assert.equal(h.page.timeSyncPending, null)
})

test('capacity poll warns at 90 percent and never imports logs', () => {
  const h = pageHarness()
  h.page.applyLine(status)
  assert.equal(h.page.data.firmware, '0.2.0')
  assert.match(h.page.data.diagnosticsMessage, /主动同步/)
  h.page.startDiagnosticsPolling()
  h.fire(15000)
  assert.deepEqual(h.commands, ['hwlog status'])
})

test('successful export is saved before a scoped cleanup confirmation; capacity updates only after acknowledgement', async () => {
  const h = pageHarness({ deferSave: true, confirm: true })
  await completeExport(h)
  assert.equal(h.files.length, 1)
  assert.equal(h.modals.length, 0)
  assert.ok(h.files[0].data.includes('complete=1'))
  assert.ok(h.files[0].data.includes(end))
  h.files[0].success({})
  await settle()
  assert.equal(h.modals.length, 1)
  assert.deepEqual(h.commands, ['hwlog dump', 'hwlog clear 8'])
  assert.equal(h.page.data.clearPending, true)
  h.page.applyLine('ok hwlog_clear n=3')
  assert.equal(h.page.data.clearPending, false)
  assert.equal(h.commands[h.commands.length - 1], 'hwlog status')
})

test('failed local save and late save completion after reconnect never offer cleanup', async () => {
  const failure = pageHarness({ saveFailure: true, confirm: true })
  await completeExport(failure)
  assert.equal(failure.modals.length, 0)
  assert.equal(failure.page.data.exportCanSave, true)
  assert.deepEqual(failure.commands, ['hwlog dump'])
  const late = pageHarness({ deferSave: true, confirm: true })
  await completeExport(late)
  late.page.invalidateConnection('disconnected')
  late.files[0].success({})
  await settle()
  assert.equal(late.modals.length, 0)
  assert.deepEqual(late.commands, ['hwlog dump'])
})

test('timeout preserves an explicitly incomplete file without offering cleanup', async () => {
  const h = pageHarness({ confirm: true })
  await h.page.exportHardwareLogs()
  h.page.applyLine(header)
  h.page.applyLine(first)
  h.fire(15000)
  assert.equal(h.page.data.exportCanSave, true)
  assert.match(h.page.data.exportMessage, /不完整/)
  h.page.saveReceivedHardwareLogs()
  await settle()
  assert.ok(h.files[0].data.includes('complete=0'))
  assert.equal(h.modals.length, 0)
  assert.deepEqual(h.commands, ['hwlog dump'])
})

test('disconnect preserves received data and resets fragmented line state for the next connection', async () => {
  const h = pageHarness()
  await h.page.exportHardwareLogs()
  h.page.lineReader.push(`${header}\n${first}\npartial`)
  h.page.onConnectionStateChanged({ deviceId: 'pico', connected: false })
  assert.equal(h.page.hardwareSnapshot.result.complete, false)
  assert.equal(h.page.hardwareSnapshot.result.records.length, 1)
  assert.equal(h.page.data.exportActive, false)
  h.page.lineReader.push('info fw=0.2.0\n')
  assert.equal(h.page.data.info.fw, '0.2.0')
})

test('explicit and custom cleanup require confirmation; a canceled dialog sends no command', async () => {
  const h = pageHarness()
  await h.page.clearHardwareLogs()
  h.page.data.customCommand = 'hwlog clear'
  h.page.sendCustom()
  await settle()
  assert.equal(h.modals.length, 2)
  assert.deepEqual(h.commands, [])
})

test('stop jumps ahead of queued work and disconnect drops leftover motion commands', () => {
  const h = pageHarness()
  h.page.data.connected = true
  h.page.writeBusy = true
  h.page.enqueueCommand('hwlog dump')
  h.page.enqueueCommand('f 0.16')
  h.page.enqueueCommand('keepalive')
  h.page.enqueueCommand('stop')
  assert.equal(h.page.commandQueue.map((entry) => entry.command).join('|'), 'stop|hwlog dump')
  h.page.invalidateConnection('test disconnect')
  assert.equal(h.page.commandQueue.length, 0)
})
