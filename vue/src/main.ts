import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

// Bootstrap and BootstrapVueNext
import { createBootstrap } from 'bootstrap-vue-next'
import 'bootstrap/dist/css/bootstrap.css'
import 'bootstrap-vue-next/dist/bootstrap-vue-next.css'

// FontAwesome
import { library } from '@fortawesome/fontawesome-svg-core'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
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
  faPlusCircle,
  faChevronDown
} from '@fortawesome/free-solid-svg-icons'

// ApexCharts
import VueApexCharts from 'vue3-apexcharts'

// Custom styles
import './assets/bootstrap.css'
import './App.css'

// Add FontAwesome icons to library
library.add(
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
  faPlusCircle,
  faChevronDown
)

// Create app
const app = createApp(App)

// Install plugins
app.use(createPinia())
app.use(router)
app.use(createBootstrap())
app.use(VueApexCharts)

// Register global components
app.component('font-awesome-icon', FontAwesomeIcon)
app.component('apexchart', VueApexCharts)

// Register global directives
import { vBTooltip, vBToggle } from 'bootstrap-vue-next'
app.directive('b-tooltip', vBTooltip)
app.directive('b-toggle', vBToggle)

// Mount the app
app.mount('#app')
