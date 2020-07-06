import Vue from 'vue'
import Vue2Filters from 'vue2-filters'
import {
  BootstrapVue,
  BIcon,
  BIconPlus,
  BIconServer,
  BIconGraphUp,
  BIconPencilSquare,
  BIconHexagonHalf
} from 'bootstrap-vue'

Vue.use(BootstrapVue)
Vue.component('BIcon', BIcon)
Vue.component('BIconPlus', BIconPlus)
Vue.component('BIconServer', BIconServer)
Vue.component('BIconGraphUp', BIconGraphUp)
Vue.component('BIconPencilSquare', BIconPencilSquare)
Vue.component('BIconHexagonHalf', BIconHexagonHalf)

Vue.use(require('vue-moment'))
Vue.use(Vue2Filters)

import App from './App.vue'

import {
  library
} from '@fortawesome/fontawesome-svg-core'
import {
  faWallet,
  faDiceD20,
  faEdit,
  faServer,
  faChartLine,
  faCircle,
  faCheckCircle,
  faTrashAlt
} from '@fortawesome/free-solid-svg-icons'
import {
  FontAwesomeIcon
} from '@fortawesome/vue-fontawesome'

library.add(faWallet)
library.add(faDiceD20)
library.add(faEdit)
library.add(faServer)
library.add(faChartLine)
library.add(faCircle)
library.add(faCheckCircle)
library.add(faTrashAlt)

Vue.component('font-awesome-icon', FontAwesomeIcon)

import VueApexCharts from 'vue-apexcharts'
Vue.component('apexchart', VueApexCharts)

import router from './router'
import store from './store/index'

//import 'bootstrap/dist/css/bootstrap.css'
import './assets/bootstrap.css'
import 'bootstrap-vue/dist/bootstrap-vue.css'
import './App.css'

Vue.config.productionTip = false

new Vue({
  router,
  store,
  render: h => h(App),
}).$mount('#app')