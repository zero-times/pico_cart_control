function parseLine(line) {
  const raw = String(line || '').trim()
  const parts = raw.split(/\s+/).filter(Boolean)
  const type = parts.shift() || ''
  const data = {
    type,
    raw
  }

  parts.forEach((part) => {
    const index = part.indexOf('=')
    if (index <= 0) {
      return
    }
    const key = part.slice(0, index).toLowerCase()
    const value = part.slice(index + 1)
    data[key] = value
  })

  return data
}

function numberValue(value, fallback) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

module.exports = {
  parseLine,
  numberValue
}
