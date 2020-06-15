import Vue from 'vue'
import Router from 'vue-router'
import Dashboard from './views/Dashboard.vue'

Vue.use(Router)

export default new Router({
    mode: 'history',
    base: process.env.BASE_URL,
    routes: [{
            path: '/',
            name: 'dashboard',
            component: Dashboard
        },
        {
            path: '/hosts',
            name: 'hosts',
            component: () => import('./views/Hosts.vue') // lazy load
        },
        {
            path: '/nodes',
            name: 'nodes',
            component: () => import('./views/Nodes.vue')
        },
        {
            path: '/wallet',
            name: 'wallet',
            component: () => import('./views/Wallet.vue')
        }
    ]
})