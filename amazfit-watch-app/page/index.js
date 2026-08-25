import { createWidget, widget, align, text_style, prop, getTextLayout } from '@zos/ui'
import { getDeviceInfo } from '@zos/device'
import { getSystemInfo } from '@zos/settings'
import {
  setPageBrightTime,
  pauseDropWristScreenOff,
  resetDropWristScreenOff,
  setWakeUpRelaunch,
} from '@zos/display'
import { onKey, KEY_SELECT, KEY_UP, KEY_DOWN, KEY_EVENT_CLICK, KEY_EVENT_LONG_PRESS } from '@zos/interaction'
import {
  Vibrator,
  VIBRATOR_SCENE_SHORT_STRONG,
  VIBRATOR_SCENE_DURATION,
  VIBRATOR_SCENE_DURATION_LONG,
  Battery,
} from '@zos/sensor'
import { exit } from '@zos/router'
import { BasePage } from '@zeppos/zml/base-page'
import { onGesture, GESTURE_LEFT, GESTURE_RIGHT } from '@zos/interaction'
import {
  K,
  CONTROL,
  snapshotFrom,
  convertSpeedFromKmh,
  speedUnitLabel,
  convertDistanceFromKm,
  distanceUnitLabel,
  convertTempFromC,
  tempUnitLabel,
} from '../utils/protocol'

// Main dial. Port of garmin-watch-app/source/EucPlanetView.mc + SpeedGauge.mc
// onto Zepp OS widgets: speed gauge, PWM badge, battery row, horn / light
// buttons, nav overlay. Widgets are created once in build() and updated in
// place from applyState().
//
// Frames are pushed by the Side Service (app-side/index.js), which polls the
// phone at the cadence the phone sets ("pi") and sends each frame with a
// one-way call. The watch only kicks that loop ("start"), keeps it alive with
// a "ping" every KEEPALIVE_MS, and re-kicks it when frames stop. Polling from
// the watch itself ran the device out of memory after twenty minutes: every
// ZML request on the device costs a handshake, timers and a session.

const COLOR_TRACK = 0x2a2a2a
const COLOR_DIM = 0x9aa0a6
const COLOR_SAFE = 0x2ecc40
const COLOR_WARN = 0xffb400
const COLOR_DANGER = 0xe74c3c
const COLOR_BG = 0x000000
const COLOR_WHITE = 0xffffff

// The 260 degree arc opens at the bottom: from lower-left (140 degrees, or
// -220 in the same place) clockwise through the top to lower-right (40).
// Zepp OS ARC: 0 degrees is 3 o'clock, angles grow clockwise.
const ARC_START = -220
const ARC_SWEEP = 260

const STALE_MS = 10000
const AUTO_CLOSE_MS = 20000
const KEEPALIVE_MS = 60000
const REKICK_MS = 5000

function clamp(v, lo, hi) {
  return v < lo ? lo : v > hi ? hi : v
}

function pwmColor(pct) {
  return pct >= 90 ? COLOR_DANGER : pct >= 70 ? COLOR_WARN : COLOR_SAFE
}

function batteryColor(pct) {
  return pct <= 15 ? COLOR_DANGER : pct <= 30 ? COLOR_WARN : COLOR_SAFE
}

function textWidth(text, size) {
  try {
    const r = getTextLayout(text, { text_size: size, text_width: 0, wrapped: 0 })
    return r && r.width ? r.width : text.length * size * 0.6
  } catch (e) {
    return text.length * size * 0.6
  }
}

