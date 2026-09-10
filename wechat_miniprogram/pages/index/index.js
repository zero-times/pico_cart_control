const { parseLine } = require('../../utils/protocol')
const { createLineReader, createExport, acceptExportLine, finishExport, addError, readHardwareStatus } = require('../../utils/hardware-log')

const SERVICE_HINTS = [
  '6E400001-B5A3-F393-E0A9-E50E24DCCA9E',
  '0000FFE0-0000-1000-8000-00805F9B34FB',
  '0000FFF0-0000-1000-8000-00805F9B34FB'
]

const CHAR_HINTS = [
  '6E400002-B5A3-F393-E0A9-E50E24DCCA9E',
  '6E400003-B5A3-F393-E0A9-E50E24DCCA9E',
  '0000FFE1-0000-1000-8000-00805F9B34FB',
  '0000FFF1-0000-1000-8000-00805F9B34FB'
]

const PARAM_KEYS = [
  'max_pwm',
  'min_pwm',
  'start_raw',
  'full_raw',
  'steer_gain',
  'ramp',
  'manual_max',
  'left_motor_gain',
  'right_motor_gain',
  'left_force_gain',
  'right_force_gain'
]

const DEFAULT_PARAM_INPUTS = {
  max_pwm: '0.45',
  min_pwm: '0.14',
  start_raw: '25000',
  full_raw: '180000',
  steer_gain: '0.75',
  ramp: '0.018',
  manual_max: '0.25',
  left_motor_gain: '1.00',
  right_motor_gain: '1.00',
  left_force_gain: '1.00',
  right_force_gain: '1.00'
}

function wxCall(name, options) {
  return new Promise((resolve, reject) => {
    wx[name](Object.assign({}, options || {}, {
      success: resolve,
      fail: reject
    }))
  })
}

function delay(ms) {
  return new Promise((resolve) => {
    setTimeout(resolve, ms)
  })
}

function normalizeUuid(uuid) {
  return String(uuid || '').toUpperCase()
}

function uuidScore(uuid, hints) {
  const normalized = normalizeUuid(uuid)
  let score = 0
  hints.forEach((hint, index) => {
    const exact = normalizeUuid(hint)
    const shortId = exact.slice(4, 8)
    if (normalized === exact) {
      score += 120 - index * 5
    } else if (normalized.indexOf(shortId) >= 0) {
      score += 70 - index * 4
    }
  })
  return score
}

function ab2str(buffer) {
  const data = new Uint8Array(buffer)
  let result = ''
  for (let i = 0; i < data.length; i += 1) {
    result += String.fromCharCode(data[i])
  }
  return result
}

function str2bytes(text) {
  if (typeof TextEncoder !== 'undefined') {
    return new TextEncoder().encode(text)
  }
  const result = new Uint8Array(text.length)
  for (let i = 0; i < text.length; i += 1) {
    result[i] = text.charCodeAt(i) & 0xff
  }
  return result
}

function bytesToBuffer(bytes) {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)
}

function pad2(value) {
  return String(value).padStart(2, '0')
}

