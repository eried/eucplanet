import { createWidget, widget, align, text_style, prop, getTextLayout } from '@zos/ui'
import { getDeviceInfo } from '@zos/device'
import { getSystemInfo } from '@zos/settings'
import {
  setPageBrightTime,
  pauseDropWristScreenOff,
  resetDropWristScreenOff,
  setWakeUpRelaunch,
} from '@zos/display'
import { onKey, KEY_SELECT, KEY_DOWN, KEY_EVENT_CLICK, KEY_EVENT_LONG_PRESS } from '@zos/interaction'
import {
  Vibrator,
  VIBRATOR_SCENE_SHORT_STRONG,
  VIBRATOR_SCENE_DURATION,
  VIBRATOR_SCENE_DURATION_LONG,
  Battery,
} from '@zos/sensor'
import { exit } from '@zos/router'
import { BasePage } from '@zeppos/zml/base-page'
import { K, CONTROL, snapshotFrom, convertSpeedFromKmh, speedUnitLabel } from '../utils/protocol'

// Main dial. Port of garmin-watch-app/source/EucPlanetView.mc + SpeedGauge.mc
// onto Zepp OS widgets: speed gauge, PWM badge, battery row, horn / light
// buttons, nav overlay. Widgets are created once in build() and updated in
// place from applyState(); the phone frame arrives through the Side Service
// (app-side/index.js) on a poll loop whose cadence the phone sets ("pi").

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
      this.state.layout = { W, H, dim, cx, cy, arcThickness, arcRadius, bandThickness, bandRadius }
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
      const btnY = Math.floor((H * 86) / 100)
      const btnR = Math.floor((W * 7) / 100)
      const leftX = Math.floor((W * 36) / 100)
      const rightX = Math.floor((W * 64) / 100)
      const D = btnR * 2
      w.horn = createWidget(widget.BUTTON, {
        x: leftX - btnR,
        y: btnY - btnR,
        w: D,
        h: D,
        normal_src: 'btn_horn_off.png',
        press_src: 'btn_horn_press.png',
        click_func: () => this.onScreenButton(1, false),
        longpress_func: () => this.onScreenButton(1, true),
      })
      w.light = createWidget(widget.BUTTON, {
        x: rightX - btnR,
        y: btnY - btnR,
        w: D,
        h: D,
        normal_src: 'btn_light_off.png',
        press_src: 'btn_light_press.png',
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

      this.dialWidgets = [
        w.track, w.bandSafe, w.bandWarn, w.bandDanger, w.speedArc, w.gpsDotBg, w.gpsDot,
        w.speed, w.unit, w.pwmTrack, w.pwmFill, w.pwmText, w.horn, w.light,
      ]
      w.batt.forEach((b) => this.dialWidgets.push(b.icon, b.text))
      this.navWidgets = [w.navRing, w.navDisc, w.navArrow, w.navArrived, w.navDistance]
      this.showDial(false)
      this.showNav(false)

      // Physical keys: Select = button 1, Down = button 2, click and long press.
      onKey({
        callback: (key, keyEvent) => {
          const s = this.state.s
          if (!s) return false
          if (key === KEY_SELECT) {
            if (keyEvent === KEY_EVENT_CLICK) this.dispatch(s.stem1Click)
            else if (keyEvent === KEY_EVENT_LONG_PRESS) this.dispatch(s.stem1Hold)
            else return false
            return true
          }
          if (key === KEY_DOWN) {
            if (keyEvent === KEY_EVENT_CLICK) this.dispatch(s.stem2Click)
            else if (keyEvent === KEY_EVENT_LONG_PRESS) this.dispatch(s.stem2Hold)
            else return false
            return true
          }
          return false
        },
      })

      this.sendWatchInfo()
      this.poll()
      this.tickTimer = setInterval(() => this.onTick(), 1000)
    },

    onDestroy() {
      this.state.destroyed = true
      if (this.pollTimer) clearTimeout(this.pollTimer)
      if (this.tickTimer) clearInterval(this.tickTimer)
      try {
        resetDropWristScreenOff()
      } catch (e) {}
    },

    // --- phone link ----------------------------------------------------------

    poll() {
      if (this.state.destroyed) return
      this.request({ method: 'state', params: {} })
        .then((r) => {
          if (r && r.ok && r.state) this.onFrame(r.state)
        })
        .catch(() => {})
        .then(() => {
          if (this.state.destroyed) return
          this.pollTimer = setTimeout(() => this.poll(), this.state.pollMs)
        })
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
      if (!st.phoneSynced) return
      const silent = Date.now() - st.lastFrameAt
      const stale = silent > STALE_MS
      st.w.disconnected.setProperty(prop.VISIBLE, stale)
      if (stale) {
        this.showDial(false)
        this.showNav(false)
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
      if (!s || !s.connected) return
      if (which === 1) this.dispatch(hold ? s.screen1Hold : s.screen1Click)
      else this.dispatch(hold ? s.screen2Hold : s.screen2Click)
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

      // Buttons: greyed when no wheel is connected, light turns amber when on.
      w.horn.setProperty(prop.MORE, {
        normal_src: s.connected ? 'btn_horn.png' : 'btn_horn_off.png',
      })
      w.horn.setProperty(prop.VISIBLE, !!s.hasHorn)
      w.light.setProperty(prop.MORE, {
        normal_src: !s.connected ? 'btn_light_off.png' : s.lightOn ? 'btn_light_on.png' : 'btn_light.png',
      })
      w.light.setProperty(prop.VISIBLE, !!s.hasLight)

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
