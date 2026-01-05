import mitt from 'mitt'

// Define the events that can be emitted
type Events = {
  'edit-host': unknown
  'add-host': void
  'show-send-ada-modal': { walletItem?: unknown; walletItems?: unknown[]; isClaim: boolean; isMultiClaim?: boolean }
  'hide-send-ada-modal': void
  'show-add-node-wizard': void
  'hide-add-node-wizard': void
  'show-add-wallet-wizard': void
  'hide-add-wallet-wizard': void
  'show-spending-password-modal': { action: string; data?: unknown }
  'confirm-spending-password': string
  'confirm-spending-password-with-action': { action: string; password: string; originalData: unknown }
}

// Create the event emitter instance
export const emitter = mitt<Events>()

// Composable for using the event bus in components
export function useEventBus() {
  return emitter
}
