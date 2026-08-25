import { BaseSideService } from '@zeppos/zml/base-side'
import { BASE_URL, PATH_STATE, PATH_CONTROL, K } from '../utils/protocol'

// Runs inside the Zepp phone app. This side owns the polling loop: it fetches
// the frame from EUC Planet over loopback HTTP (the Zepp app permits cleartext)
// at the cadence the phone asks for ("pi") and pushes each frame to the watch
// with a one-way call. The watch only receives, which keeps its small JS heap
// out of the per-request handshake and session churn ZML does on the device
// side; a first version that polled from the watch ran out of memory after
// about 20 minutes.
//
// The watch kicks the loop with a "start" request, pings it every so often
// (the loop stops on its own when the pings stop, so a closed watch app never
// leaves a timer running inside the Zepp app), and sends control intents as
// "control" requests.

const TIMEOUT_MS = 2500
const RETRY_MS = 1000
const KEEPALIVE_TTL_MS = 3 * 60 * 1000

function withTimeout(promise, ms) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('timeout')), ms)
    promise.then(
      (v) => {
        clearTimeout(t)
        resolve(v)
      },
      (e) => {
        clearTimeout(t)
        reject(e)
      },
    )
  })
}

function parseBody(body) {
  if (typeof body === 'string') {
    try {
      return JSON.parse(body)
    } catch (e) {
      return null
    }
  }
  return body && typeof body === 'object' ? body : null
}

function fetchState() {
  return withTimeout(fetch({ url: BASE_URL + PATH_STATE, method: 'GET' }), TIMEOUT_MS).then((r) =>
    r && r.status === 200 ? parseBody(r.body) : null,
  )
}

function postControl(cmd) {
  return withTimeout(
    fetch({
      url: BASE_URL + PATH_CONTROL,
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ cmd }),
    }),
    TIMEOUT_MS,
  ).then((r) => !!r && r.status === 200)
}

AppSideService(
  BaseSideService({
    state: {
      timer: null,
      running: false,
      lastPingAt: 0,
      fails: 0,
      toldWatchUnreachable: false,
    },

    onInit() {},

    startLoop() {
      this.state.lastPingAt = Date.now()
      if (this.state.running) return
      this.state.running = true
      this.tick()
    },

    stopLoop() {
      this.state.running = false
      if (this.state.timer) {
        clearTimeout(this.state.timer)
        this.state.timer = null
      }
    },

    tick() {
      if (!this.state.running) return
      if (Date.now() - this.state.lastPingAt > KEEPALIVE_TTL_MS) {
        this.stopLoop()
        return
      }
      fetchState()
        .then((state) => {
          if (!this.state.running) return null
          if (state) {
            this.state.fails = 0
            this.state.toldWatchUnreachable = false
            this.call({ method: 'frame', params: state })
            const pi = typeof state[K.POLL_MS] === 'number' ? state[K.POLL_MS] : RETRY_MS
            return Math.max(250, Math.min(5000, pi))
          }
          this.noteFail()
          return RETRY_MS
        })
        .catch(() => {
          this.noteFail()
          return RETRY_MS
        })
        .then((wait) => {
          if (wait === null || !this.state.running) return
          this.state.timer = setTimeout(() => this.tick(), wait)
        })
    },

    // The Side Service is alive (it is running this loop) but its fetch to
    // EUC Planet on the phone keeps failing. After a few tries, tell the watch
    // so it can say "phone app not reachable" instead of the generic waiting
    // screen. This is the one signal that separates "Zepp is not relaying to
    // the watch" from "the phone app is not answering on localhost".
    noteFail() {
      this.state.fails++
      if (this.state.fails >= 3 && !this.state.toldWatchUnreachable) {
        this.state.toldWatchUnreachable = true
        this.call({ method: 'status', params: { phoneReachable: false } })
      }
    },

    onRequest(req, res) {
      if (req.method === 'start' || req.method === 'ping') {
        this.startLoop()
        res(null, { ok: true })
        return
      }
      if (req.method === 'stop') {
        this.stopLoop()
        res(null, { ok: true })
        return
      }
      if (req.method === 'state') {
        // One-shot fetch, kept for debugging from the watch side.
        fetchState()
          .then((state) => res(null, state ? { ok: true, state } : { ok: false }))
          .catch((e) => res(null, { ok: false, error: String(e && e.message ? e.message : e) }))
        return
      }
      if (req.method === 'control') {
        const cmd = req.params && req.params.cmd
        if (typeof cmd !== 'string' || !cmd) {
          res(null, { ok: false, error: 'no cmd' })
          return
        }
        postControl(cmd)
          .then((ok) => res(null, { ok }))
          .catch((e) => res(null, { ok: false, error: String(e && e.message ? e.message : e) }))
        return
      }
      res('unknown method: ' + req.method)
    },

    onRun() {},

    onDestroy() {
      this.stopLoop()
    },
  }),
)
