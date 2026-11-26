import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    globals: true,
    environment: 'jsdom',
    include: ['tests/unit/**/*.spec.ts'],
    reporters: ['default', 'junit', 'json'],
    outputFile: {
      junit: fileURLToPath(new URL('../build/reports/vue-tests/junit.xml', import.meta.url)),
      json: fileURLToPath(new URL('../build/reports/vue-tests/results.json', import.meta.url))
    },
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      exclude: ['node_modules/', 'tests/']
    }
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  }
})
