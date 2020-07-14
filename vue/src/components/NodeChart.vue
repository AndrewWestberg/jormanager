<template>
  <b-card
    no-body
    border-variant="secondary"
    :header="title"
    header-border-variant="secondary"
    align="center"
  >
    <b-card-text>
      <apexchart type="area" :options="options" :series="series"></apexchart>
    </b-card-text>
  </b-card>
</template>

<script>
import moment from "moment-timezone";
export default {
  name: "NodeChart",
  props: ["title", "series", "colors", "min"],
  data() {
    return {
      options: {
        chart: {
          id: "vuechart",
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
        colors: this.colors,
        xaxis: {
          type: "datetime",
          labels: {
            style: {
              colors: "#6c757d"
            },
            formatter: (value, timestamp) => {
              return moment(timestamp).format("LTS");
            }
          }
        },
        yaxis: {
          min: this.min,
          forceNiceScale: true,
          labels: {
            style: {
              colors: "#6c757d"
            }
          }
        },
        grid: {
          row: {
            colors: ["#00000022"]
          }
        },
        legend: {
          labels: {
            colors: "#FFFFFF"
          }
        },
        tooltip: {
          theme: "dark"
        },
        dataLabels: {
          enabled: false
        },
        fill: {
          opacity: 0.1,
          type: "gradient",
          gradient: {
            shade: "light",
            shadeIntensity: 0.2,
            opacityFrom: 0.5,
            opacityTo: 0.3,
            stops: [0, 90, 100]
          }
        },
        stroke: {
          curve: "straight",
          width: 1
        },
        markers: {
          strokeWidth: 1,
          radius: 1,
          hover: {
            sizeOffset: 2
          }
        }
      }
    };
  }
};
</script>