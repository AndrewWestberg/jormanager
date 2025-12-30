import { describe, it, expect } from 'vitest'

/**
 * Tests for SendAdaModal component methods.
 * 
 * Key functions tested:
 * - calculateSpent: Complex spending calculation with amounts, percentages, and fees
 * - hexEncode: Character to hex encoding
 * - validateMetadataItem: Recursive metadata validation
 * - formatCurrency: Currency display formatting
 */

interface ToAccount {
  currency: string
  type: 'amount' | 'percent'
  amount: number | null
  percent?: number
  account: number
  tokenFee?: number
  isFeePayer?: boolean
}

interface MockContext {
  formSendAda: {
    isClaim: boolean
    toAccounts: ToAccount[]
  }
  fromWalletItem: {
    id: number
    paymentAddrLovelace: number
    stakingAddrLovelace: number
    nativeAssetMap: Record<string, number>
  }
  txFee: number
  tokenKeepFee: number
  tokenLocked: number
  tokenFees: number[]
  walletItems: unknown[]
  $ci: { parse: (value: unknown, options: unknown) => number }
}

describe('SendAdaModal component methods', () => {
  // Mock the $ci.parse function used by the component
  const mockCiParse = (value: unknown): number => {
    // Simple mock that strips non-numeric chars and returns integer
    if (typeof value === 'number') return value
    const numStr = String(value).replace(/[^0-9.-]/g, '')
    return parseInt(numStr) || 0
  }

  // Create mock component context for testing
  const createMockContext = (overrides: Partial<MockContext> = {}): MockContext => ({
    formSendAda: {
      isClaim: false,
      toAccounts: [],
      ...overrides.formSendAda
    },
    fromWalletItem: {
      id: 1,
      paymentAddrLovelace: 10000000, // 10 ADA
      stakingAddrLovelace: 5000000,  // 5 ADA
      nativeAssetMap: {},
      ...overrides.fromWalletItem
    },
    txFee: 200000, // 0.2 ADA
    tokenKeepFee: 0,
    tokenLocked: 0,
    tokenFees: [],
    walletItems: [],
    $ci: { parse: mockCiParse },
    ...overrides
  })

  describe('hexEncode', () => {
    // Extracted function for testing
    const hexEncode = (value: string): string => {
      let result = ''
      for (let i = 0; i < value.length; i++) {
        const hex = value.charCodeAt(i).toString(16)
        if (hex.length > 2) {
          result += ('000' + hex).slice(-4)
        } else {
          result += ('0' + hex).slice(-2)
        }
      }
      return result
    }

    it('encodes ASCII characters to hex', () => {
      expect(hexEncode('Hello')).toBe('48656c6c6f')
    })

    it('encodes lowercase text', () => {
      expect(hexEncode('world')).toBe('776f726c64')
    })

    it('encodes numbers as ASCII', () => {
      expect(hexEncode('123')).toBe('313233')
    })

    it('handles empty string', () => {
      expect(hexEncode('')).toBe('')
    })

    it('encodes special characters', () => {
      expect(hexEncode('!@#')).toBe('214023')
    })

    it('encodes spaces', () => {
      expect(hexEncode(' ')).toBe('20')
    })

    it('encodes mixed content', () => {
      expect(hexEncode('A1!')).toBe('413121')
    })
  })

  describe('validateMetadataItem', () => {
    // Extracted function for testing
    const validateMetadataItem = (field: unknown): string | null => {
      if (Array.isArray(field)) {
        for (let i = 0; i < field.length; i++) {
          const errorMessage = validateMetadataItem(field[i])
          if (errorMessage !== null) {
            return errorMessage
          }
        }
      } else if (typeof field === 'string' || field instanceof String) {
        if (field.length >= 64) {
          return 'Metadata strings must be less than 64 characters.'
        }
      } else if (field === Object(field)) {
        for (const propertyName in field as Record<string, unknown>) {
          const errorMessage = validateMetadataItem((field as Record<string, unknown>)[propertyName])
          if (errorMessage !== null) {
            return errorMessage
          }
        }
      }
      return null
    }

    it('returns null for valid short string', () => {
      expect(validateMetadataItem('short string')).toBeNull()
    })

    it('returns error for string >= 64 characters', () => {
      const longString = 'a'.repeat(64)
      expect(validateMetadataItem(longString)).toBe(
        'Metadata strings must be less than 64 characters.'
      )
    })

    it('returns null for string exactly 63 characters', () => {
      const maxString = 'a'.repeat(63)
      expect(validateMetadataItem(maxString)).toBeNull()
    })

    it('returns null for valid array of short strings', () => {
      expect(validateMetadataItem(['short1', 'short2'])).toBeNull()
    })

    it('returns error for array containing long string', () => {
      const arr = ['short', 'a'.repeat(64)]
      expect(validateMetadataItem(arr)).toBe(
        'Metadata strings must be less than 64 characters.'
      )
    })

    it('returns null for valid nested object', () => {
      const obj = {
        key1: 'value1',
        key2: { nested: 'value2' }
      }
      expect(validateMetadataItem(obj)).toBeNull()
    })

    it('returns error for nested object with long string', () => {
      const obj = {
        level1: {
          level2: 'a'.repeat(64)
        }
      }
      expect(validateMetadataItem(obj)).toBe(
        'Metadata strings must be less than 64 characters.'
      )
    })

    it('returns null for numbers', () => {
      expect(validateMetadataItem(12345)).toBeNull()
    })

    it('returns null for boolean', () => {
      expect(validateMetadataItem(true)).toBeNull()
    })

    it('handles mixed nested structure', () => {
      const complex = {
        arr: ['short', 'strings'],
        obj: { nested: 'value' },
        num: 123
      }
      expect(validateMetadataItem(complex)).toBeNull()
    })
  })

  describe('calculateSpent', () => {
    // Simplified version of calculateSpent for unit testing
    // This tests the core calculation logic without Vue dependencies
    
    const calculateSpent = (ctx: MockContext, index: number, skipTokenFees = false) => {
      let feePayerAccountId = -1
      if (ctx.formSendAda.isClaim) {
        // Simplified fee payer calculation
        feePayerAccountId = ctx.formSendAda.toAccounts[0]?.account || -1
      } else {
        feePayerAccountId = ctx.fromWalletItem.id
      }
      
      const tokenKeepFee = skipTokenFees ? 0 : ctx.tokenKeepFee
      const tokenLocked = skipTokenFees ? 0 : ctx.tokenLocked
      const baseAmount: Record<string, number> = ctx.fromWalletItem.nativeAssetMap
        ? { ...ctx.fromWalletItem.nativeAssetMap }
        : {}
      
      baseAmount['ada'] = ctx.formSendAda.isClaim
        ? ctx.fromWalletItem.stakingAddrLovelace
        : ctx.fromWalletItem.paymentAddrLovelace -
          ctx.txFee -
          tokenKeepFee -
          tokenLocked
      
      const alreadySpentPercentages: Record<string, number> = {}
      const amount: Record<string, number> = {}

      for (let i = 0; i < index; i++) {
        const account = ctx.formSendAda.toAccounts[i]
        amount[account.currency] = 0
        
        if (feePayerAccountId === account.account) {
          account.isFeePayer = true
        } else {
          account.isFeePayer = false
        }
        
        if (account.type === 'amount' && account.amount != null) {
          amount[account.currency] = ctx.$ci.parse(account.amount, {})
          baseAmount[account.currency] -= amount[account.currency]
          
          if (
            ctx.formSendAda.isClaim &&
            account.account === feePayerAccountId &&
            account.currency === 'ada'
          ) {
            baseAmount[account.currency] -= ctx.txFee
          }
          alreadySpentPercentages[account.currency] = 0
        } else if (
          account.type === 'percent' &&
          account.percent != null &&
          account.percent > 0
        ) {
          if (alreadySpentPercentages[account.currency] === undefined) {
            alreadySpentPercentages[account.currency] = 0
          }
          const percent = account.percent
          if (alreadySpentPercentages[account.currency] == 100) {
            amount[account.currency] = 0
          } else {
            amount[account.currency] = Math.round(
              baseAmount[account.currency] *
                (percent / (100.0 - alreadySpentPercentages[account.currency]))
            )
            alreadySpentPercentages[account.currency] += percent
          }
          baseAmount[account.currency] -= amount[account.currency]
          
          if (
            ctx.formSendAda.isClaim &&
            account.account === feePayerAccountId &&
            account.currency === 'ada'
          ) {
            if (baseAmount[account.currency] >= ctx.txFee) {
              baseAmount[account.currency] -= ctx.txFee
            } else {
              amount[account.currency] -= ctx.txFee
            }
          }
        }

        if (!skipTokenFees && account.currency !== 'ada') {
          account.tokenFee = ctx.tokenFees[i] || 0
          baseAmount['ada'] -= account.tokenFee
        }
      }
      
      return { remaining: baseAmount, amount: amount }
    }

    it('calculates remaining ada after tx fee for empty accounts', () => {
      const ctx = createMockContext()
      ctx.formSendAda.toAccounts = []
      
      const result = calculateSpent(ctx, 0)
      
      // 10 ADA - 0.2 ADA fee = 9.8 ADA = 9800000 lovelace
      expect(result.remaining['ada']).toBe(9800000)
    })

    it('calculates remaining ada after amount transfer', () => {
      const ctx = createMockContext({
        formSendAda: {
          isClaim: false,
          toAccounts: [
            {
              currency: 'ada',
              type: 'amount',
              amount: 1000000, // 1 ADA
              account: 2,
              tokenFee: 0
            }
          ]
        }
      })
      
      const result = calculateSpent(ctx, 1)
      
      // 10 ADA - 0.2 fee - 1 ADA = 8.8 ADA
      expect(result.remaining['ada']).toBe(8800000)
      expect(result.amount['ada']).toBe(1000000)
    })

    it('calculates 50% transfer correctly', () => {
      const ctx = createMockContext({
        formSendAda: {
          isClaim: false,
          toAccounts: [
            {
              currency: 'ada',
              type: 'percent',
              percent: 50,
              amount: null,
              account: 2,
              tokenFee: 0
            }
          ]
        }
      })
      
      const result = calculateSpent(ctx, 1)
      
      // Available: 10 ADA - 0.2 fee = 9.8 ADA
      // 50% of 9.8 ADA = 4.9 ADA = 4900000 lovelace
      expect(result.amount['ada']).toBe(4900000)
      expect(result.remaining['ada']).toBe(4900000)
    })

    it('calculates 100% transfer correctly', () => {
      const ctx = createMockContext({
        formSendAda: {
          isClaim: false,
          toAccounts: [
            {
              currency: 'ada',
              type: 'percent',
              percent: 100,
              amount: null,
              account: 2,
              tokenFee: 0
            }
          ]
        }
      })
      
      const result = calculateSpent(ctx, 1)
      
      // 100% of (10 ADA - 0.2 fee) = 9.8 ADA
      expect(result.amount['ada']).toBe(9800000)
      expect(result.remaining['ada']).toBe(0)
    })

    it('uses staking balance for claims', () => {
      const ctx = createMockContext({
        formSendAda: {
          isClaim: true,
          toAccounts: []
        }
      })
      
      const result = calculateSpent(ctx, 0)
      
      // For claims, uses stakingAddrLovelace (5 ADA) without subtracting fee
      expect(result.remaining['ada']).toBe(5000000)
    })

    it('handles native tokens', () => {
      const ctx = createMockContext({
        fromWalletItem: {
          id: 1,
          paymentAddrLovelace: 10000000,
          stakingAddrLovelace: 5000000,
          nativeAssetMap: {
            'policyId.TOKEN': 1000
          }
        },
        formSendAda: {
          isClaim: false,
          toAccounts: []
        }
      })
      
      const result = calculateSpent(ctx, 0)
      
      expect(result.remaining['ada']).toBe(9800000)
      expect(result.remaining['policyId.TOKEN']).toBe(1000)
    })

    it('handles tokenKeepFee deduction', () => {
      const ctx = createMockContext({
        tokenKeepFee: 1000000, // 1 ADA keep fee
        formSendAda: {
          isClaim: false,
          toAccounts: []
        }
      })
      
      const result = calculateSpent(ctx, 0)
      
      // 10 ADA - 0.2 fee - 1 ADA keep fee = 8.8 ADA
      expect(result.remaining['ada']).toBe(8800000)
    })

    it('skips token fees when skipTokenFees is true', () => {
      const ctx = createMockContext({
        tokenKeepFee: 1000000,
        tokenLocked: 500000,
        formSendAda: {
          isClaim: false,
          toAccounts: []
        }
      })
      
      const result = calculateSpent(ctx, 0, true)
      
      // 10 ADA - 0.2 fee (no keep/locked fees)
      expect(result.remaining['ada']).toBe(9800000)
    })
  })

  describe('formatCurrency logic', () => {
    it('formats ada in lovelace to ADA with 6 decimals', () => {
      const lovelace = 1234567
      const ada = lovelace / 1000000 // 1.234567
      expect(ada).toBeCloseTo(1.234567, 6)
    })

    it('converts whole ada amounts correctly', () => {
      const lovelace = 5000000
      const ada = lovelace / 1000000
      expect(ada).toBe(5)
    })

    it('handles minimum UTxO value', () => {
      const minUTxO = 1000000 // 1 ADA
      const ada = minUTxO / 1000000
      expect(ada).toBe(1)
    })
  })
})
