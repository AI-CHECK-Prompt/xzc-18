import { createRouter, createWebHashHistory } from 'vue-router'
import Overview from './views/Overview.vue'
import BedDetail from './views/BedDetail.vue'
import Playback from './views/Playback.vue'
import Rules from './views/Rules.vue'
import Alliance from './views/Alliance.vue'
import SimilarCase from './views/SimilarCase.vue'
import JointQc from './views/JointQc.vue'
import WhatIf from './views/WhatIf.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: Overview, name: 'overview' },
    { path: '/bed/:id', component: BedDetail, name: 'bed' },
    { path: '/playback', component: Playback, name: 'playback' },
    { path: '/rules', component: Rules, name: 'rules' },
    { path: '/alliance', component: Alliance, name: 'alliance' },
    { path: '/similar', component: SimilarCase, name: 'similar' },
    { path: '/qc', component: JointQc, name: 'qc' },
    { path: '/whatif', component: WhatIf, name: 'whatif' }
  ]
})

export default router
