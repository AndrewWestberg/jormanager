import { describe, expect, it } from 'vitest'
import {
  defaultTracingListenForHost,
  isCustomTracingListen,
  syncTracingListenForHost
} from '@/utils/tracing'

const localHost = {
  id: 1,
  type: 'local' as const,
  hostname: 'localhost',
  sshUser: 'westbam',
  sshPort: 22,
  sshPemPath: '',
  cardanoCliPath: '/usr/bin/cardano-cli',
  cardanoNodePath: '/usr/bin/cardano-node',
  nodeHomePath: '/srv/cardano',
  jcliPath: null
}

const remoteHost = {
  id: 2,
  type: 'remote' as const,
  hostname: 'relay.example.com',
  sshUser: 'westbam',
  sshPort: 22,
  sshPemPath: '',
  cardanoCliPath: '/usr/bin/cardano-cli',
  cardanoNodePath: '/usr/bin/cardano-node',
  nodeHomePath: '/srv/cardano',
  jcliPath: null
}

describe('tracing utils', () => {
  it('defaults tracing bind to localhost for local hosts', () => {
    expect(defaultTracingListenForHost(localHost)).toBe('127.0.0.1')
  })

  it('defaults tracing bind to all interfaces for remote hosts', () => {
    expect(defaultTracingListenForHost(remoteHost)).toBe('0.0.0.0')
  })

  it('defaults to all interfaces when the host is not selected yet', () => {
    expect(defaultTracingListenForHost(null)).toBe('0.0.0.0')
  })

  it('updates to the host default while the user has not customized the value', () => {
    expect(syncTracingListenForHost('0.0.0.0', localHost, false)).toBe('127.0.0.1')
  })

  it('preserves the current value after the user customizes tracing bind', () => {
    expect(syncTracingListenForHost('127.0.0.1', remoteHost, true)).toBe('127.0.0.1')
  })

  it('detects when the current tracing bind differs from the selected host default', () => {
    expect(isCustomTracingListen('127.0.0.1', remoteHost)).toBe(true)
    expect(isCustomTracingListen('127.0.0.1', localHost)).toBe(false)
  })
})
