import { createWidget, widget, align, prop, getTextLayout } from '@zos/ui'
import { getDeviceInfo } from '@zos/device'
import { localStorage } from '@zos/storage'
import { convertSpeedFromKmh, speedUnitLabel } from '../utils/protocol'

// Glanceable EUC Planet widget: a card the rider swipes to from the watchface,
// no app launch needed. It reads the last telemetry frame the dial page cached
// to on-watch storage (localStorage, shared across the mini program), so it is
// entirely local, no phone round trip at glance time. When there is no recent
// frame it shows a representative example so the card is never blank.

const COLOR_DIM = 0x9aa0a6
const COLOR_SAFE = 0x2ecc40
const COLOR_WARN = 0xffb400
const COLOR_DANGER = 0xe74c3c
const COLOR_WHITE = 0xffffff
const COLOR_BG = 0x000000

const FRESH_MS = 5 * 60 * 1000

// Shown when the app has not cached a frame yet, so the card always renders.
const DEMO = {
  s: 24, us: 'kmh', b: 85, p: 32, n: 'EUC Planet', c: false,
  ms: 50, wgb: true, wgo: 65, wgr: 85, ts: 0, demo: true,
}

function clamp(v, lo, hi) {
  return v < lo ? lo : v > hi ? hi : v
}

function pwmColor(pct) {
  return pct >= 90 ? COLOR_DANGER : pct >= 70 ? COLOR_WARN : COLOR_SAFE
}

function batteryColor(pct) {
  return pct <= 15 ? COLOR_DANGER : pct <= 30 ? COLOR_WARN : COLOR_SAFE
}

function readLast() {
  try {
    const raw = localStorage.getItem('euc_last', '')
    if (raw) {
      const d = JSON.parse(raw)
      if (d && typeof d.s === 'number') {
        const fresh = typeof d.ts === 'number' && Date.now() - d.ts < FRESH_MS
        return { d, fresh }
      }
    }
  } catch (e) {}
  return { d: DEMO, fresh: false }
}

SecondaryWidget({
  state: { w: {}, layout: null, timer: null },

  onInit() {},

  build() {
    const info = getDeviceInfo()
    const W = info.width
    const H = info.height
    const cx = Math.floor(W / 2)
    this.state.layout = { W, H, cx }
    const w = this.state.w

    createWidget(widget.FILL_RECT, { x: 0, y: 0, w: W, h: H, color: COLOR_BG })

    w.name = createWidget(widget.TEXT, {
      x: Math.floor(W * 0.1), y: Math.floor(H * 0.15), w: Math.floor(W * 0.8), h: 34,
      color: COLOR_DIM, text_size: 24, align_h: align.CENTER_H, align_v: align.CENTER_V, text: '',
    })
    // Big speed ending at centre, small unit just right of centre.
    w.speed = createWidget(widget.TEXT, {
      x: cx - 200, y: Math.floor(H * 0.3), w: 200, h: 120,
      color: COLOR_SAFE, text_size: 108, align_h: align.RIGHT, align_v: align.CENTER_V, text: '0',
    })
    w.unit = createWidget(widget.TEXT, {
      x: cx + 8, y: Math.floor(H * 0.3) + 34, w: 120, h: 40,
      color: COLOR_DIM, text_size: 26, align_h: align.LEFT, align_v: align.CENTER_V, text: '',
    })
    // Bottom stats: battery (left) and PWM (right).
    const statY = Math.floor(H * 0.64)
    const valY = Math.floor(H * 0.71)
    const lx = Math.floor(W * 0.31)
    const rx = Math.floor(W * 0.69)
    const statW = Math.floor(W * 0.26)
    w.battLabel = createWidget(widget.TEXT, {
      x: lx - statW / 2, y: statY, w: statW, h: 26,
      color: COLOR_DIM, text_size: 20, align_h: align.CENTER_H, align_v: align.CENTER_V, text: 'BATTERY',
    })
    w.battVal = createWidget(widget.TEXT, {
      x: lx - statW / 2, y: valY, w: statW, h: 44,
      color: COLOR_SAFE, text_size: 36, align_h: align.CENTER_H, align_v: align.CENTER_V, text: '--',
    })
    w.pwmLabel = createWidget(widget.TEXT, {
      x: rx - statW / 2, y: statY, w: statW, h: 26,
      color: COLOR_DIM, text_size: 20, align_h: align.CENTER_H, align_v: align.CENTER_V, text: 'PWM',
    })
    w.pwmVal = createWidget(widget.TEXT, {
      x: rx - statW / 2, y: valY, w: statW, h: 44,
      color: COLOR_SAFE, text_size: 36, align_h: align.CENTER_H, align_v: align.CENTER_V, text: '--',
    })

    this.refresh()
  },

  refresh() {
    const w = this.state.w
    if (!w.speed) return
    const { d, fresh } = readLast()
    const L = this.state.layout
    const live = !!d.c && (fresh || d.demo)

    w.name.setProperty(prop.TEXT, d.n || 'EUC Planet')

    const maxSafe = d.ms > 0 ? d.ms : 1
    const frac = clamp(d.s / maxSafe, 0, 1)
    const orangeFrac = clamp((d.wgo || 65) / 100, 0.05, 0.95)
    const redFrac = clamp((d.wgr || 85) / 100, orangeFrac + 0.04, 0.95)
    const spdColor = d.wgb && frac >= redFrac ? COLOR_DANGER : d.wgb && frac >= orangeFrac ? COLOR_WARN : COLOR_SAFE
    const spd = String(Math.round(convertSpeedFromKmh(d.s, d.us)))
    // Keep the number ending at centre regardless of its width.
    let tw = 0
    try {
      const r = getTextLayout(spd, { text_size: 108, text_width: 0, wrapped: 0 })
      tw = r && r.width ? r.width : spd.length * 60
    } catch (e) {
      tw = spd.length * 60
    }
    w.speed.setProperty(prop.MORE, { text: spd, color: spdColor })
    w.unit.setProperty(prop.TEXT, speedUnitLabel(d.us))

    w.battVal.setProperty(prop.MORE, { text: Math.round(d.b) + '%', color: batteryColor(d.b) })
    w.pwmVal.setProperty(prop.MORE, { text: Math.round(d.p) + '%', color: pwmColor(Math.round(d.p)) })
  },

  onResume() {
    this.refresh()
    // Light refresh while the card is on screen so a live ride keeps ticking.
    try {
      this.state.timer = setInterval(() => this.refresh(), 2000)
    } catch (e) {}
  },

  onPause() {
    if (this.state.timer) {
      try {
        clearInterval(this.state.timer)
      } catch (e) {}
      this.state.timer = null
    }
  },

  onDestroy() {
    this.onPause()
  },
})
