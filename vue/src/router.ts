import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
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

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

export default router
