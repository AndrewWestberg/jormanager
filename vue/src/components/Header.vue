<template>
  <header
    id="header"
    class="jm-header"
  >
    <div class="jm-header-inner">
      <RouterLink
        to="/"
        class="jm-brand"
        @click="mobileNavOpen = false"
      >
        <span
          class="jm-brand-logo"
          aria-hidden="true"
        >
          <img
            :src="jormanagerLogo"
            alt=""
            class="jm-brand-img"
            width="32"
            height="32"
          >
        </span>
        <span class="jm-brand-name">JorManager</span>
        <span class="jm-version-pill jm-mono">{{ displayVersion }}</span>
      </RouterLink>

      <button
        type="button"
        class="jm-mobile-toggle"
        :aria-expanded="mobileNavOpen"
        aria-controls="primary-nav-list"
        aria-label="Toggle navigation menu"
        @click="mobileNavOpen = !mobileNavOpen"
      >
        <font-awesome-icon
          :icon="['fas', mobileNavOpen ? 'times' : 'bars']"
          aria-hidden="true"
        />
        <span>Menu</span>
      </button>

      <nav
        class="jm-nav-wrap"
        :class="{ open: mobileNavOpen }"
        aria-label="Primary"
      >
        <ul
          id="primary-nav-list"
          class="jm-nav-list"
        >
          <li
            v-for="item in navItems"
            :key="item.to"
          >
            <RouterLink
              v-slot="{ href, navigate, isExactActive }"
              :to="item.to"
              custom
            >
              <a
                :href="href"
                class="jm-nav-link"
                :aria-current="isExactActive ? 'page' : undefined"
                @click="(event) => { mobileNavOpen = false; navigate(event) }"
              >
                <font-awesome-icon
                  :icon="item.icon"
                  aria-hidden="true"
                />
                <span>{{ item.label }}</span>
                <span
                  v-if="item.to === '/hosts' && mismatchedHosts.length > 0"
                  class="jm-nav-alert-badge jm-mono"
                  :aria-label="`${mismatchedHosts.length} SSH host key mismatch alert`"
                >
                  {{ mismatchedHosts.length }}
                </span>
              </a>
            </RouterLink>
          </li>
        </ul>
      </nav>

      <RouterLink
        v-if="showMiniEpoch"
        to="/"
        class="jm-mini-epoch"
        title="View live epoch telemetry on Dashboard"
        @click="mobileNavOpen = false"
      >
        <span class="jm-mono jm-mini-epoch-num">Ep {{ epoch }}</span>
        <span
          class="jm-mini-bar"
          aria-hidden="true"
        >
          <span
            class="jm-mini-bar-fill"
            :style="{ width: `${epochPercent}%` }"
          />
        </span>
        <span class="jm-mono">{{ epochPercent }}%</span>
      </RouterLink>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useJorManagerStore } from '@/stores/jormanager'
import jormanagerLogo from '@/assets/jormanager-logo.png'

const route = useRoute()
const store = useJorManagerStore()
const { appVersion, epoch, slot, epochLength, mismatchedHosts } = storeToRefs(store)

const showMiniEpoch = computed(() => epoch.value > 0 && route.path !== '/')

const displayVersion = computed(() => {
  const raw = appVersion.value?.trim() || '---'
  return raw.replace(/^jormanager\s*/i, '') || raw
})

const mobileNavOpen = ref(false)

const navItems: Array<{ to: string; label: string; icon: [string, string] }> = [
  { to: '/', label: 'Dashboard', icon: ['fas', 'chart-line'] },
  { to: '/hosts', label: 'Hosts', icon: ['fas', 'server'] },
  { to: '/nodes', label: 'Nodes', icon: ['fas', 'dice-d20'] },
  { to: '/blocks', label: 'Blocks', icon: ['fas', 'cubes'] },
  { to: '/wallet', label: 'Wallet', icon: ['fas', 'wallet'] }
]

const epochPercent = computed(() => {
  if (!epochLength.value || epochLength.value <= 0) return 0
  const pct = Math.round((Math.min(slot.value, epochLength.value) / epochLength.value) * 100)
  return Math.max(0, Math.min(100, pct))
})

onMounted(() => {
  store.requestAppVersion()
})
</script>

<style scoped>
.jm-header {
  position: sticky;
  inset-block-start: 0;
  z-index: 60;
  background: rgba(10, 15, 26, 0.84);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--jm-border-subtle);
}

.jm-header-inner {
  max-width: 1420px;
  margin-inline: auto;
  padding: 0.7rem 1.5rem;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 1rem;
}

.jm-brand {
  display: inline-flex;
  align-items: center;
  gap: 0.68rem;
  justify-self: start;
  text-decoration: none;
  color: var(--jm-text-primary);
}

