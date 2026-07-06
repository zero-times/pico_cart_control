const { parseLine } = require('../../utils/protocol')

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
    paramRows: buildParamRows(DEFAULT_PARAM_INPUTS)
  },

  writeBusy: false,
  pendingCommand: '',
  driveTimer: null,
  stopTimer: null,
  lastTouchDriveAt: 0,
  fullLogs: [],
  lastLogFilePath: '',
  lastLogFileName: '',

  onLoad() {
    wx.onBluetoothDeviceFound(this.onBluetoothDeviceFound.bind(this))
    wx.onBLECharacteristicValueChange(this.onCharacteristicChanged.bind(this))
    wx.onBluetoothAdapterStateChange(this.onAdapterStateChanged.bind(this))
    wx.onBLEConnectionStateChange(this.onConnectionStateChanged.bind(this))
  },

  onUnload() {
    this.releaseDrive()
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

  writeLogFile() {
    return new Promise((resolve, reject) => {
      const fileName = this.buildLogFileName()
      const filePath = `${wx.env.USER_DATA_PATH}/${fileName}`
      const content = this.buildLogText()
      wx.getFileSystemManager().writeFile({
        filePath,
        data: content,
        encoding: 'utf8',
        success: () => {
          this.lastLogFilePath = filePath
          this.lastLogFileName = fileName
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
      this.setData({
        connected: false,
        streaming: false
      })
      this.releaseDrive()
      this.addLog('device disconnected')
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
      await delay(200)
      await this.sendCommand('identify 5')
    }
  },

  async connectDevice(device) {
    this.setData({ connecting: true })
    this.addLog(`connect ${device.name}`)
    try {
      if (this.data.scanning) {
        await this.stopScan()
      }
      await wxCall('createBLEConnection', {
        deviceId: device.deviceId,
        timeout: 10000
      })

      if (wx.canIUse && wx.canIUse('setBLEMTU')) {
        try {
          await wxCall('setBLEMTU', {
            deviceId: device.deviceId,
            mtu: 128
          })
        } catch (err) {
          this.addLog(`mtu keep default ${err.errMsg || err}`)
        }
      }

      await delay(400)
      const channel = await this.pickUartChannel(device.deviceId)
      await wxCall('notifyBLECharacteristicValueChange', {
        state: true,
        deviceId: device.deviceId,
        serviceId: channel.serviceId,
        characteristicId: channel.notifyCharId
      })

      this.setData({
        connected: true,
        connecting: false,
        deviceId: device.deviceId,
        deviceName: device.name,
        serviceId: channel.serviceId,
        writeCharId: channel.writeCharId,
        notifyCharId: channel.notifyCharId,
        writeNoResponse: !!channel.writeProperties.writeNoResponse
      })

      this.addLog(`channel ${channel.serviceId} ${channel.writeCharId}`)
      await this.sendCommand('status')
      await this.sendCommand('param')
      return true
    } catch (err) {
      this.setData({ connecting: false })
      this.addLog(`connect error ${err.errMsg || err}`)
      wx.showToast({
        title: '连接失败',
        icon: 'none'
      })
      return false
    }
  },

  async disconnect() {
    this.releaseDrive()
    if (this.data.deviceId) {
      try {
        await wxCall('closeBLEConnection', { deviceId: this.data.deviceId })
      } catch (err) {
        this.addLog(`disconnect error ${err.errMsg || err}`)
      }
    }
    this.setData({
      connected: false,
      streaming: false,
      deviceId: '',
      deviceName: '',
      serviceId: '',
      writeCharId: '',
      notifyCharId: '',
      writeNoResponse: false
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
    if (res.deviceId !== this.data.deviceId) {
      return
    }
    const chunk = ab2str(res.value)
    let buffer = `${this.data.rxBuffer}${chunk}`.replace(/\r/g, '\n')
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''

    lines.forEach((line) => {
      const trimmed = line.trim()
      if (trimmed) {
        this.applyLine(trimmed)
      }
    })

    if (buffer.length > 240) {
      buffer = buffer.slice(-240)
    }
    this.setData({ rxBuffer: buffer })
  },

  applyLine(line) {
    this.addLog(`< ${line}`)
    const parsed = parseLine(line)

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
    } else if (parsed.type === 'info') {
      this.setData({ info: parsed })
    } else if (parsed.type === 'ok' && parsed.stream) {
      this.setData({ streaming: parsed.stream === 'on' })
    }
  },

  async writeBytes(bytes) {
    const chunkSize = 18
    for (let offset = 0; offset < bytes.length; offset += chunkSize) {
      const chunk = bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length))
      const options = {
        deviceId: this.data.deviceId,
        serviceId: this.data.serviceId,
        characteristicId: this.data.writeCharId,
        value: bytesToBuffer(chunk)
      }
      if (
        this.data.writeNoResponse
        && wx.canIUse
        && wx.canIUse('writeBLECharacteristicValue.object.writeType')
      ) {
        options.writeType = 'writeNoResponse'
      }
      await wxCall('writeBLECharacteristicValue', options)
      await delay(35)
    }
  },

  async sendCommand(command) {
    if (!this.data.connected) {
      this.addLog('not connected')
      return
    }
    if (this.writeBusy) {
      this.pendingCommand = String(command || '').trim()
      return
    }
    const line = `${String(command || '').trim()}\n`
    if (line.trim().length === 0) {
      return
    }

    this.writeBusy = true
    try {
      this.addLog(`> ${line.trim()}`)
      await this.writeBytes(str2bytes(line))
    } catch (err) {
      this.addLog(`write error ${err.errMsg || err}`)
    } finally {
      this.writeBusy = false
      if (this.pendingCommand) {
        const pending = this.pendingCommand
        this.pendingCommand = ''
        setTimeout(() => {
          this.sendCommand(pending)
        }, 0)
      }
    }
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
      this.sendCommand(command)
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

  onCustomInput(event) {
    this.setData({
      customCommand: event.detail.value
    })
  },

  sendCustom() {
    const command = this.data.customCommand.trim()
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

    if (!wx.shareFileMessage) {
      wx.setClipboardData({ data: this.buildLogText() })
      this.addLog('shareFileMessage unavailable, log copied')
      wx.showModal({
        title: '已复制日志',
        content: '当前微信版本不支持直接发送文件，日志内容已复制。',
        showCancel: false
      })
      return
    }

    wx.shareFileMessage({
      filePath: this.lastLogFilePath,
      fileName: this.lastLogFileName || 'pico-cart-log.txt',
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