function formatDateTime(date) {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`
}

function formatFileTime(date) {
  return `${date.getFullYear()}${pad2(date.getMonth() + 1)}${pad2(date.getDate())}_${pad2(date.getHours())}${pad2(date.getMinutes())}${pad2(date.getSeconds())}`
}

function buildParamRows(inputs) {
  return PARAM_KEYS.map((key) => ({
    key,
    value: inputs[key] || ''
  }))
}

Page({
  data: {
    adapterReady: false,
    scanning: false,
    connected: false,
    connecting: false,
    deviceId: '',
    deviceName: '',
    serviceId: '',
    writeCharId: '',
    notifyCharId: '',
    writeNoResponse: false,
    devices: [],
    rxBuffer: '',
    streaming: false,
    manualPower: 16,
    customCommand: '',
    logs: [],
    logAnchor: '',
    info: {},
    firmware: '-',
    timeSyncState: '未同步',
    timeSyncDetail: '连接后自动同步手机时间',
    linkStatus: '未连接',
    lastBleError: '',
    hardwareStatus: null,
    diagnosticsMessage: 'RAM 日志未查询',
    exportActive: false,
    exportMessage: '按需同步日志到手机',
    exportCanSave: false,
    hardwareFileName: '',
    clearPending: false,
    status: {
      mode: '-',
      sensor: '-',
      err: '-',
      lraw: '0',
      rraw: '0',
      l: '0',
      r: '0',
      total: '0',
      steer: '0',
      pwml: '0',
      pwmr: '0',
      estop: '-',
      unsafe: '-'
    },
    params: {},
    paramInputs: Object.assign({}, DEFAULT_PARAM_INPUTS),
    paramRows: buildParamRows(DEFAULT_PARAM_INPUTS),
    calStatus: '未读取',
    calSaving: false,
    savedLeftGain: '-',
    savedRightGain: '-'
  },

  writeBusy: false,
  commandQueue: null,
  connectionSession: 0,
  driveTimer: null,
  stopTimer: null,
  lastTouchDriveAt: 0,
  fullLogs: [],
  lastLogFilePath: '',
  lastLogFileName: '',

  onLoad() {
    this.commandQueue = []
    this.fullLogs = []
    this.listeners = {
      BluetoothDeviceFound: this.onBluetoothDeviceFound.bind(this),
      BLECharacteristicValueChange: this.onCharacteristicChanged.bind(this),
      BluetoothAdapterStateChange: this.onAdapterStateChanged.bind(this),
      BLEConnectionStateChange: this.onConnectionStateChanged.bind(this)
    }
    Object.keys(this.listeners).forEach((name) => wx[`on${name}`](this.listeners[name]))
    this.resetLineReader()
  },

  onShow() {
    this.pageHidden = false
    if (this.data.connected) {
      this.refreshHardwareStatus()
      this.startDiagnosticsPolling()
    }
  },

  onHide() {
    this.pageHidden = true
    this.releaseDrive()
    this.stopDiagnosticsPolling()
    if (this.hardwareExport) this.endHardwareExport('页面进入后台，导出未完成')
  },

  onUnload() {
    this.releaseDrive()
    this.invalidateConnection('页面已关闭')
    this.unloaded = true
    Object.keys(this.listeners || {}).forEach((name) => {
      if (wx[`off${name}`]) wx[`off${name}`](this.listeners[name])
    })
    if (this.data.connected && this.data.deviceId) {
      wx.closeBLEConnection({ deviceId: this.data.deviceId })
    }
    wx.closeBluetoothAdapter({})
  },

  addLog(message) {
    if (!this.fullLogs) {
      this.fullLogs = []
    }
    const logs = this.data.logs.slice(-79)
    const time = new Date().toTimeString().slice(0, 8)
    const id = `log-${Date.now()}-${logs.length}`
    const entry = {
      id,
      text: `${time} ${message}`
    }
    logs.push(entry)
    this.fullLogs.push(entry)
    if (this.fullLogs.length > 1000) {
      this.fullLogs = this.fullLogs.slice(-1000)
    }
    this.setData({
      logs,
      logAnchor: id
    })
  },

  buildLogText() {
    const now = new Date()
    const status = this.data.status || {}
    const params = this.data.paramInputs || {}
    const info = this.data.info || {}
    const fullLogs = this.fullLogs && this.fullLogs.length ? this.fullLogs : this.data.logs

    const lines = [
      'Pico Cart Debug Log',
      `created_at=${formatDateTime(now)}`,
      `device_name=${this.data.deviceName || '-'}`,
      `device_id=${this.data.deviceId || '-'}`,
      `connected=${this.data.connected ? '1' : '0'}`,
      `service_id=${this.data.serviceId || '-'}`,
      `write_char=${this.data.writeCharId || '-'}`,
      `notify_char=${this.data.notifyCharId || '-'}`,
      '',
      '[info]',
      JSON.stringify(info, null, 2),
      '',
      '[status]',
      JSON.stringify(status, null, 2),
      '',
      '[params]',
      JSON.stringify(params, null, 2),
      '',
      '[logs]'
    ]

    fullLogs.forEach((entry) => {
      lines.push(entry.text)
    })

    return `${lines.join('\n')}\n`
  },

  buildLogFileName() {
    const name = (this.data.deviceName || 'pico-cart')
      .replace(/[\\/:*?"<>|\s]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 32) || 'pico-cart'
    return `${name}_${formatFileTime(new Date())}.txt`
  },

  writeLogFile(options) {
    return new Promise((resolve, reject) => {
      const fileName = options && options.fileName || this.buildLogFileName()
      const filePath = `${wx.env.USER_DATA_PATH}/${fileName}`
      const content = options && options.content || this.buildLogText()
      wx.getFileSystemManager().writeFile({
        filePath,
        data: content,
        encoding: 'utf8',
        success: () => {
          this.lastLogFilePath = filePath
          this.lastLogFileName = fileName
          this.lastLogFileContent = content
          resolve({ filePath, fileName, content })
        },
        fail: reject
      })
    })
  },

  async ensurePrivacyAuthorized() {
    if (!wx.requirePrivacyAuthorize) {
      return true
    }
    return new Promise((resolve) => {
      wx.requirePrivacyAuthorize({
        success: () => {
          resolve(true)
        },
        fail: (err) => {
          this.addLog(`privacy denied ${err.errMsg || err}`)
          wx.showModal({
            title: '需要隐私授权',
            content: '蓝牙调试需要先同意小程序隐私保护指引，并在后台声明“蓝牙”用途。',
            showCancel: false
          })
          resolve(false)
        }
      })
    })
  },

  async initBluetooth() {
    try {
      const privacyOk = await this.ensurePrivacyAuthorized()
      if (!privacyOk) {
        return
      }
      await wxCall('openBluetoothAdapter')
      this.setData({ adapterReady: true })
      this.addLog('bluetooth adapter ready')
      await this.startScan()
    } catch (err) {
      this.addLog(`adapter error ${err.errMsg || err}`)
      wx.showModal({
        title: '蓝牙不可用',
        content: err.errMsg || '请打开手机蓝牙后重试',
        showCancel: false
      })
    }
  },

  async startScan() {
    if (!this.data.adapterReady) {
      await this.initBluetooth()
      return
    }
    try {
      await wxCall('startBluetoothDevicesDiscovery', {
        allowDuplicatesKey: false,
        interval: 0
      })
      this.setData({ scanning: true })
      this.addLog('scan started')
    } catch (err) {
      this.addLog(`scan error ${err.errMsg || err}`)
    }
  },

  async stopScan() {
    try {
      await wxCall('stopBluetoothDevicesDiscovery')
    } catch (err) {
      this.addLog(`stop scan error ${err.errMsg || err}`)
    }
    this.setData({ scanning: false })
  },

  onAdapterStateChanged(res) {
    this.setData({
      adapterReady: !!res.available,
      scanning: !!res.discovering
    })
  },

  onConnectionStateChanged(res) {
    if (res.deviceId !== this.data.deviceId) {
      return
    }
    if (!res.connected) {
      const errCode = res.errCode == null ? '-' : res.errCode
      this.setData({
        connected: false,
        streaming: false,
        linkStatus: `已断开 errCode=${errCode}`,
        lastBleError: `errCode=${errCode}`
      })
      this.invalidateConnection('蓝牙断开，日志未完整接收')
      this.releaseDrive(false)
      this.addLog(`device disconnected errCode=${errCode}`)
    }
  },

  onBluetoothDeviceFound(res) {
    const found = res.devices || []
    const map = {}
    this.data.devices.forEach((device) => {
      map[device.deviceId] = device
    })

    found.forEach((device) => {
      const name = device.name || device.localName || ''
      if (!device.deviceId) {
        return
      }
      map[device.deviceId] = {
        deviceId: device.deviceId,
        name: name || '未命名设备',
        rssi: device.RSSI || device.rssi || 0,
        advertisServiceUUIDs: device.advertisServiceUUIDs || []
      }
    })

    const devices = Object.keys(map)
      .map((key) => map[key])
      .sort((a, b) => b.rssi - a.rssi)

    this.setData({ devices })
  },

  async connectTap(event) {
    const deviceId = event.currentTarget.dataset.id
    const device = this.data.devices.find((item) => item.deviceId === deviceId)
    if (!device) {
      return
    }
    await this.connectDevice(device)
  },

  async connectIdentifyTap(event) {
    const deviceId = event.currentTarget.dataset.id
    const device = this.data.devices.find((item) => item.deviceId === deviceId)
    if (!device) {
      return
    }
    const ok = await this.connectDevice(device)
    if (ok) {
      const session = this.connectionSession
      await delay(200)
      if (session === this.connectionSession) await this.sendCommand('identify 5')
    }
  },

  async connectDevice(device) {
    const previousId = this.data.deviceId
    if (this.data.connecting || this.data.connected || previousId) {
      this.releaseDrive(false)
      this.invalidateConnection('重新连接')
      if (previousId) {
        try {
          await wxCall('closeBLEConnection', { deviceId: previousId })
        } catch (err) {
          this.addLog(`reconnect close ${err.errMsg || err}`)
        }
      }
    }
    this.invalidateConnection('重新连接')
    const session = this.connectionSession
    const assertCurrent = () => {
      if (session !== this.connectionSession || this.unloaded) throw new Error('connection canceled')
    }
    this.setData({ connecting: true, deviceId: device.deviceId, firmware: '-', hardwareStatus: null,
      timeSyncState: '未同步', timeSyncDetail: '连接后自动同步手机时间', diagnosticsMessage: 'RAM 日志未查询',
      linkStatus: '正在连接', lastBleError: '' })
    this.addLog(`connect ${device.name}`)
    try {
      if (this.data.scanning) {
        await this.stopScan()
        assertCurrent()
      }
      await wxCall('createBLEConnection', {
        deviceId: device.deviceId,
        timeout: 12000
      })
      assertCurrent()

      if (wx.canIUse && wx.canIUse('setBLEMTU')) {
        try {
          await wxCall('setBLEMTU', {
            deviceId: device.deviceId,
            mtu: 64
          })
        } catch (err) {
          this.addLog(`mtu keep default ${err.errMsg || err}`)
        }
      }

      await delay(400)
      assertCurrent()
      const channel = await this.pickUartChannel(device.deviceId)
      assertCurrent()
      await wxCall('notifyBLECharacteristicValueChange', {
        state: true,
        deviceId: device.deviceId,
        serviceId: channel.serviceId,
        characteristicId: channel.notifyCharId
      })
      assertCurrent()

      this.setData({
        connected: true,
        connecting: false,
        deviceId: device.deviceId,
        deviceName: device.name,
        serviceId: channel.serviceId,
        writeCharId: channel.writeCharId,
        notifyCharId: channel.notifyCharId,
        writeNoResponse: !!channel.writeProperties.writeNoResponse,
        linkStatus: '已连接',
        lastBleError: ''
      })

      this.addLog(`channel ${channel.serviceId} ${channel.writeCharId}`)
      await this.syncTime()
      assertCurrent()
      await this.sendCommand('info')
      assertCurrent()
      await this.refreshHardwareStatus()
      assertCurrent()
      await this.sendCommand('status')
      assertCurrent()
      await this.sendCommand('param')
      assertCurrent()
      await this.sendCommand('cal status')
      assertCurrent()
      this.startDiagnosticsPolling()
      return true
    } catch (err) {
      if (session !== this.connectionSession || this.unloaded) return false
      this.setData({ connecting: false, lastBleError: String(err.errMsg || err), linkStatus: '连接失败' })
      this.invalidateConnection('连接初始化失败')
      wx.closeBLEConnection({ deviceId: device.deviceId })
      this.setData({ connected: false })
      this.addLog(`connect error ${err.errMsg || err} errCode=${err.errCode == null ? '-' : err.errCode}`)
      wx.showToast({
        title: '连接失败',
        icon: 'none'
      })
      return false
    }
  },

  async disconnect() {
    this.releaseDrive()
    this.invalidateConnection('蓝牙断开，日志未完整接收')
    if (this.data.deviceId) {
      try {
        await wxCall('closeBLEConnection', { deviceId: this.data.deviceId })
      } catch (err) {
        this.addLog(`disconnect error ${err.errMsg || err}`)
      }
    }
    this.setData({
      connected: false,
      connecting: false,
      streaming: false,
      deviceId: '',
      deviceName: '',
      serviceId: '',
      writeCharId: '',
      notifyCharId: '',
      writeNoResponse: false,
      linkStatus: '未连接',
      lastBleError: ''
    })
  },

  async pickUartChannel(deviceId) {
    const serviceResult = await wxCall('getBLEDeviceServices', { deviceId })
    const services = (serviceResult.services || []).filter((service) => service.isPrimary !== false)
    let best = null

    for (let i = 0; i < services.length; i += 1) {
      const service = services[i]
      let charResult = null
      try {
        charResult = await wxCall('getBLEDeviceCharacteristics', {
          deviceId,
          serviceId: service.uuid
        })
      } catch (err) {
        this.addLog(`char skip ${service.uuid}`)
        continue
      }

      const chars = charResult.characteristics || []
      const writes = chars.filter((char) => char.properties.write || char.properties.writeNoResponse)
      const notifies = chars.filter((char) => char.properties.notify || char.properties.indicate)

      writes.forEach((writeChar) => {
        notifies.forEach((notifyChar) => {
          const score = uuidScore(service.uuid, SERVICE_HINTS)
            + uuidScore(writeChar.uuid, CHAR_HINTS)
            + uuidScore(notifyChar.uuid, CHAR_HINTS)
            + (writeChar.properties.writeNoResponse ? 8 : 0)
            + (notifyChar.properties.notify ? 8 : 0)

          if (!best || score > best.score) {
            best = {
              score,
              serviceId: service.uuid,
              writeCharId: writeChar.uuid,
              notifyCharId: notifyChar.uuid,
              writeProperties: writeChar.properties
            }
          }
        })
      })
    }

    if (!best) {
      throw new Error('no writable notify characteristic')
    }
    return best
  },

  onCharacteristicChanged(res) {
    if (res.deviceId !== this.data.deviceId || !this.data.connected ||
        (res.characteristicId && normalizeUuid(res.characteristicId) !== normalizeUuid(this.data.notifyCharId))) {
      return
    }
    this.lineReader.push(ab2str(res.value))
  },

  applyLine(line) {
    this.addLog(`< ${line}`)
    const parsed = parseLine(line)
    this.applyDiagnosticLine(line, parsed)

    if (parsed.type === 'stat') {
      this.setData({
        status: Object.assign({}, this.data.status, parsed)
      })
    } else if (parsed.type === 'param') {
      const inputs = Object.assign({}, this.data.paramInputs)
      Object.keys(inputs).forEach((key) => {
        if (parsed[key] !== undefined) {
          inputs[key] = parsed[key]
        }
      })
      this.setData({
        params: parsed,
        paramInputs: inputs,
        paramRows: buildParamRows(inputs)
      })
    } else if (parsed.type === 'cal') {
      this.setData({
        calSaving: false,
        calStatus: parsed.err && parsed.err !== '-' ? `校准文件不可用：${parsed.err}` : `已保存=${parsed.loaded === '1' ? '是' : '否'}，未保存改动=${parsed.dirty === '1' ? '有' : '无'}`,
        savedLeftGain: parsed.saved_left_motor_gain || this.data.savedLeftGain,
        savedRightGain: parsed.saved_right_motor_gain || this.data.savedRightGain
      })
    } else if (parsed.type === 'ok' && /^ok cal_save(?:\s|$)/.test(line)) {
      this.setData({
        calSaving: false,
        calStatus: `已保存左右轮增益 ${parsed.left_motor_gain || '-'} / ${parsed.right_motor_gain || '-'}`,
        savedLeftGain: parsed.left_motor_gain || this.data.savedLeftGain,
        savedRightGain: parsed.right_motor_gain || this.data.savedRightGain
      })
    } else if (parsed.type === 'err' && /^err cal/.test(line)) {
      this.setData({ calSaving: false, calStatus: `保存失败：${line}` })
    } else if (parsed.type === 'info') {
      this.setData({ info: parsed, firmware: parsed.fw || this.data.firmware })
    } else if (parsed.type === 'ok' && parsed.stream) {
      this.setData({ streaming: parsed.stream === 'on' })
    }
  },

  async writeBytes(bytes, session, channel) {
    const chunkSize = 18
    for (let offset = 0; offset < bytes.length; offset += chunkSize) {
      if (session !== this.connectionSession || !this.data.connected) throw new Error('connection changed')
      const chunk = bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length))
      const options = {
        deviceId: channel.deviceId,
        serviceId: channel.serviceId,
        characteristicId: channel.writeCharId,
        value: bytesToBuffer(chunk)
      }
      if (
        channel.writeNoResponse
        && wx.canIUse
        && wx.canIUse('writeBLECharacteristicValue.object.writeType')
      ) {
        options.writeType = 'writeNoResponse'
      }
      await wxCall('writeBLECharacteristicValue', options)
      await delay(35)
    }
  },

  commandKind(command) {
    const name = String(command || '').trim().split(/\s+/)[0] || ''
    if (name === 'stop' || name === 's') return 'hard_stop'
    if (name === 'softstop') return 'soft_stop'
    if (['f', 'b', 'l', 'r', 'drive', 'keepalive', 'motor', 'motortest'].indexOf(name) >= 0) return 'movement'
    return 'regular'
  },

  sendCommand(command) {
    const text = String(command || '').trim()
    if (!this.data.connected || !text) return Promise.resolve(false)
    if (!this.commandQueue) this.commandQueue = []
    const kind = this.commandKind(text)
    return new Promise((resolve) => {
      if (kind === 'hard_stop' || kind === 'soft_stop' || kind === 'movement') {
        this.commandQueue = this.commandQueue.filter((entry) => {
          const existing = this.commandKind(entry.command)
          if (existing === 'movement' || (kind === 'hard_stop' && existing === 'soft_stop')) {
            entry.resolve(false)
            return false
          }
          return true
        })
      }
      if (kind === 'movement' && text === 'keepalive' && this.commandQueue.some((entry) => entry.command === 'keepalive')) {
        resolve(false)
        return
      }
      const entry = { command: text, session: this.connectionSession, resolve, kind }
      if (kind === 'hard_stop' || kind === 'soft_stop') this.commandQueue.unshift(entry)
      else this.commandQueue.push(entry)
      this.drainCommands()
    })
  },

  async drainCommands() {
    if (this.writeBusy) return
    const session = this.connectionSession
    this.writeBusy = true
    try {
      while (this.commandQueue.length && session === this.connectionSession) {
        const entry = this.commandQueue.shift()
        if (entry.session !== session || !this.data.connected) { entry.resolve(false); continue }
        try {
          this.addLog(`> ${entry.command}`)
          await this.writeBytes(str2bytes(`${entry.command}\n`), session, Object.assign({}, this.data))
          entry.resolve(session === this.connectionSession)
        } catch (err) {
          if (session === this.connectionSession) {
            const detail = `write error ${err.errMsg || err} errCode=${err.errCode == null ? '-' : err.errCode}`
            this.addLog(detail)
            this.setData({ lastBleError: detail })
          }
          entry.resolve(false)
        }
      }
    } finally {
      if (session === this.connectionSession) this.writeBusy = false
    }
  },

  resetLineReader() {
    this.lineReader = createLineReader((line) => this.applyLine(line), () => {
      this.addLog('receive error: line exceeds 400 characters')
      if (this.hardwareExport) addError(this.hardwareExport, '收到超长记录，导出不完整')
    })
  },

  invalidateConnection(reason) {
    this.connectionSession += 1
    this.stopDiagnosticsPolling()
    clearTimeout(this.timeSyncTimer)
    clearTimeout(this.hardwareStatusTimer)
    clearTimeout(this.clearTimer)
    this.hardwareStatusTimer = null
    this.timeSyncPending = null
    this.clearRequest = null
    ;(this.commandQueue || []).forEach((entry) => entry.resolve(false))
    this.commandQueue = []
    this.writeBusy = false
    this.resetLineReader()
    if (this.hardwareExport) this.endHardwareExport(reason)
    this.setData({ clearPending: false, rxBuffer: '', timeSyncState: '未同步', timeSyncDetail: '连接后自动同步手机时间' })
  },

  async syncTime() {
    if (!this.data.connected || this.timeSyncPending) return false
    const pending = { unix: Date.now(), session: this.connectionSession }
    this.timeSyncPending = pending
    this.setData({ timeSyncState: '同步中', timeSyncDetail: '正在同步手机时间' })
    clearTimeout(this.timeSyncTimer)
    this.timeSyncTimer = setTimeout(() => {
      if (this.timeSyncPending === pending) {
        this.timeSyncPending = null
        this.setData({ timeSyncState: '失败', timeSyncDetail: '未收到时间确认，请手动重试' })
      }
    }, 8000)
    const sent = await this.sendCommand(`time sync ${pending.unix}`)
    if (!sent && this.timeSyncPending === pending) {
      clearTimeout(this.timeSyncTimer)
      this.timeSyncPending = null
      this.setData({ timeSyncState: '失败', timeSyncDetail: '时间发送失败，请手动重试' })
    }
    return sent
  },

  stopDiagnosticsPolling() {
    clearInterval(this.diagnosticsTimer)
    this.diagnosticsTimer = null
  },

  startDiagnosticsPolling() {
    this.stopDiagnosticsPolling()
    if (!this.data.connected || this.pageHidden) return
    const session = this.connectionSession
    this.diagnosticsTimer = setInterval(() => {
      if (session === this.connectionSession && !this.writeBusy && !this.timeSyncPending && !this.data.exportActive) this.refreshHardwareStatus()
    }, 15000)
  },

  async refreshHardwareStatus() {
    if (!this.data.connected || this.data.exportActive || this.data.clearPending || this.hardwareStatusTimer) return false
    const session = this.connectionSession
    this.hardwareStatusTimer = setTimeout(() => {
      this.hardwareStatusTimer = null
      if (session === this.connectionSession) this.setData({ diagnosticsMessage: '缓存状态查询超时，可点刷新重试' })
    }, 8000)
    const sent = await this.sendCommand('hwlog status')
    if (!sent && session === this.connectionSession) {
      clearTimeout(this.hardwareStatusTimer)
      this.hardwareStatusTimer = null
      this.setData({ diagnosticsMessage: '缓存状态查询发送失败' })
    }
    return sent
  },

  applyDiagnosticLine(line, parsed) {
    if (parsed.type === 'time') {
      const pending = this.timeSyncPending
      if (parsed.synced === '1' && /^\d+$/.test(parsed.unix_ms) && /^\d+$/.test(parsed.uptime_ms) &&
          (!pending || String(pending.unix) === parsed.request_ms)) {
        clearTimeout(this.timeSyncTimer)
        this.timeSyncPending = null
        this.setData({ timeSyncState: '成功', timeSyncDetail: '手机时间已同步；同步前记录仍使用启动时长', firmware: parsed.fw || this.data.firmware })
      } else if (parsed.synced === '0' && !pending) {
        this.setData({ timeSyncState: '未同步', timeSyncDetail: '设备时间未同步，请手动同步' })
      }
    }
    if (/^ok hwlog_status(?:\s|$)/.test(line)) {
      const status = readHardwareStatus(parsed)
      if (status) {
        clearTimeout(this.hardwareStatusTimer)
        this.hardwareStatusTimer = null
        this.setData({ hardwareStatus: status, firmware: status.firmware,
          diagnosticsMessage: status.percent >= 90 ? '缓存已达 90% 以上，请主动同步日志，避免旧记录被覆盖' : '每 15 秒刷新容量；日志仅在点击同步后导入' })
        if (!status.synced && !this.timeSyncPending) this.setData({ timeSyncState: '未同步', timeSyncDetail: '设备时间未同步，请手动同步' })
      }
    }
    if (/^ok hwlog_clear(?:\s|$)/.test(line) && /^\d+$/.test(parsed.n) && this.clearRequest) {
      clearTimeout(this.clearTimer)
      this.clearRequest = null
      this.setData({ clearPending: false, diagnosticsMessage: `清理已确认，剩余 ${parsed.n} 条，正在刷新` })
      this.refreshHardwareStatus()
    }
    if (parsed.type === 'err' && /^err (hwlog|time)/.test(line)) {
      if (this.hardwareExport && /^err hwlog/.test(line)) this.endHardwareExport(`设备拒绝导出：${line}`)
      if (this.clearRequest && /^err hwlog/.test(line)) {
        clearTimeout(this.clearTimer)
        this.clearRequest = null
        this.setData({ clearPending: false, diagnosticsMessage: `清理未成功：${line}` })
      }
      if (this.timeSyncPending && /^err time/.test(line)) {
        clearTimeout(this.timeSyncTimer)
        this.timeSyncPending = null
        this.setData({ timeSyncState: '失败', timeSyncDetail: '设备拒绝时间同步，请检查固件版本' })
      }
    }
    if (!this.hardwareExport) return
    const event = acceptExportLine(this.hardwareExport, line)
    if (event === 'ignored') return
    if (event === 'complete' || event === 'incomplete') {
      this.endHardwareExport()
    } else {
      this.setData({ exportMessage: `正在同步 ${this.hardwareExport.records.length} / ${this.hardwareExport.header ? this.hardwareExport.header.count : '?'} 条` })
      this.armExportIdleTimeout()
    }
  },

  armExportIdleTimeout() {
    clearTimeout(this.exportIdleTimer)
    this.exportIdleTimer = setTimeout(() => this.endHardwareExport('15 秒未收到日志，导出不完整'), 15000)
  },

  async exportHardwareLogs() {
    if (!this.data.connected || this.data.exportActive || this.data.clearPending) return
    this.hardwareExport = createExport()
    this.exportConnection = this.connectionSession
    this.exportDevice = { id: this.data.deviceId, name: this.data.deviceName }
    this.setData({ exportActive: true, exportCanSave: false, exportMessage: '正在请求硬件日志快照' })
    this.armExportIdleTimeout()
    this.exportTimer = setTimeout(() => this.endHardwareExport('导出超过 90 秒，未完整接收'), 90000)
    const current = this.hardwareExport
    const sent = await this.sendCommand('hwlog dump')
    if (!sent && this.hardwareExport === current) this.endHardwareExport('日志导出请求发送失败')
  },

  endHardwareExport(reason) {
    if (!this.hardwareExport) return
    clearTimeout(this.exportTimer)
    clearTimeout(this.exportIdleTimer)
    const result = finishExport(this.hardwareExport, reason)
    this.hardwareExport = null
    this.hardwareSnapshot = { result, session: this.exportConnection, device: this.exportDevice, receivedAt: new Date().toISOString(), saved: false }
    if (result.complete) {
      this.setData({ exportMessage: `已完整接收 ${result.records.length} 条，正在保存` })
      this.saveHardwareSnapshot(this.hardwareSnapshot)
    } else {
      this.setData({ exportActive: false, exportCanSave: true,
        exportMessage: `导出不完整：${result.errors.join('；')}。已收到 ${result.records.length} 条，可保存已有记录后重试。` })
    }
  },

  async saveHardwareSnapshot(snapshot) {
    if (!snapshot || snapshot.saving) return
    snapshot.saving = true
    const result = snapshot.result
    const header = result.header
    const lines = ['Pico Cart Hardware Log Export',
      `received_at=${snapshot.receivedAt}`, `device_name=${snapshot.device.name}`, `device_id=${snapshot.device.id}`,
      `complete=${result.complete ? '1' : '0'}`, `received=${result.records.length}`, `expected=${header ? header.count : 'unknown'}`,
      `fw=${header ? header.firmware : 'unknown'}`, `integrity_errors=${JSON.stringify(result.errors)}`,
      'timestamps: t=uptime_ms; u=unix_ms (0 means unsynchronized; no wall-clock time inferred)', '', '[hardware_logs]']
    if (header) lines.push(header.raw)
    result.records.forEach((record) => lines.push(record.raw))
    if (result.endLine) lines.push(result.endLine)
    const content = `${lines.join('\n')}\n`
    try {
      const saved = await this.writeLogFile({ content,
        fileName: `pico-hwlog_${formatFileTime(new Date())}_${Date.now()}_${result.complete ? 'complete' : 'partial'}.txt` })
      snapshot.saved = true
      this.hardwareSavedFile = saved
      if (this.unloaded) return
      if (this.hardwareSnapshot === snapshot) this.setData({ exportActive: false, exportCanSave: false,
        hardwareFileName: saved.fileName, exportMessage: `${result.complete ? '完整' : '不完整'}日志已保存：${result.records.length} 条` })
      if (result.complete && result.records.length && snapshot.session === this.connectionSession && this.data.connected && this.hardwareSnapshot === snapshot) {
        const choice = await wxCall('showModal', { title: '日志已完整保存',
          content: `已保存 ${result.records.length} 条到手机。是否清理硬件中这些已同步记录？同步后产生的新记录会保留。`, confirmText: '清理已同步', cancelText: '保留' })
        if (choice.confirm && this.hardwareSnapshot === snapshot && snapshot.session === this.connectionSession && this.data.connected) {
          await this.requestHardwareClear(header.last)
        }
      }
    } catch (err) {
      if (!this.unloaded && this.hardwareSnapshot === snapshot) this.setData({ exportActive: false, exportCanSave: true, exportMessage: `保存失败，可重试；硬件记录未清理。${err.errMsg || err.message || ''}` })
    } finally {
      snapshot.saving = false
    }
  },

  saveReceivedHardwareLogs() {
    this.saveHardwareSnapshot(this.hardwareSnapshot)
  },

  async clearHardwareLogs() {
    if (!this.data.connected || this.data.exportActive || this.data.clearPending) return
    const session = this.connectionSession
    const choice = await wxCall('showModal', { title: '清空硬件 RAM 日志',
      content: '这会删除硬件当前所有缓存记录，未同步的日志将无法恢复。是否继续？', confirmText: '确认清空', confirmColor: '#a63c2d' })
    if (choice.confirm && session === this.connectionSession && this.data.connected) await this.requestHardwareClear()
  },

  async requestHardwareClear(last) {
    if (!this.data.connected || this.data.exportActive || this.data.clearPending) return
    const request = { session: this.connectionSession }
    this.clearRequest = request
    this.setData({ clearPending: true, diagnosticsMessage: '等待硬件确认清理结果' })
    this.clearTimer = setTimeout(() => {
      if (this.clearRequest !== request) return
      this.clearRequest = null
      this.setData({ clearPending: false, diagnosticsMessage: '未收到清理确认，结果未知；请刷新容量核对' })
    }, 8000)
    const sent = await this.sendCommand(last === undefined ? 'hwlog clear' : `hwlog clear ${last}`)
    if (!sent && this.clearRequest === request) {
      clearTimeout(this.clearTimer)
      this.clearRequest = null
      this.setData({ clearPending: false, diagnosticsMessage: '清理命令发送失败，请刷新核对' })
    }
  },

  shareHardwareLogs() {
    if (!this.hardwareSavedFile) return
    this.shareSavedFile(this.hardwareSavedFile)
  },

  sendStatus() {
    this.sendCommand('status')
  },

  sendParamQuery() {
    this.sendCommand('param')
  },

  sendAuto() {
    this.sendCommand('auto')
  },

  sendManual() {
    this.sendCommand('manual')
  },

  sendStop() {
    this.sendCommand('stop')
  },

  sendTare() {
    this.sendCommand('tare')
  },

  sendIdentify() {
    this.sendCommand('identify 5')
  },

  toggleStream() {
    const next = !this.data.streaming
    this.setData({ streaming: next })
    this.sendCommand(next ? 'stream on' : 'stream off')
  },

  onPowerChange(event) {
    this.setData({
      manualPower: event.detail.value
    })
  },

  directionCommand(direction) {
    const power = (this.data.manualPower / 100).toFixed(2)
    const map = {
      forward: `f ${power}`,
      backward: `b ${power}`,
      left: `l ${power}`,
      right: `r ${power}`
    }
    return map[direction] || 'stop'
  },

  holdDrive(event) {
    const direction = event.currentTarget.dataset.dir
    const command = this.directionCommand(direction)
    this.lastTouchDriveAt = Date.now()
    if (this.stopTimer) {
      clearTimeout(this.stopTimer)
      this.stopTimer = null
    }
    this.releaseDrive(false)
    this.sendCommand(command)
    this.driveTimer = setInterval(() => {
      this.sendCommand('keepalive')
    }, 260)
  },

  tapDrive(event) {
    if (Date.now() - this.lastTouchDriveAt < 500) {
      return
    }
    const direction = event.currentTarget.dataset.dir
    const command = this.directionCommand(direction)
    this.sendCommand(command)
    if (this.stopTimer) {
      clearTimeout(this.stopTimer)
    }
    this.stopTimer = setTimeout(() => {
      this.sendCommand('stop')
      this.stopTimer = null
    }, 420)
  },

  releaseDrive(sendStop) {
    if (this.driveTimer) {
      clearInterval(this.driveTimer)
      this.driveTimer = null
    }
    if (this.stopTimer) {
      clearTimeout(this.stopTimer)
      this.stopTimer = null
    }
    if (sendStop !== false && this.data.connected) {
      this.sendCommand('stop')
    }
  },

  onParamInput(event) {
    const key = event.currentTarget.dataset.key
    const index = event.currentTarget.dataset.index
    this.setData({
      [`paramInputs.${key}`]: event.detail.value,
      [`paramRows[${index}].value`]: event.detail.value
    })
  },

  applyParam(event) {
    const key = event.currentTarget.dataset.key
    const value = this.data.paramInputs[key]
    if (value === undefined || value === '') {
      return
    }
    this.sendCommand(`set ${key} ${value}`)
  },

  refreshCalibration() {
    this.sendCommand('cal status')
  },

  testLeftWheel() {
    this.sendCommand('motor left f 0.16 800')
  },

  testRightWheel() {
    this.sendCommand('motor right f 0.16 800')
  },

  testStraight() {
    this.sendCommand('f 0.16')
  },

  saveMotorCalibration() {
    if (this.data.calSaving) return
    this.setData({ calSaving: true, calStatus: '正在停车并保存轮速增益' })
    this.sendCommand('stop')
    this.sendCommand('cal save motor')
  },

  onCustomInput(event) {
    this.setData({
      customCommand: event.detail.value
    })
  },

  sendCustom() {
    const command = this.data.customCommand.trim()
    if (/^hwlog\s+clear(?:\s|$)/.test(command)) {
      this.clearHardwareLogs()
      return
    }
    if (command === 'hwlog dump') {
      this.exportHardwareLogs()
      return
    }
    if (command) {
      this.sendCommand(command)
    }
  },

  copyLogs() {
    const content = this.buildLogText()
    wx.setClipboardData({
      data: content,
      success: () => {
        this.addLog(`log copied chars=${content.length}`)
      },
      fail: (err) => {
        this.addLog(`copy log error ${err.errMsg || err}`)
      }
    })
  },

  async saveLogsToFile() {
    try {
      const result = await this.writeLogFile()
      this.addLog(`log saved ${result.filePath}`)
      wx.showToast({
        title: '日志已保存',
        icon: 'success'
      })
    } catch (err) {
      this.addLog(`save log error ${err.errMsg || err}`)
      wx.showToast({
        title: '保存失败',
        icon: 'none'
      })
    }
  },

  shareLogs() {
    if (!this.lastLogFilePath) {
      wx.showModal({
        title: '先保存日志',
        content: '微信要求文件分享必须由点击动作直接触发。请先点“保存文件”生成日志，再点“发送好友”。',
        showCancel: false
      })
      return
    }
    this.shareSavedFile({ filePath: this.lastLogFilePath, fileName: this.lastLogFileName, content: this.lastLogFileContent })
  },

  shareSavedFile(file) {
    if (!wx.shareFileMessage) {
      wx.setClipboardData({ data: file.content || this.buildLogText() })
      this.addLog('shareFileMessage unavailable, log copied')
      wx.showModal({
        title: '已复制日志',
        content: '当前微信版本不支持直接发送文件，日志内容已复制。',
        showCancel: false
      })
      return
    }

    wx.shareFileMessage({
      filePath: file.filePath,
      fileName: file.fileName || 'pico-cart-log.txt',
      success: () => {
        this.addLog(`log shared ${this.lastLogFileName || this.lastLogFilePath}`)
      },
      fail: (err) => {
        this.addLog(`share log error ${err.errMsg || err}`)
        if (String(err.errMsg || '').indexOf('TAP gesture') >= 0) {
          wx.showModal({
            title: '分享被微信拦截',
            content: '请先点“保存文件”，再立刻点“发送好友”。如果仍失败，请用“复制日志”发送文本。',
            showCancel: false
          })
          return
        }
        wx.showToast({
          title: '发送失败',
          icon: 'none'
        })
      }
    })
  },

  clearLog() {
    this.fullLogs = []
    this.setData({
      logs: [],
      logAnchor: ''
    })
  }
})
