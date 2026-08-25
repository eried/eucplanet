import { BaseSideService } from '@zeppos/zml/base-side'
import { BASE_URL, PATH_STATE, PATH_CONTROL } from '../utils/protocol'

// Runs inside the Zepp phone app. The dial page on the watch asks for a
// frame ("state") or sends a control intent ("control"); both are relayed to
// EUC Planet over loopback HTTP, which the Zepp app permits (its network
// security config allows cleartext). Every failure answers {ok:false}
// instead of erroring so the watch can show its "phone not here" placeholder
// and keep polling.

const TIMEOUT_MS = 2500

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

AppSideService(
  BaseSideService({
    onInit() {},

    onRequest(req, res) {
      if (req.method === 'state') {
        withTimeout(fetch({ url: BASE_URL + PATH_STATE, method: 'GET' }), TIMEOUT_MS)
          .then((r) => {
            const state = r && r.status === 200 ? parseBody(r.body) : null
            res(null, state ? { ok: true, state } : { ok: false, status: r ? r.status : 0 })
          })
          .catch((e) => res(null, { ok: false, error: String(e && e.message ? e.message : e) }))
        return
      }
      if (req.method === 'control') {
        const cmd = req.params && req.params.cmd
        if (typeof cmd !== 'string' || !cmd) {
          res(null, { ok: false, error: 'no cmd' })
          return
        }
        withTimeout(
          fetch({
            url: BASE_URL + PATH_CONTROL,
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ cmd }),
          }),
          TIMEOUT_MS,
        )
          .then((r) => res(null, { ok: !!r && r.status === 200 }))
          .catch((e) => res(null, { ok: false, error: String(e && e.message ? e.message : e) }))
        return
      }
      res('unknown method: ' + req.method)
    },

    onRun() {},
    onDestroy() {},
  }),
)
