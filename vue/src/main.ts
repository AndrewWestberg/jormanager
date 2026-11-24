import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

// Bootstrap 5.3 and BootstrapVueNext 1.x
import { createBootstrap, vBTooltip, vBToggle } from 'bootstrap-vue-next'
import 'bootstrap/dist/css/bootstrap.css'
import 'bootstrap-vue-next/dist/bootstrap-vue-next.css'

// FontAwesome 7 SVG Icons (zero external CDN CSS)
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
  faChevronDown,
  faCompressArrowsAlt,
  faLayerGroup,
  faExclamationTriangle,
  faCheckToSlot,
  faShieldHalved,
  faEye,
  faEyeSlash,
  faSearch,
  faPlus,
  faCheck,
  faArrowUpRightFromSquare,
  faTimes,
  faTimesCircle,
  faRotate,
  faBars,
  faNetworkWired,
  faArrowLeft,
  faArrowRight,
  faLock
} from '@fortawesome/free-solid-svg-icons'

// ApexCharts 8
import VueApexCharts from 'vue3-apexcharts'

// JorManager Dark Mission Control Design System
import './App.css'

// Register FontAwesome icons
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
  faChevronDown,
  faCompressArrowsAlt,
  faLayerGroup,
  faExclamationTriangle,
  faCheckToSlot,
  faShieldHalved,
  faEye,
  faEyeSlash,
  faSearch,
  faPlus,
  faCheck,
  faArrowUpRightFromSquare,
  faTimes,
  faTimesCircle,
  faRotate,
  faBars,
  faNetworkWired,
  faArrowLeft,
  faArrowRight,
  faLock
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
app.directive('b-tooltip', vBTooltip)
app.directive('b-toggle', vBToggle)

// Mount the app
app.mount('#app')
