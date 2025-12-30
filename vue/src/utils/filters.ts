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
