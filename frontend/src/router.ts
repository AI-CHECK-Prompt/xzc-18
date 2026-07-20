import { createRouter, createWebHashHistory } from 'vue-router'
import Overview from './views/Overview.vue'
import BedDetail from './views/BedDetail.vue'
import Playback from './views/Playback.vue'
import Rules from './views/Rules.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: Overview, name: 'overview' },
    { path: '/bed/:id', component: BedDetail, name: 'bed' },
    { path: '/playback', component: Playback, name: 'playback' },
    { path: '/rules', component: Rules, name: 'rules' }
  ]
})

export default router
