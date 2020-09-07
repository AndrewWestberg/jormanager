import Vue from 'vue'
import Router from 'vue-router'

Vue.use(Router)

export default new Router({
    mode: 'history',
    base: process.env.BASE_URL,
    routes: [{
            path: '/',
            name: 'dashboard',
            component: () => import('./views/Dashboard.vue')
        },
        {
            path: '/hosts',
            name: 'hosts',
            component: () => import('./views/Hosts.vue')
        },
        {
            path: '/nodes',
            name: 'nodes',
            component: () => import('./views/Nodes.vue')
        },
        {
            path: '/blocks',
            name: 'blocks',
            component: () => import('./views/Blocks.vue')
        },
        {
            path: '/wallet',
            name: 'wallet',
            component: () => import('./views/Wallet.vue')
        }
    ]
})