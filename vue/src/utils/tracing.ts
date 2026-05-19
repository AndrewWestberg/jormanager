import type { Host } from '@/types'

export function defaultTracingListenForHost(host: Host | null | undefined): '127.0.0.1' | '0.0.0.0' {
  return host?.type === 'local' ? '127.0.0.1' : '0.0.0.0'
}

export function syncTracingListenForHost(
  currentValue: string,
  host: Host | null | undefined,
  hasCustomizedTracingListen: boolean
): string {
  if (hasCustomizedTracingListen) {
    return currentValue
  }

  return defaultTracingListenForHost(host)
}

export function isCustomTracingListen(
  value: string,
  host: Host | null | undefined
): boolean {
  return value !== defaultTracingListenForHost(host)
}
