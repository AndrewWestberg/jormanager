<template>
  <BCard
    no-body
    border-variant="secondary"
    :header="title"
    header-border-variant="secondary"
    align="center"
  >
    <BCardText>
      <apexchart type="bar" :options="chartOptions" :series="series"></apexchart>
    </BCardText>
  </BCard>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { BCard, BCardText } from 'bootstrap-vue-next'

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
    animations: {
      enabled: false
    },
    type: 'bar',
    stacked: true,
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
  plotOptions: {
    bar: {
      horizontal: true
    }
  },
  colors: ['#dc3545', '#ffc107', '#28a745'],
  xaxis: {
    type: 'category',
    categories: props.categories,
    labels: {
      style: {
        colors: '#6c757d'
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
    enabled: false,
    theme: 'dark'
  },
  dataLabels: {
    enabled: false
  },
  fill: {
    opacity: 1,
    type: 'gradient',
    gradient: {
      shade: 'light',
      shadeIntensity: 0.2,
      opacityFrom: 0.9,
      opacityTo: 0.9,
      stops: [0, 90, 100]
    }
  }
}))
</script>
