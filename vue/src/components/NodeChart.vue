<template>
  <section class="jm-card jm-chart-card">
    <div class="jm-card-header jm-chart-header">
      <h2 class="jm-chart-title">
        {{ title }}
      </h2>
      <div
        v-if="latestValues.length"
        class="jm-chart-pills"
      >
        <span
          v-for="item in latestValues"
          :key="item.name"
          class="jm-node-pill jm-mono"
        >
          <span
            class="jm-dot"
            :style="{ backgroundColor: item.color }"
          />
          <span>{{ item.name }}</span>
          <strong>{{ item.value.toLocaleString() }}</strong>
        </span>
      </div>
    </div>
    <div class="jm-chart-body">
      <apexchart
        v-if="series.length > 0"
        type="area"
        height="260"
        :options="chartOptions"
        :series="series"
      />
      <div
        v-else
        class="jm-chart-empty"
      >
        <font-awesome-icon
          :icon="['fas', 'chart-line']"
          class="mb-2 opacity-50"
          aria-hidden="true"
        />
        <span>Awaiting live node telemetry…</span>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import moment from 'moment-timezone'

interface Props {
  title: string
  series: Array<{ name: string; data: [number, number][] }>
  colors?: string[]
  min?: number
  curve?: 'smooth' | 'straight' | 'stepline'
}

const props = withDefaults(defineProps<Props>(), {
  colors: () => [],
  min: undefined,
  curve: 'smooth'
})

const fallbackPalette = ['#38bdf8', '#a855f7', '#10b981', '#f59e0b', '#f43f5e', '#818cf8']

const resolvedColors = computed(() =>
  props.series.map((_, idx) => props.colors[idx] || fallbackPalette[idx % fallbackPalette.length])
)

const latestValues = computed(() =>
  props.series
    .map((s, idx) => {
      const point = s.data[s.data.length - 1]
      if (!point) return null
      return {
        name: s.name,
        value: point[1],
        color: resolvedColors.value[idx]
      }
    })
    .filter((entry): entry is { name: string; value: number; color: string } => entry !== null)
)

const chartOptions = computed(() => ({
  chart: {
    id: `node-chart-${props.title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`,
    background: 'transparent',
    foreColor: '#94a3b8',
    fontFamily: "'JetBrains Mono', ui-monospace, monospace",
    animations: {
      enabled: false
    },
    toolbar: {
      show: false
    },
    zoom: {
      enabled: false
    }
  },
  theme: {
    mode: 'dark' as const
  },
  colors: resolvedColors.value,
  xaxis: {
    type: 'datetime' as const,
    axisBorder: {
      color: 'rgba(148, 163, 184, 0.16)'
    },
    axisTicks: {
      color: 'rgba(148, 163, 184, 0.16)'
    },
    labels: {
      style: {
        colors: '#94a3b8',
        fontSize: '11px'
      },
      formatter: (_value: string, timestamp: number) => {
        return moment(timestamp).format('LTS')
      }
    }
  },
  yaxis: {
    min: props.min,
    forceNiceScale: true,
    labels: {
      style: {
        colors: '#94a3b8',
        fontSize: '11px'
      },
      formatter: (val: number) => Number.isFinite(val) ? Math.round(val).toLocaleString() : ''
    }
  },
  grid: {
    borderColor: 'rgba(148, 163, 184, 0.12)',
    strokeDashArray: 4,
    padding: {
      left: 8,
      right: 12
    }
  },
  legend: {
    position: 'bottom' as const,
    horizontalAlign: 'center' as const,
    labels: {
      colors: '#cbd5e1'
    },
    markers: {
      size: 6
    }
  },
  tooltip: {
    theme: 'dark',
    x: {
      format: 'HH:mm:ss'
    }
  },
  dataLabels: {
    enabled: false
  },
  fill: {
    type: 'gradient',
    gradient: {
      shadeIntensity: 1,
      opacityFrom: 0.34,
      opacityTo: 0.02,
      stops: [0, 92, 100]
    }
  },
  stroke: {
    curve: props.curve,
    width: 2
  },
  markers: {
    size: 0,
    hover: {
      size: 4
    }
  }
}))
</script>

<style scoped>
.jm-chart-card {
  display: flex;
  flex-direction: column;
}

.jm-chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.jm-chart-title {
  margin: 0;
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--jm-text-primary);
}

.jm-chart-pills {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  flex-wrap: wrap;
}

.jm-node-pill {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 0.72rem;
  padding: 0.15rem 0.52rem;
  border-radius: var(--jm-radius-pill);
  background: var(--jm-bg-input);
  border: 1px solid var(--jm-border-subtle);
  color: #cbd5e1;
}

.jm-node-pill strong {
  color: var(--jm-text-primary);
  font-weight: 600;
}

.jm-chart-body {
  padding: 0.75rem 0.85rem 0.5rem;
  flex: 1;
}

.jm-chart-empty {
  min-height: 240px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  font-size: 0.85rem;
  color: var(--jm-text-muted);
}
</style>
