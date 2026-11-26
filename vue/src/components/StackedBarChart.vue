<template>
  <section class="jm-card jm-chart-card">
    <div class="jm-card-header jm-chart-header">
      <h2 class="jm-chart-title">
        {{ title }}
      </h2>
      <span class="jm-pill jm-pill-slate jm-mono">93d KES Window</span>
    </div>
    <div class="jm-chart-body">
      <apexchart
        v-if="categories.length > 0"
        type="bar"
        height="260"
        :options="chartOptions"
        :series="series"
      />
      <div
        v-else
        class="jm-chart-empty"
      >
        <font-awesome-icon
          :icon="['fas', 'key']"
          class="mb-2 opacity-50"
          aria-hidden="true"
        />
        <span>No core node KES metrics reported yet…</span>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface KESSeries {
  name: string
  data: number[]
}

interface Props {
  title: string
  series: KESSeries[]
  categories: string[]
  min?: number
}

const props = withDefaults(defineProps<Props>(), {
  min: undefined
})

const chartOptions = computed(() => ({
  chart: {
    id: `stacked-bar-chart-${props.title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`,
    background: 'transparent',
    foreColor: '#94a3b8',
    fontFamily: "'JetBrains Mono', ui-monospace, monospace",
    animations: {
      enabled: false
    },
    type: 'bar' as const,
    stacked: true,
    toolbar: {
      show: false
    }
  },
  theme: {
    mode: 'dark' as const
  },
  plotOptions: {
    bar: {
      horizontal: true,
      barHeight: '52%',
      borderRadius: 4
    }
  },
  colors: ['#f43f5e', '#f59e0b', '#10b981'],
  xaxis: {
    type: 'category' as const,
    categories: props.categories,
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
      }
    }
  },
  yaxis: {
    max: 93,
    title: {
      text: undefined
    },
    labels: {
      style: {
        colors: '#cbd5e1',
        fontSize: '11px'
      }
    }
  },
  grid: {
    borderColor: 'rgba(148, 163, 184, 0.12)',
    strokeDashArray: 4
  },
  legend: {
    position: 'bottom' as const,
    labels: {
      colors: '#cbd5e1'
    }
  },
  tooltip: {
    enabled: true,
    theme: 'dark',
    y: {
      formatter: (val: number) => `${val.toFixed(1)} days`
    }
  },
  dataLabels: {
    enabled: false
  },
  fill: {
    opacity: 0.92
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
}

.jm-chart-title {
  margin: 0;
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--jm-text-primary);
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
