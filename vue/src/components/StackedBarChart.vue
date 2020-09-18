<template>
  <b-card
    no-body
    border-variant="secondary"
    :header="title"
    header-border-variant="secondary"
    align="center"
  >
    <b-card-text>
      <apexchart type="bar" :options="options" :series="series"></apexchart>
    </b-card-text>
  </b-card>
</template>

<script>
export default {
  name: "StackedBarChart",
  props: ["title", "series", "categories", "min"],
  data() {
    return {
      options: {
        chart: {
          id: "vuechart",
          animations: {
            enabled: false,
          },
          type: "bar",
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
              reset: false,
            },
          },
        },
        plotOptions: {
          bar: {
            horizontal: true,
          },
        },
        colors: ["#dc3545", "#ffc107", "#28a745"],
        xaxis: {
          type: "category",
          categories: this.categories,
          labels: {
            style: {
              colors: "#6c757d",
            },
          },
        },
        yaxis: {
          max: 93,
          title: {
            text: undefined,
          },
          labels: {
            style: {
              colors: "#6c757d",
            },
          },
        },
        grid: {
          row: {
            colors: ["#00000022"],
          },
        },
        legend: {
          labels: {
            colors: "#FFFFFF",
          },
        },
        tooltip: {
          enabled: false,
          theme: "dark",
        },
        dataLabels: {
          enabled: false,
        },
        fill: {
          opacity: 1,
          type: "gradient",
          gradient: {
            shade: "light",
            shadeIntensity: 0.2,
            opacityFrom: 0.9,
            opacityTo: 0.9,
            stops: [0, 90, 100],
          },
        },
      },
    };
  },
};
</script>