// Utility functions to replace Vue 2 filters

/**
 * Format a number as currency
 */
export function formatCurrency(value: number, symbol: string = '₳', decimals: number = 6): string {
  const formatted = value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals
  })
  return `${symbol}${formatted}`
}

/**
 * Convert hex string to ASCII (readable text)
 */
export function hex2ascii(hexx: string): string {
  const hex = hexx.toString()
  let str = ''
  for (let i = 0; i < hex.length; i += 2) {
    const intVal = parseInt(hex.substr(i, 2), 16)
    if (intVal < 32 || intVal > 126) {
      // non-displayable character, return original hex
      return hexx
    }
    str += String.fromCharCode(intVal)
  }
  return str
}

/**
 * Format lovelace to ADA with currency symbol
 */
export function lovelaceToAda(lovelace: string | number | bigint, decimals: number = 6): string {
  if (typeof lovelace === 'number' && !Number.isSafeInteger(lovelace)) return 'Unavailable'
  const text = String(lovelace)
  if (!/^-?\d+$/.test(text) || !Number.isInteger(decimals) || decimals < 0 || decimals > 6) return 'Unavailable'
  const amount = BigInt(text)
  const absolute = amount < 0n ? -amount : amount
  const whole = (absolute / 1000000n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const fraction = (absolute % 1000000n).toString().padStart(6, '0').slice(0, decimals)
  return `${amount < 0n ? '-' : ''}₳${whole}${decimals ? `.${fraction}` : ''}`
}

/**
 * Capitalize first letter of each word
 */
export function startCase(value: string): string {
  return value.replace(/\b\w/g, (char) => char.toUpperCase())
}

/**
 * Generate a UUID, with fallback for non-HTTPS contexts where crypto.randomUUID is unavailable
 */
export function generateUUID(): string {
  // Prefer crypto.randomUUID() in secure contexts (HTTPS/localhost)
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID()
    }
  } catch {
    // Falls through to crypto.getRandomValues() below
  }
  // Fallback: use crypto.getRandomValues() for RFC4122 v4 UUID (works in HTTP contexts)
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
      const bytes = new Uint8Array(16)
      crypto.getRandomValues(bytes)
      bytes[6] = (bytes[6] & 0x0f) | 0x40 // version 4
      bytes[8] = (bytes[8] & 0x3f) | 0x80 // variant bits
      return [...bytes].map((b, i) =>
        [4, 6, 8, 10].includes(i) ? '-' + b.toString(16).padStart(2, '0') : b.toString(16).padStart(2, '0')
      ).join('')
    }
  } catch {
    // Falls through to Math.random() below
  }
  // Last resort: Math.random() (lower entropy, but always available)
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16)
  })
}
