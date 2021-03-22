import Vue from 'vue'
import Vue2Filters from 'vue2-filters'
import VueClipboard from 'vue-clipboard2'
import {
  BootstrapVue,
  BIcon,
  BIconPlus,
  BIconServer,
  BIconGraphUp,
  BIconPencilSquare,
  BIconHexagonHalf,
  BIconCaretUp,
  BIconCaretDown,
  BIconX
} from 'bootstrap-vue'

Vue.use(BootstrapVue)
Vue.component('BIcon', BIcon)
Vue.component('BIconPlus', BIconPlus)
Vue.component('BIconServer', BIconServer)
Vue.component('BIconGraphUp', BIconGraphUp)
Vue.component('BIconPencilSquare', BIconPencilSquare)
Vue.component('BIconHexagonHalf', BIconHexagonHalf)
Vue.component('BIconCaretUp', BIconCaretUp)
Vue.component('BIconCaretDown', BIconCaretDown)
Vue.component('BIconX', BIconX)

Vue.use(require('vue-moment'))
Vue.use(Vue2Filters)
Vue.use(VueClipboard)

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
  faTrashAlt,
  faCopy,
  faCashRegister,
  faHandHoldingUsd,
  faWeightHanging,
  faBalanceScaleRight,
  faPowerOff,
  faFileArchive,
  faLink,
  faUnlink,
  faKey,
  faCubes,
  faPercent,
  faClock,
  faDumpsterFire,
  faCube,
  faHammer,
  faGhost,
  faClipboardList,
  faHatWizard,
  faInfoCircle,
  faSkull,
  faProjectDiagram,
  faCoins,
  faPlusCircle
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
library.add(faCopy)
library.add(faCashRegister)
library.add(faHandHoldingUsd)
library.add(faWeightHanging)
library.add(faBalanceScaleRight)
library.add(faPowerOff)
library.add(faFileArchive)
library.add(faLink)
library.add(faUnlink)
library.add(faKey)
library.add(faCubes)
library.add(faPercent)
library.add(faClock)
library.add(faDumpsterFire)
library.add(faCube)
library.add(faHammer)
library.add(faGhost)
library.add(faClipboardList)
library.add(faHatWizard)
library.add(faInfoCircle)
library.add(faSkull)
library.add(faPlusCircle)
library.add(faProjectDiagram)
library.add(faCoins)

Vue.component('font-awesome-icon', FontAwesomeIcon)

import VueApexCharts from 'vue-apexcharts'
Vue.component('apexchart', VueApexCharts)

import VueCurrencyInput from 'vue-currency-input'
const pluginOptions = {
  globalOptions: {
    currency: {
      prefix: '₳'
    },
    precision: 6,
    valueAsInteger: true,
    allowNegative: false,
    distractionFree: {
      hideNegligibleDecimalDigits: true,
      hideCurrencySymbol: false,
      hideGroupingSymbol: true
    }
  }
}
Vue.use(VueCurrencyInput, pluginOptions)

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