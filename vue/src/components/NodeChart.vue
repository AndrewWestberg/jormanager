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
  props: ["title", "series", "colors"],
  data() {
    return {
      options: {
        chart: {
          id: "vuechart-example",
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
  },
  watch: {
    nodeColors(value) {
      this.options.colors = value;
    }
  }
};
</script>