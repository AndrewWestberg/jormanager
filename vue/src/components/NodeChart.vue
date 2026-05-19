<template>
  <BCard
    no-body
    border-variant="secondary"
    :header="title"
    header-border-variant="secondary"
    align="center"
  >
    <BCardText>
      <apexchart type="area" :options="chartOptions" :series="series"></apexchart>
    </BCardText>
  </BCard>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { BCard, BCardText } from 'bootstrap-vue-next'
import moment from 'moment-timezone'

interface Props {
  title: string
  series: Array<{ name: string; data: [number, number][] }>
  colors?: string[]
  min?: number
}

const props = withDefaults(defineProps<Props>(), {
  colors: () => [],
  min: undefined
})

const chartOptions = computed(() => ({
  chart: {
    id: `node-chart-${props.title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`,
    animations: {
      enabled: false
    },
    toolbar: {
      show: false,
      tools: {
        download: false,
        selection: false,
        zoom: false,
        zoomin: false,
        zoomout: false,
        pan: false,
        reset: false
      }
    }
  },
  colors: props.colors,
  xaxis: {
    type: 'datetime',
    labels: {
      style: {
        colors: '#6c757d'
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
        colors: '#6c757d'
      }
    }
  },
  grid: {
    row: {
      colors: ['#00000022']
    }
  },
  legend: {
    labels: {
      colors: '#FFFFFF'
    }
  },
  tooltip: {
    theme: 'dark'
  },
  dataLabels: {
    enabled: false
  },
  fill: {
    opacity: 0.1,
    type: 'gradient',
    gradient: {
      shade: 'light',
      shadeIntensity: 0.2,
      opacityFrom: 0.5,
      opacityTo: 0.3,
      stops: [0, 90, 100]
    }
  },
  stroke: {
    curve: 'straight',
    width: 1
  },
  markers: {
    strokeWidth: 1,
    radius: 1,
    hover: {
      sizeOffset: 2
    }
  }
}))
</script>
