/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

declare module 'webstomp-client' {
  export interface Client {
    connected: boolean
    connect(
      headers: Record<string, string>,
      connectCallback: () => void,
      errorCallback?: (error: unknown) => void
    ): void
    subscribe(destination: string, callback: (message: Message) => void): void
    send(destination: string, body?: string): void
    disconnect(callback?: () => void): void
  }

  export interface Message {
    body: string
    headers: Record<string, string>
    ack(): void
    nack(): void
  }

  export function over(socket: unknown, options?: { debug?: boolean }): Client
}

declare module 'sockjs-client' {
  export default class SockJS {
    constructor(url: string, protocols?: string | string[], options?: Record<string, unknown>)
    close(): void
    send(data: string): void
    onopen: (() => void) | null
    onclose: (() => void) | null
    onmessage: ((event: { data: string }) => void) | null
    onerror: ((error: unknown) => void) | null
  }
}

declare module 'json-bigint' {
  export function parse(text: string): unknown
  export function stringify(value: unknown): string
}
