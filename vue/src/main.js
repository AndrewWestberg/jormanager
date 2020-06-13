import Vue from 'vue'
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

import App from './App.vue'
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