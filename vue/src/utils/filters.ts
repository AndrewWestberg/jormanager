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
export function lovelaceToAda(lovelace: number, decimals: number = 6): string {
  return formatCurrency(lovelace / 1000000, '₳', decimals)
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