.jm-brand-logo {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: radial-gradient(circle at 50% 45%, rgba(226, 232, 240, 0.16), rgba(30, 41, 59, 0.85));
  border: 1px solid rgba(56, 189, 248, 0.36);
  box-shadow: 0 0 18px rgba(56, 189, 248, 0.14);
  padding: 3px;
}

.jm-brand-img {
  width: 32px;
  height: 32px;
  object-fit: contain;
  display: block;
  filter: drop-shadow(0 1px 3px rgba(0, 0, 0, 0.55));
}

.jm-brand-name {
  font-weight: 700;
  font-size: 1.04rem;
  letter-spacing: -0.02em;
}

.jm-version-pill {
  font-size: 0.7rem;
  color: var(--jm-text-secondary);
  background: var(--jm-bg-elevated);
  border: 1px solid var(--jm-border-subtle);
  padding: 0.12rem 0.5rem;
  border-radius: var(--jm-radius-pill);
}

.jm-nav-wrap {
  justify-self: center;
}

.jm-nav-list {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  background: rgba(15, 22, 36, 0.85);
  border: 1px solid var(--jm-border-subtle);
  padding: 0.25rem;
  border-radius: var(--jm-radius-md);
  list-style: none;
  margin: 0;
}

.jm-nav-link {
  display: inline-flex;
  align-items: center;
  gap: 0.48rem;
  padding: 0.46rem 0.9rem;
  border-radius: 9px;
  border: 1px solid transparent;
  font-size: 0.86rem;
  font-weight: 500;
  color: var(--jm-text-secondary);
  text-decoration: none;
  transition: background-color 0.15s ease, color 0.15s ease, border-color 0.15s ease;
}

.jm-nav-link:hover {
  color: var(--jm-text-primary);
  background: rgba(148, 163, 184, 0.08);
}

.jm-nav-link[aria-current='page'] {
  color: #e0f2fe;
  font-weight: 600;
  background: linear-gradient(180deg, rgba(56, 189, 248, 0.2), rgba(56, 189, 248, 0.1));
  border-color: rgba(56, 189, 248, 0.42);
  box-shadow: 0 2px 10px rgba(56, 189, 248, 0.12);
}

.jm-nav-alert-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding-inline: 0.32rem;
  border-radius: var(--jm-radius-pill);
  background: var(--jm-rose);
  color: #fff;
  font-size: 0.68rem;
  font-weight: 700;
  line-height: 1;
}

.jm-mini-epoch {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  justify-self: end;
  background: var(--jm-bg-elevated);
  border: 1px solid var(--jm-border-subtle);
  padding: 0.34rem 0.72rem;
  border-radius: var(--jm-radius-pill);
  font-size: 0.76rem;
  color: var(--jm-text-secondary);
  text-decoration: none;
  transition: border-color 0.15s ease;
}

.jm-mini-epoch:hover {
  border-color: var(--jm-border-accent);
  color: var(--jm-text-primary);
}

.jm-mini-epoch-num {
  color: var(--jm-text-primary);
  font-weight: 600;
}

.jm-mini-bar {
  width: 44px;
  height: 5px;
  border-radius: 99px;
  background: rgba(148, 163, 184, 0.2);
  overflow: hidden;
}

.jm-mini-bar-fill {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, var(--jm-cyan), var(--jm-emerald));
  border-radius: 99px;
}

.jm-mobile-toggle {
  display: none;
  appearance: none;
  align-items: center;
  gap: 0.45rem;
  background: var(--jm-bg-elevated);
  border: 1px solid var(--jm-border-strong);
  color: var(--jm-text-primary);
  border-radius: var(--jm-radius-sm);
  padding: 0.42rem 0.78rem;
  font-size: 0.84rem;
  font-weight: 600;
  cursor: pointer;
}

@media (max-width: 960px) {
  .jm-header-inner {
    display: flex;
    flex-wrap: wrap;
    gap: 0.65rem 0.75rem;
    padding: 0.65rem 1rem;
  }

  .jm-brand {
    order: 1;
    margin-inline-end: auto;
  }

  .jm-mini-epoch {
    order: 2;
  }

  .jm-mobile-toggle {
    display: inline-flex;
    order: 3;
  }

  .jm-nav-wrap {
    display: none;
    width: 100%;
    order: 4;
  }

  .jm-nav-wrap.open {
    display: block;
  }

  .jm-nav-list {
    flex-direction: column;
    align-items: stretch;
  }

  .jm-nav-link {
    width: 100%;
  }
}

@media (max-width: 540px) {
  .jm-version-pill,
  .jm-mini-bar,
  .jm-mobile-toggle span {
    display: none;
  }

  .jm-mobile-toggle {
    padding: 0.42rem 0.62rem;
  }
}
</style>
