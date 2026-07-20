import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 10000 })

export default {
  beds: () => http.get('/beds'),
  bed: (id: number) => http.get(`/bed/${id}`),
  metric: (bedId: number, minutes = 60) => http.get(`/metric`, { params: { bedId, minutes } }),
  waveform: (bedId: number, limit = 200) => http.get(`/waveform`, { params: { bedId, limit } }),
  alerts: (bedId?: number) => http.get('/alerts', { params: { bedId } }),
  ackAlert: (id: number) => http.post(`/alert/${id}/ack`),
  listRules: () => http.get('/scoring/rule'),
  saveRule: (r: any) => http.post('/scoring/rule', r),
  scoreResult: (patientId: number, ruleCode: string) => http.get('/scoring/result', { params: { patientId, ruleCode } }),
  policies: () => http.get('/alert/policy'),
  hisPatient: (p: any) => http.post('/his/patient', p),
  hisOrder: (o: any) => http.post('/his/order', o),
  bind: (bedId: number, patientId: number) => http.post(`/bed/${bedId}/bind`, null, { params: { patientId } }),
  inject: (protocol: string, sn: string, channel: string, value: number) =>
    http.post('/selfcheck/inject', null, { params: { protocol, sn, channel, value } }),
  devices: () => http.get('/devices'),
  saveDevice: (d: any) => http.post('/device', d),
  online: () => http.get('/push/online'),
  plugins: () => http.get('/plugins'),
  playbackByBed: (bedId: number) => http.get(`/playback/by-bed/${bedId}`),
  playbackItems: (sessionId: number) => http.get(`/playback/${sessionId}/items`),
  playbackManual: (bedId: number, patientId?: number, centerAt?: string) =>
    http.post(`/playback/manual`, null, { params: { bedId, patientId, centerAt } })
}