Page(
  BasePage({
    state: {
      s: null,
      phoneSynced: false,
      lastFrameAt: 0,
      pollMs: 1000,
      lastKickAt: 0,
      page: 0,
      destroyed: false,
      keepOnApplied: null,
      layout: null,
      w: {},
      watchBattery: null,
      vibrator: null,
    },

    onInit() {
      try {
        setWakeUpRelaunch({ relaunch: true })
      } catch (e) {}
      try {
        this.state.vibrator = new Vibrator()
      } catch (e) {}
      try {
        this.state.watchBattery = new Battery()
      } catch (e) {}
    },

    build() {
      const info = getDeviceInfo()
      const W = info.width
      const H = info.height
      const dim = Math.min(W, H)
      const cx = Math.floor(W / 2)
      const cy = Math.floor(H / 2)
      const arcThickness = Math.floor((dim * 6) / 100)
      const arcInset = Math.floor((dim * 5) / 100)
      const arcRadius = Math.floor(dim / 2) - arcThickness - arcInset
      const bandThickness = Math.max(2, Math.floor((arcThickness * 25) / 100))
      const bandRadius = arcRadius + Math.floor((arcThickness * 60) / 100) + Math.floor(bandThickness / 2)
      const btnY = Math.floor((H * 86) / 100)
      const btnR = Math.floor((W * 7) / 100)
      const leftX = Math.floor((W * 36) / 100)
      const rightX = Math.floor((W * 64) / 100)
      this.state.layout = {
        W, H, dim, cx, cy, arcThickness, arcRadius, bandThickness, bandRadius,
        btn: { y: btnY, r: btnR, leftX, rightX },
      }
      const L = this.state.layout
      const w = this.state.w

      createWidget(widget.FILL_RECT, { x: 0, y: 0, w: W, h: H, color: COLOR_BG })

      // --- placeholders -------------------------------------------------
      w.waiting = createWidget(widget.TEXT, {
        x: Math.floor(W * 0.1),
        y: cy - 60,
        w: Math.floor(W * 0.8),
        h: 120,
        color: COLOR_WHITE,
        text_size: 34,
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text_style: text_style.WRAP,
        text: 'Open EUC Planet\non your phone',
      })
      w.disconnected = createWidget(widget.TEXT, {
        x: Math.floor(W * 0.1),
        y: cy - 30,
        w: Math.floor(W * 0.8),
        h: 60,
        color: COLOR_WHITE,
        text_size: 34,
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: 'Disconnected',
      })
      w.disconnected.setProperty(prop.VISIBLE, false)

      // --- speed gauge ---------------------------------------------------
      const arcBox = (r) => ({ x: cx - r, y: cy - r, w: r * 2, h: r * 2, radius: r })
      w.track = createWidget(widget.ARC, {
        ...arcBox(arcRadius),
        start_angle: ARC_START,
        end_angle: ARC_START + ARC_SWEEP,
        line_width: arcThickness,
        color: COLOR_TRACK,
      })
      w.bandSafe = createWidget(widget.ARC, {
        ...arcBox(bandRadius),
        start_angle: ARC_START,
        end_angle: ARC_START,
        line_width: bandThickness,
        color: COLOR_SAFE,
      })
      w.bandWarn = createWidget(widget.ARC, {
        ...arcBox(bandRadius),
        start_angle: ARC_START,
        end_angle: ARC_START,
        line_width: bandThickness,
        color: COLOR_WARN,
      })
      w.bandDanger = createWidget(widget.ARC, {
        ...arcBox(bandRadius),
        start_angle: ARC_START,
        end_angle: ARC_START,
        line_width: bandThickness,
        color: COLOR_DANGER,
      })
      w.speedArc = createWidget(widget.ARC, {
        ...arcBox(arcRadius),
        start_angle: ARC_START,
        end_angle: ARC_START + 1,
        line_width: arcThickness,
        color: COLOR_SAFE,
      })
      const dotR = Math.floor((arcThickness * 45) / 100)
      w.gpsDotBg = createWidget(widget.CIRCLE, { center_x: cx, center_y: cy, radius: Math.floor(dotR * 1.45), color: COLOR_BG })
      w.gpsDot = createWidget(widget.CIRCLE, { center_x: cx, center_y: cy, radius: dotR, color: COLOR_WHITE })

      const speedY = Math.floor((H * 33) / 100)
      w.speed = createWidget(widget.TEXT, {
        x: Math.floor(W * 0.1),
        y: speedY - 70,
        w: Math.floor(W * 0.8),
        h: 140,
        color: COLOR_SAFE,
        text_size: 120,
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: '0',
      })
      w.unit = createWidget(widget.TEXT, {
        x: cx + 60,
        y: speedY - 10,
        w: 120,
        h: 40,
        color: COLOR_DIM,
        text_size: 28,
        align_h: align.LEFT,
        align_v: align.CENTER_V,
        text: 'km/h',
      })

      // --- PWM badge (bar + percent) ----------------------------------------
      w.pwmTrack = createWidget(widget.FILL_RECT, { x: cx, y: cy, w: 10, h: 8, radius: 4, color: COLOR_TRACK })
      w.pwmFill = createWidget(widget.FILL_RECT, { x: cx, y: cy, w: 0, h: 8, radius: 4, color: COLOR_SAFE })
      w.pwmText = createWidget(widget.TEXT, {
        x: cx,
        y: cy,
        w: 120,
        h: 40,
        color: COLOR_SAFE,
        text_size: 24,
        align_h: align.LEFT,
        align_v: align.CENTER_V,
        text: '0',
      })

      // --- battery row (up to three icon + percent cells) --------------------
      w.batt = []
      const icons = ['icon_wheel.png', 'icon_phone.png', 'icon_watch.png']
      for (let i = 0; i < 3; i++) {
        w.batt.push({
          icon: createWidget(widget.IMG, { x: cx, y: cy, src: icons[i] }),
          text: createWidget(widget.TEXT, {
            x: cx,
            y: cy,
            w: 90,
            h: 30,
            color: COLOR_SAFE,
            text_size: 24,
            align_h: align.CENTER_H,
            align_v: align.CENTER_V,
            text: '',
          }),
        })
      }

      // --- horn / light buttons ---------------------------------------------
      // A true round button: an OS-drawn CIRCLE for the disc, the icon as an
      // IMG on top, and a transparent BUTTON over both to catch taps (a
      // BUTTON's hit area is its rectangle, independent of its clear image).
      // This stays perfectly round at any size, unlike a circular PNG inside a
      // square BUTTON, whose square press chrome showed through.
      const iconSz = 32
      w.hornCircle = createWidget(widget.CIRCLE, { center_x: leftX, center_y: btnY, radius: btnR, color: 0x29b6f6 })
      w.hornIcon = createWidget(widget.IMG, { x: leftX - iconSz / 2, y: btnY - iconSz / 2, src: 'icon_horn.png' })
      w.horn = createWidget(widget.BUTTON, {
        x: leftX - btnR,
        y: btnY - btnR,
        w: btnR * 2,
        h: btnR * 2,
        normal_src: 'btn_clear.png',
        press_src: 'btn_tap.png',
        click_func: () => this.onScreenButton(1, false),
        longpress_func: () => this.onScreenButton(1, true),
      })
      w.lightCircle = createWidget(widget.CIRCLE, { center_x: rightX, center_y: btnY, radius: btnR, color: 0x444444 })
      w.lightIcon = createWidget(widget.IMG, { x: rightX - iconSz / 2, y: btnY - iconSz / 2, src: 'icon_light.png' })
      w.light = createWidget(widget.BUTTON, {
        x: rightX - btnR,
        y: btnY - btnR,
        w: btnR * 2,
        h: btnR * 2,
        normal_src: 'btn_clear.png',
        press_src: 'btn_tap.png',
        click_func: () => this.onScreenButton(2, false),
        longpress_func: () => this.onScreenButton(2, true),
      })

      // --- navigation overlay ------------------------------------------------
      const navR = Math.floor((W * 22) / 100)
      w.navRing = createWidget(widget.CIRCLE, { center_x: cx, center_y: cy, radius: navR + 2, color: COLOR_WHITE })
      w.navDisc = createWidget(widget.CIRCLE, { center_x: cx, center_y: cy, radius: navR, color: COLOR_BG })
      w.navArrow = createWidget(widget.IMG, {
        x: cx - 24,
        y: cy - 24,
        w: 48,
        h: 48,
        center_x: 24,
        center_y: 24,
        angle: 0,
        src: 'arrow.png',
      })
      w.navArrived = createWidget(widget.TEXT, {
        x: cx - navR,
        y: cy - 20,
        w: navR * 2,
        h: 40,
        color: COLOR_WHITE,
        text_size: 28,
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: 'Arrived',
      })
      w.navDistance = createWidget(widget.TEXT, {
        x: cx - navR,
        y: cy + navR + 4,
        w: navR * 2,
        h: 34,
        color: COLOR_WHITE,
        text_size: 24,
        align_h: align.CENTER_H,
        align_v: align.CENTER_V,
        text: '',
      })

      // --- details page (swipe left from the dial) --------------------------
      // Wear OS parity: a header (wheel name, or "Disconnected"), a headline
      // speed, then seven telemetry rows. Hidden until the rider swipes left;
      // values refresh every frame so it is live the instant it appears.
      w.detHeader = createWidget(widget.TEXT, {
        x: Math.floor(W * 0.08), y: Math.floor(H * 0.075), w: Math.floor(W * 0.84), h: 34,
        color: COLOR_WHITE, text_size: 24, align_h: align.CENTER_H, align_v: align.CENTER_V,
        text: '',
      })
      // Headline speed: a big number ending at centre, small unit just right of
      // centre, mirroring the dial and the Wear OS details header.
      w.detSpeed = createWidget(widget.TEXT, {
        x: cx - 170, y: Math.floor(H * 0.125), w: 170, h: 56,
        color: COLOR_SAFE, text_size: 48, align_h: align.RIGHT, align_v: align.CENTER_V,
        text: '0',
      })
      w.detSpeedUnit = createWidget(widget.TEXT, {
        x: cx + 6, y: Math.floor(H * 0.125) + 14, w: 110, h: 34,
        color: COLOR_DIM, text_size: 24, align_h: align.LEFT, align_v: align.CENTER_V,
        text: '',
      })

      const DETAIL_ROWS = ['Voltage', 'Current', 'Power', 'PWM', 'Temp', 'Torque', 'Trip']
      const rowN = DETAIL_ROWS.length
      const rowTop = Math.floor(H * 0.3)
      const rowStep = Math.floor((H * 0.58) / rowN)
      const rowFont = Math.max(20, Math.floor(W * 0.046))
      const labelX = Math.floor(W * 0.22)
      const valueW = Math.floor(W * 0.3)
      const valueX = Math.floor(W * 0.78) - valueW
      w.detail = []
      for (let i = 0; i < rowN; i++) {
        const ry = rowTop + rowStep * i
        w.detail.push({
          label: createWidget(widget.TEXT, {
            x: labelX, y: ry, w: Math.floor(W * 0.3), h: rowStep,
            color: COLOR_DIM, text_size: rowFont, align_h: align.LEFT, align_v: align.CENTER_V,
            text: DETAIL_ROWS[i],
          }),
          value: createWidget(widget.TEXT, {
            x: valueX, y: ry, w: valueW, h: rowStep,
            color: COLOR_WHITE, text_size: rowFont, align_h: align.RIGHT, align_v: align.CENTER_V,
            text: '--',
          }),
        })
      }
      // Page dots at the bottom, filled = current page.
      const dotY = Math.floor(H * 0.945)
      const pageDotR = Math.max(3, Math.floor(W * 0.013))
      const dotGap = Math.floor(W * 0.045)
      L.dots = { y: dotY, r: pageDotR, gap: dotGap, cx }
      w.dot0 = createWidget(widget.CIRCLE, { center_x: cx - dotGap, center_y: dotY, radius: pageDotR, color: COLOR_WHITE })
      w.dot1 = createWidget(widget.CIRCLE, { center_x: cx + dotGap, center_y: dotY, radius: pageDotR, color: COLOR_TRACK })

      this.dialWidgets = [
        w.track, w.bandSafe, w.bandWarn, w.bandDanger, w.speedArc, w.gpsDotBg, w.gpsDot,
        w.speed, w.unit, w.pwmTrack, w.pwmFill, w.pwmText,
        w.hornCircle, w.hornIcon, w.horn, w.lightCircle, w.lightIcon, w.light,
      ]
      w.batt.forEach((b) => this.dialWidgets.push(b.icon, b.text))
      this.navWidgets = [w.navRing, w.navDisc, w.navArrow, w.navArrived, w.navDistance]
      this.detailWidgets = [w.detHeader, w.detSpeed, w.detSpeedUnit]
      w.detail.forEach((d) => this.detailWidgets.push(d.label, d.value))
      this.dotWidgets = [w.dot0, w.dot1]
      this.showDial(false)
      this.showNav(false)
      this.showDetails(false)
      this.showDots(false)

      // Swipe left goes to the details page, swipe right comes back. A right
      // swipe on the dial is left to the system (leave the app), matching the
      // Wear OS pager where page 0 is the edge.
      onGesture({
        callback: (event) => {
          if (!this.state.phoneSynced) return false
          if (event === GESTURE_LEFT && this.state.page === 0) {
            this.showPage(1)
            return true
          }
          if (event === GESTURE_RIGHT && this.state.page === 1) {
            this.showPage(0)
            return true
          }
          return false
        },
      })

      // Physical keys, same three-button model as Garmin: Select = button 1
      // (click and hold), Up = button 2 (click and hold), Down = button 3
      // (click only, the system owns its long press). Back is left alone so
      // the rider can leave the app.
      onKey({
        callback: (key, keyEvent) => {
          const s = this.state.s
          if (!s) return false
          const name = key === KEY_SELECT ? 'select' : key === KEY_UP ? 'up' : key === KEY_DOWN ? 'down' : String(key)
          const ev = keyEvent === KEY_EVENT_CLICK ? 'click' : keyEvent === KEY_EVENT_LONG_PRESS ? 'hold' : String(keyEvent)
          this.debug('key ' + name + ' ' + ev)
          let act = null
          if (key === KEY_SELECT) act = ev === 'click' ? s.stem1Click : ev === 'hold' ? s.stem1Hold : null
          else if (key === KEY_UP) act = ev === 'click' ? s.stem2Click : ev === 'hold' ? s.stem2Hold : null
          else if (key === KEY_DOWN) act = ev === 'click' ? s.stem3Click : null
          if (act === null) return false
          this.debug('key dispatch act=' + act)
          this.dispatch(act)
          return true
        },
      })

      this.sendWatchInfo()
      this.kick('start')
      this.tickTimer = setInterval(() => this.onTick(), 1000)
    },

    onDestroy() {
      this.state.destroyed = true
      if (this.tickTimer) clearInterval(this.tickTimer)
      try {
        this.request({ method: 'stop', params: {} }).catch(() => {})
      } catch (e) {}
      try {
        resetDropWristScreenOff()
      } catch (e) {}
    },

    // --- phone link ----------------------------------------------------------

    // Frames arrive here from the Side Service loop.
    onCall(data) {
      if (!data) return
      if (data.method === 'frame' && data.params) {
        this.onFrame(data.params)
        return
      }
      // Diagnostic from the Side Service: it is running but cannot reach EUC
      // Planet on the phone. Only meaningful before the first frame; once
      // frames arrive the dial takes over.
      if (data.method === 'status' && data.params && data.params.phoneReachable === false) {
        if (!this.state.phoneSynced) {
          this.state.w.waiting.setProperty(prop.TEXT, "Can't reach EUC Planet.\nOpen it on your phone.")
        }
      }
    },

    // Ask the Side Service to (re)start its loop, or tell it we are still here.
    kick(method) {
      if (this.state.destroyed) return
      this.state.lastKickAt = Date.now()
      this.request({ method, params: {} }).catch(() => {})
    },

    onFrame(raw) {
      const s = snapshotFrom(raw)
      this.state.s = s
      this.state.phoneSynced = true
      this.state.lastFrameAt = Date.now()
      this.state.pollMs = s.pollMs
      this.applyKeepOn(s.keepScreenOn)
      this.applyState(s)
      s.events.forEach((ev) => this.onEvent(ev))
    },

    onEvent(ev) {
      if (!ev || typeof ev !== 'object') return
      if (ev[K.KIND] === K.KIND_QUIT) {
        exit()
        return
      }
      if (ev[K.KIND] === K.KIND_VIBRATE) {
        const ms = typeof ev[K.VIBRATE_MS] === 'number' ? ev[K.VIBRATE_MS] : 300
        this.buzz(ms >= 900 ? VIBRATOR_SCENE_DURATION_LONG : ms >= 200 ? VIBRATOR_SCENE_DURATION : VIBRATOR_SCENE_SHORT_STRONG)
      }
    },

    onTick() {
      const st = this.state
      const now = Date.now()
      // Keepalive for the Side Service loop, and a re-kick when frames stop
      // (the Zepp app may have restarted its side service).
      const sinceKick = now - st.lastKickAt
      const sinceFrame = st.lastFrameAt ? now - st.lastFrameAt : Infinity
      const stalled = sinceFrame > Math.max(REKICK_MS, st.pollMs * 3)
      if (sinceKick > KEEPALIVE_MS || (stalled && sinceKick > REKICK_MS)) {
        this.kick(stalled ? 'start' : 'ping')
      }
      if (!st.phoneSynced) return
      const silent = now - st.lastFrameAt
      const stale = silent > STALE_MS
      st.w.disconnected.setProperty(prop.VISIBLE, stale)
      if (stale) {
        this.showDial(false)
        this.showNav(false)
        this.showDetails(false)
        this.showDots(false)
        st.page = 0
      }
      // Self-close fallback for "Auto-stop on watch", same rule as Garmin.
      if (st.s && st.s.closeOnExit && silent > AUTO_CLOSE_MS) exit()
    },

    sendWatchInfo() {
      let model = 'Amazfit'
      let fw = ''
      let api = ''
      try {
        const d = getDeviceInfo()
        if (d && d.deviceName) model = String(d.deviceName)
      } catch (e) {}
      try {
        const si = getSystemInfo()
        if (si) {
          fw = si.osVersion || si.firmwareVersion || ''
          api = si.minAPI || ''
        }
      } catch (e) {}
      this.sendControl(CONTROL.INFO_PREFIX + 'model=' + model + '|fw=' + fw + '|api=' + api)
    },

    sendControl(cmd) {
      console.log('[eucplanet] control ' + cmd)
      this.request({ method: 'control', params: { cmd } }).catch(() => {})
    },

    // Port of garmin-watch-app/source/Actions.mc.
    dispatch(actionName) {
      if (!actionName || actionName === 'NONE') return
      const s = this.state.s
      let intent
      if (actionName === 'HORN') intent = CONTROL.HORN
      else if (actionName === 'LIGHT_TOGGLE') intent = s && s.lightOn ? CONTROL.LIGHT_OFF : CONTROL.LIGHT_ON
      else intent = CONTROL.ACTION_PREFIX + actionName
      this.sendControl(intent)
      if (s && s.hapticOnAction) this.buzz(VIBRATOR_SCENE_SHORT_STRONG)
    },

    onScreenButton(which, hold) {
      const s = this.state.s
      const act = !s ? 'NONE' : which === 1 ? (hold ? s.screen1Hold : s.screen1Click) : hold ? s.screen2Hold : s.screen2Click
      this.debug('tap slot=' + which + (hold ? ' hold' : ' click') + ' act=' + act)
      if (!s || !s.connected) return
      this.dispatch(act)
    },

    // Input-event report for the phone's Service Mode. Only sent while the
    // phone says it is recording ("dg"), so a normal ride costs nothing extra.
    debug(msg) {
      const s = this.state.s
      if (!s || !s.diag) return
      this.sendControl(CONTROL.DEBUG_PREFIX + msg)
    },

    buzz(mode) {
      const v = this.state.vibrator
      if (!v) return
      try {
        v.setMode(mode)
        v.start()
      } catch (e) {}
    },

    applyKeepOn(on) {
      if (this.state.keepOnApplied === on) return
      this.state.keepOnApplied = on
      try {
        if (on) {
          setPageBrightTime({ brightTime: 2147483000 })
          pauseDropWristScreenOff({ duration: 0 })
        } else {
          setPageBrightTime({ brightTime: 10000 })
          resetDropWristScreenOff()
        }
      } catch (e) {}
    },

    // --- rendering -----------------------------------------------------------

    showDial(visible) {
      this.dialWidgets.forEach((x) => x.setProperty(prop.VISIBLE, visible))
    },

    showNav(visible) {
      this.navWidgets.forEach((x) => x.setProperty(prop.VISIBLE, visible))
    },

    showDetails(visible) {
      this.detailWidgets.forEach((x) => x.setProperty(prop.VISIBLE, visible))
    },

    showDots(visible) {
      this.dotWidgets.forEach((x) => x.setProperty(prop.VISIBLE, visible))
    },

    // Switch between the dial (page 0) and the details list (page 1). Called on
    // swipe and, every frame, from applyState so the active page stays shown
    // while the other stays hidden.
    showPage(p) {
      this.state.page = p
      const onDial = p === 0
      this.showDial(onDial)
      this.showDetails(!onDial)
      if (!onDial) this.showNav(false)
      this.showDots(true)
      const D = this.state.layout.dots
      this.state.w.dot0.setProperty(prop.MORE, {
        center_x: D.cx - D.gap, center_y: D.y, radius: D.r, color: onDial ? COLOR_WHITE : COLOR_TRACK,
      })
      this.state.w.dot1.setProperty(prop.MORE, {
        center_x: D.cx + D.gap, center_y: D.y, radius: D.r, color: onDial ? COLOR_TRACK : COLOR_WHITE,
      })
    },

    applyState(s) {
      const L = this.state.layout
      const w = this.state.w
      w.waiting.setProperty(prop.VISIBLE, false)
      w.disconnected.setProperty(prop.VISIBLE, false)
      this.showDial(true)

      // Gauge fraction and colour (SpeedGauge.mc).
      const maxSafe = s.maxSpeedKmh > 0 ? s.maxSpeedKmh : 1
      const frac = clamp(s.speedKmh / maxSafe, 0, 1)
      const orangeFrac = clamp(s.gaugeOrangeThresholdPct / 100, 0.05, 0.95)
      const redFrac = clamp(s.gaugeRedThresholdPct / 100, orangeFrac + 0.04, 0.95)
      const speedColor =
        s.showGaugeBand && frac >= redFrac ? COLOR_DANGER : s.showGaugeBand && frac >= orangeFrac ? COLOR_WARN : COLOR_SAFE

      const bandVisible = !!s.showGaugeBand
      const orangeAngle = ARC_START + Math.round(ARC_SWEEP * orangeFrac)
      const redAngle = ARC_START + Math.round(ARC_SWEEP * redFrac)
      w.bandSafe.setProperty(prop.MORE, { start_angle: ARC_START, end_angle: orangeAngle })
      w.bandWarn.setProperty(prop.MORE, { start_angle: orangeAngle, end_angle: redAngle })
      w.bandDanger.setProperty(prop.MORE, { start_angle: redAngle, end_angle: ARC_START + ARC_SWEEP })
      w.bandSafe.setProperty(prop.VISIBLE, bandVisible)
      w.bandWarn.setProperty(prop.VISIBLE, bandVisible)
      w.bandDanger.setProperty(prop.VISIBLE, bandVisible)

      if (frac > 0.005) {
        w.speedArc.setProperty(prop.MORE, {
          start_angle: ARC_START,
          end_angle: ARC_START + Math.max(1, Math.round(ARC_SWEEP * frac)),
          color: speedColor,
        })
        w.speedArc.setProperty(prop.VISIBLE, true)
      } else {
        w.speedArc.setProperty(prop.VISIBLE, false)
      }

      // GPS extra-speed dot on the arc; -1 means nothing to show.
      if (s.gpsSpeedKmh >= 0) {
        const gpsFrac = clamp(s.gpsSpeedKmh / maxSafe, 0, 1)
        const a = ((ARC_START + ARC_SWEEP * gpsFrac) * Math.PI) / 180
        const dx = L.cx + Math.round(L.arcRadius * Math.cos(a))
        const dy = L.cy + Math.round(L.arcRadius * Math.sin(a))
        w.gpsDotBg.setProperty(prop.MORE, { center_x: dx, center_y: dy })
        w.gpsDot.setProperty(prop.MORE, { center_x: dx, center_y: dy })
        w.gpsDotBg.setProperty(prop.VISIBLE, true)
        w.gpsDot.setProperty(prop.VISIBLE, true)
      } else {
        w.gpsDotBg.setProperty(prop.VISIBLE, false)
        w.gpsDot.setProperty(prop.VISIBLE, false)
      }

      // Speed numeral + unit suffix. Prioritize PWM shrinks the speed so the
      // PWM block below becomes the focal element (Wear OS and Garmin parity).
      const speedText = String(Math.round(convertSpeedFromKmh(s.speedKmh, s.speedUnit)))
      const speedSize = s.prioritizePwm ? 56 : 120
      const speedY = Math.floor((L.H * 33) / 100)
      w.speed.setProperty(prop.MORE, {
        x: Math.floor(L.W * 0.1),
        y: speedY - 70,
        w: Math.floor(L.W * 0.8),
        h: 140,
        text: speedText,
        text_size: speedSize,
        color: speedColor,
      })
      if (s.showSpeedUnit) {
        const tw = textWidth(speedText, speedSize)
        w.unit.setProperty(prop.MORE, {
          x: L.cx + Math.floor(tw / 2) + 6,
          y: speedY + Math.floor(speedSize * 0.18) - 20,
          w: 120,
          h: 40,
          text: speedUnitLabel(s.speedUnit),
        })
        w.unit.setProperty(prop.VISIBLE, true)
      } else {
        w.unit.setProperty(prop.VISIBLE, false)
      }

      this.applyPwm(s)
      this.applyBatteryRow(s)

      // Buttons: the round disc is a CIRCLE whose colour tracks state (horn
      // blue live / dark when no wheel; light amber when on, grey when off,
      // dark when no wheel). The icon and the transparent tap BUTTON sit on
      // top. Circle updates carry their geometry, the same rule the gauge dot
      // follows.
      const B = L.btn
      const hornColor = s.connected ? 0x29b6f6 : 0x1a1a1a
      w.hornCircle.setProperty(prop.MORE, { center_x: B.leftX, center_y: B.y, radius: B.r, color: hornColor })
      const hornVis = !!s.hasHorn
      w.hornCircle.setProperty(prop.VISIBLE, hornVis)
      w.hornIcon.setProperty(prop.VISIBLE, hornVis)
      w.horn.setProperty(prop.VISIBLE, hornVis)
      const lightColor = !s.connected ? 0x1a1a1a : s.lightOn ? 0xffb400 : 0x444444
      w.lightCircle.setProperty(prop.MORE, { center_x: B.rightX, center_y: B.y, radius: B.r, color: lightColor })
      const lightVis = !!s.hasLight
      w.lightCircle.setProperty(prop.VISIBLE, lightVis)
      w.lightIcon.setProperty(prop.VISIBLE, lightVis)
      w.light.setProperty(prop.VISIBLE, lightVis)

      // Navigation overlay over the gauge while the phone popup is up.
      if (s.navActive) {
        this.showNav(true)
        w.navArrow.setProperty(prop.VISIBLE, !s.navArrived)
        w.navArrived.setProperty(prop.VISIBLE, !!s.navArrived)
        w.navArrow.setProperty(prop.MORE, { angle: Math.round(s.navAngle) })
        w.navDistance.setProperty(prop.TEXT, s.navArrived ? '' : s.navDistance)
      } else {
        this.showNav(false)
      }

      // Keep the details list current, then enforce the active page: the dial
      // above rendered as if on screen, so when the rider has swiped to the
      // details page hide the whole dial and nav and show the list instead.
      this.applyDetails(s)
      const onDial = this.state.page === 0
      if (!onDial) {
        this.showDial(false)
        this.showNav(false)
      }
      this.showDetails(!onDial)
      this.showDots(true)
      const Dt = L.dots
      w.dot0.setProperty(prop.MORE, { center_x: Dt.cx - Dt.gap, center_y: Dt.y, radius: Dt.r, color: onDial ? COLOR_WHITE : COLOR_TRACK })
      w.dot1.setProperty(prop.MORE, { center_x: Dt.cx + Dt.gap, center_y: Dt.y, radius: Dt.r, color: onDial ? COLOR_TRACK : COLOR_WHITE })
    },

    // Refresh the details-page values. Cheap, runs every frame so the list is
    // live the moment the rider swipes to it. Layout mirrors the Wear OS
    // details screen: wheel-name header, headline speed, then the rows.
    applyDetails(s) {
      const w = this.state.w
      const live = s.connected
      const dash = '--'
      const CYAN = 0x26c6da

      if (live) {
        w.detHeader.setProperty(prop.MORE, { text: s.wheelName || 'EUC', color: COLOR_WHITE })
      } else {
        w.detHeader.setProperty(prop.MORE, { text: 'Disconnected', color: COLOR_DIM })
      }

      const maxSafe = s.maxSpeedKmh > 0 ? s.maxSpeedKmh : 1
      const frac = clamp(s.speedKmh / maxSafe, 0, 1)
      const orangeFrac = clamp(s.gaugeOrangeThresholdPct / 100, 0.05, 0.95)
      const redFrac = clamp(s.gaugeRedThresholdPct / 100, orangeFrac + 0.04, 0.95)
      const spdColor = !live
        ? COLOR_DIM
        : s.showGaugeBand && frac >= redFrac
          ? COLOR_DANGER
          : s.showGaugeBand && frac >= orangeFrac
            ? COLOR_WARN
            : COLOR_SAFE
      const spd = Math.round(convertSpeedFromKmh(s.speedKmh, s.speedUnit))
      w.detSpeed.setProperty(prop.MORE, { text: live ? String(spd) : dash, color: spdColor })
      w.detSpeedUnit.setProperty(prop.TEXT, s.showSpeedUnit ? speedUnitLabel(s.speedUnit) : '')

      const powerW = s.voltage * s.current
      const tempDisp = convertTempFromC(s.temperatureC, s.tempUnit)
      const tripDisp = convertDistanceFromKm(s.tripKm, s.distanceUnit)
      const tempColor = s.temperatureC > 60 ? COLOR_DANGER : s.temperatureC > 45 ? COLOR_WARN : CYAN
      const rows = [
        { v: live ? s.voltage.toFixed(1) + ' V' : dash, c: CYAN },
        { v: live ? s.current.toFixed(1) + ' A' : dash, c: CYAN },
        { v: live ? Math.round(powerW) + ' W' : dash, c: CYAN },
        { v: live ? Math.round(s.pwmPercent) + ' %' : dash, c: live ? pwmColor(Math.round(s.pwmPercent)) : CYAN },
        { v: live ? Math.round(tempDisp) + ' ' + tempUnitLabel(s.tempUnit) : dash, c: live ? tempColor : CYAN },
        { v: live ? s.torque.toFixed(1) : dash, c: CYAN },
        { v: live ? tripDisp.toFixed(2) + ' ' + distanceUnitLabel(s.distanceUnit) : dash, c: CYAN },
      ]
      for (let i = 0; i < w.detail.length && i < rows.length; i++) {
        w.detail[i].value.setProperty(prop.MORE, { text: rows[i].v, color: rows[i].c })
      }
    },

    applyPwm(s) {
      const L = this.state.layout
      const w = this.state.w
      const pct = clamp(Math.round(s.pwmPercent), 0, 100)
      const color = pwmColor(pct)
      const showBar = s.pwmDisplay === 'BAR' || s.pwmDisplay === 'BOTH'
      const showNum = s.pwmDisplay === 'NUMBERS' || s.pwmDisplay === 'BOTH'

      if (s.prioritizePwm) {
        // Number centred above a wide bar at the dial's vertical midpoint.
        const barW = Math.floor((L.W * 55) / 100)
        const barH = Math.max(6, Math.floor((L.H * 4) / 100))
        const numH = 40
        const gap = 2
        const totalH = (showNum ? numH : 0) + (showNum && showBar ? gap : 0) + (showBar ? barH : 0)
        let top = Math.floor((L.H * 50) / 100) - Math.floor(totalH / 2)
        if (showNum) {
          w.pwmText.setProperty(prop.MORE, {
            x: L.cx - 60,
            y: top,
            w: 120,
            h: numH,
            text: String(pct),
            text_size: 34,
            color,
            align_h: align.CENTER_H,
          })
          top += numH + gap
        }
        if (showBar) {
          const barX = L.cx - Math.floor(barW / 2)
          w.pwmTrack.setProperty(prop.MORE, { x: barX, y: top, w: barW, h: barH, radius: Math.floor(barH / 2) })
          const fillW = Math.floor((barW * pct) / 100)
          w.pwmFill.setProperty(prop.MORE, { x: barX, y: top, w: Math.max(0, fillW), h: barH, radius: Math.floor(barH / 2), color })
          w.pwmFill.setProperty(prop.VISIBLE, fillW > 0)
        }
      } else {
        // Thin bar with the percent hugging its right side, both at y = 50 %.
        const y = Math.floor((L.H * 50) / 100)
        const barW = Math.floor((L.W * 32) / 100)
        const barH = Math.max(6, Math.floor((L.H * 18) / 1000))
        const pctOffset = Math.floor((L.W * 3) / 100)
        const pctReserve = Math.floor((L.W * 14) / 100)
        const groupW = showBar && showNum ? barW + pctReserve : showBar ? barW : pctReserve
        const groupX = L.cx - Math.floor(groupW / 2)
        if (showBar) {
          w.pwmTrack.setProperty(prop.MORE, { x: groupX, y, w: barW, h: barH, radius: Math.floor(barH / 2) })
          const fillW = Math.floor((barW * pct) / 100)
          w.pwmFill.setProperty(prop.MORE, { x: groupX, y, w: Math.max(0, fillW), h: barH, radius: Math.floor(barH / 2), color })
          w.pwmFill.setProperty(prop.VISIBLE, fillW > 0)
        }
        if (showNum) {
          const textX = showBar ? groupX + barW + pctOffset : L.cx - 60
          w.pwmText.setProperty(prop.MORE, {
            x: textX,
            y: y + Math.floor(barH / 2) - 20,
            w: 120,
            h: 40,
            text: String(pct),
            text_size: 24,
            color,
            align_h: showBar ? align.LEFT : align.CENTER_H,
          })
        }
      }
      w.pwmTrack.setProperty(prop.VISIBLE, showBar)
      if (!showBar) w.pwmFill.setProperty(prop.VISIBLE, false)
      w.pwmText.setProperty(prop.VISIBLE, showNum)
    },

    applyBatteryRow(s) {
      const L = this.state.layout
      const w = this.state.w
      const fields = []
      if (s.showWheelBattery) fields.push({ i: 0, pct: s.batteryPercent })
      if (s.showPhoneBattery) fields.push({ i: 1, pct: s.phoneBatteryPercent })
      if (s.showWatchBattery) fields.push({ i: 2, pct: this.readWatchBattery() })

      w.batt.forEach((b) => {
        b.icon.setProperty(prop.VISIBLE, false)
        b.text.setProperty(prop.VISIBLE, false)
      })
      if (fields.length === 0) return

      const rowW = Math.floor((L.W * 55) / 100)
      const rowLeft = Math.floor(L.W / 2) - Math.floor(rowW / 2)
      const step = Math.floor(rowW / fields.length)
      const iconSize = 32
      const textH = 30
      const blockH = iconSize + 1 + textH
      const blockTop = Math.floor((L.H * 64) / 100) - Math.floor(blockH / 2)

      fields.forEach((f, n) => {
        const cxCell = rowLeft + Math.floor(step / 2) + step * n
        const cell = w.batt[f.i]
        cell.icon.setProperty(prop.MORE, { x: cxCell - Math.floor(iconSize / 2), y: blockTop })
        cell.text.setProperty(prop.MORE, {
          x: cxCell - 45,
          y: blockTop + iconSize + 1,
          w: 90,
          h: textH,
          text: String(Math.round(f.pct)),
          color: batteryColor(f.pct),
        })
        cell.icon.setProperty(prop.VISIBLE, true)
        cell.text.setProperty(prop.VISIBLE, true)
      })
    },

    readWatchBattery() {
      try {
        const b = this.state.watchBattery
        if (b) {
          const v = b.getCurrent()
          if (typeof v === 'number') return v
        }
      } catch (e) {}
      return 0
    },
  }),
)

