<template>
  <div>
    <div>
      <b-card-group deck>
        <b-card
          no-body
          border-variant="secondary"
          header="Block Height"
          header-border-variant="secondary"
          align="center"
        >
          <b-card-text>
            <apexchart type="bar" :options="options" :series="series"></apexchart>
          </b-card-text>
        </b-card>
        <b-card
          no-body
          border-variant="secondary"
          header="Block Height"
          header-border-variant="secondary"
          align="center"
        >
          <b-card-text>
            <apexchart type="line" :options="options" :series="series"></apexchart>
          </b-card-text>
        </b-card>
        <b-card
          no-body
          border-variant="secondary"
          header="Secondary"
          header-border-variant="secondary"
          align="center"
        >
          <apexchart type="bar" :options="options" :series="series"></apexchart>
        </b-card>
      </b-card-group>
    </div>
    <div></div>
    <h1>Total Blocks: {{ blocksCount }}</h1>
  </div>
</template>

<script>
import { mapGetters } from "vuex";

export default {
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
        colors: ["#0096FF80", "#C9061080"],
        xaxis: {
          categories: [1991, 1992, 1993, 1994, 1995, 1996, 1997, 1998],
          labels: {
            style: {
              colors: "#6c757d"
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
        fill: {}
      },
      series: [
        {
          name: "node",
          data: [30, 40, 45, 50, 49, 60, 70, 91]
        },
        {
          name: "bcsh",
          data: [90, 80, 74, 35, 49, 23, 37, 15]
        }
      ]
    };
  },
  methods: {},
  computed: {
    ...mapGetters(["blocksCount"])
  }
};
</script>