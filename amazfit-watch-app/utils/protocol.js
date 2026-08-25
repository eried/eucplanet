// Wire vocabulary shared with the phone. Mirrors
// app/src/main/java/com/eried/eucplanet/amazfit/AmazfitProtocol.kt 1:1;
// keep both files in lockstep when adding fields.

// Loopback port EUC Planet listens on. Same constant as AMAZFIT_PORT on the
// phone; change both or neither.
export const PORT = 28193
export const BASE_URL = 'http://127.0.0.1:' + PORT
export const PATH_STATE = '/state'
export const PATH_CONTROL = '/control'

export const K = {
  KIND: 'k',
  KIND_STATE: 'state',
  KIND_QUIT: 'quit',
  KIND_VIBRATE: 'vibe',
  VIBRATE_MS: 'ms',
  POLL_MS: 'pi',
  EVENTS: 'ev',

  CONNECTED: 'c',
  SPEED: 's',
  BATTERY: 'b',
  PHONE_BATT: 'b2',
  VOLTAGE: 'v',
  CURRENT: 'i',
  PWM: 'p',
  TEMP: 't',
  TRIP_KM: 'tr',
  TORQUE: 'tq',
  LIGHT_ON: 'l',
  MAX_SPEED: 'ms',
  WHEEL_NAME: 'n',
  HAS_HORN: 'ch',
  HAS_LIGHT: 'cl',
  IMPERIAL: 'im',
  UNIT_SPEED: 'us',
  UNIT_DISTANCE: 'ud',
  UNIT_TEMP: 'ut',
  ACCENT: 'ac',
  TIMESTAMP: 'ts',

  GPS_SPEED: 'gs',
  GPS_SOURCE: 'gsr',

  OPT_KEEP_ON: 'wko',
  OPT_SHOW_WHEEL_BATT: 'wsb',
  OPT_SHOW_PHONE_BATT: 'wpb',
  OPT_SHOW_WATCH_BATT: 'wwb',
  OPT_PWM_DISPLAY: 'wpd',
  OPT_SHOW_SPEED_UNIT: 'wsu',
  OPT_PRIORITIZE_PWM: 'wpp',
  OPT_DIAL_ROTATION: 'wrot',
  OPT_GAUGE_BAND: 'wgb',
  OPT_GAUGE_ORANGE: 'wgo',
  OPT_GAUGE_RED: 'wgr',
  OPT_CLOSE_ON_EXIT: 'wce',

  STEM1_CLICK: 's1c',
  STEM1_HOLD: 's1h',
  STEM2_CLICK: 's2c',
  STEM2_HOLD: 's2h',
  SCREEN1_CLICK: 'b1c',
  SCREEN1_HOLD: 'b1h',
  SCREEN2_CLICK: 'b2c',
  SCREEN2_HOLD: 'b2h',
  HAPTIC_ON_ACTION: 'hap',

  NAV_ACTIVE: 'na',
  NAV_ANGLE: 'ng',
  NAV_PRIMARY: 'np',
  NAV_DISTANCE: 'nd',
  NAV_ARRIVED: 'nar',
}

// Control intents, watch -> phone, as POST /control {"cmd": <intent>}.
export const CONTROL = {
  HORN: 'horn',
  LIGHT_ON: 'light_on',
  LIGHT_OFF: 'light_off',
  ACTION_PREFIX: 'action:',
  INFO_PREFIX: 'info:',
}

// Defaults mirror garmin-watch-app/source/WatchState.mc so a partial frame
// never leaves a field undefined.
export function snapshotFrom(d) {
  const b = (k, f) => (d[k] === undefined || d[k] === null ? f : !!d[k])
  const n = (k, f) => (typeof d[k] === 'number' && isFinite(d[k]) ? d[k] : f)
  const s = (k, f) => (typeof d[k] === 'string' ? d[k] : f)
  return {
    connected: b(K.CONNECTED, false),
    wheelName: s(K.WHEEL_NAME, ''),
    speedKmh: n(K.SPEED, 0),
    batteryPercent: n(K.BATTERY, 0),
    phoneBatteryPercent: n(K.PHONE_BATT, 0),
    voltage: n(K.VOLTAGE, 0),
    current: n(K.CURRENT, 0),
    pwmPercent: n(K.PWM, 0),
    temperatureC: n(K.TEMP, 0),
    tripKm: n(K.TRIP_KM, 0),
    torque: n(K.TORQUE, 0),
    lightOn: b(K.LIGHT_ON, false),
    maxSpeedKmh: n(K.MAX_SPEED, 30),
    hasHorn: b(K.HAS_HORN, true),
    hasLight: b(K.HAS_LIGHT, true),
    speedUnit: s(K.UNIT_SPEED, 'kmh'),
    distanceUnit: s(K.UNIT_DISTANCE, 'km'),
    tempUnit: s(K.UNIT_TEMP, 'C'),
    keepScreenOn: b(K.OPT_KEEP_ON, true),
    showWheelBattery: b(K.OPT_SHOW_WHEEL_BATT, true),
    showPhoneBattery: b(K.OPT_SHOW_PHONE_BATT, true),
    showWatchBattery: b(K.OPT_SHOW_WATCH_BATT, true),
    pwmDisplay: s(K.OPT_PWM_DISPLAY, 'BOTH'),
    showSpeedUnit: b(K.OPT_SHOW_SPEED_UNIT, true),
    prioritizePwm: b(K.OPT_PRIORITIZE_PWM, false),
    showGaugeBand: b(K.OPT_GAUGE_BAND, false),
    gaugeOrangeThresholdPct: n(K.OPT_GAUGE_ORANGE, 65),
    gaugeRedThresholdPct: n(K.OPT_GAUGE_RED, 85),
    closeOnExit: b(K.OPT_CLOSE_ON_EXIT, false),
    stem1Click: s(K.STEM1_CLICK, 'NONE'),
    stem1Hold: s(K.STEM1_HOLD, 'NONE'),
    stem2Click: s(K.STEM2_CLICK, 'NONE'),
    stem2Hold: s(K.STEM2_HOLD, 'NONE'),
    screen1Click: s(K.SCREEN1_CLICK, 'HORN'),
    screen1Hold: s(K.SCREEN1_HOLD, 'NONE'),
    screen2Click: s(K.SCREEN2_CLICK, 'LIGHT_TOGGLE'),
    screen2Hold: s(K.SCREEN2_HOLD, 'NONE'),
    hapticOnAction: b(K.HAPTIC_ON_ACTION, false),
    gpsSpeedKmh: n(K.GPS_SPEED, -1),
    gpsSource: s(K.GPS_SOURCE, ''),
    navActive: b(K.NAV_ACTIVE, false),
    navAngle: n(K.NAV_ANGLE, 0),
    navPrimary: s(K.NAV_PRIMARY, ''),
    navDistance: s(K.NAV_DISTANCE, ''),
    navArrived: b(K.NAV_ARRIVED, false),
    pollMs: Math.max(250, Math.min(5000, n(K.POLL_MS, 1000))),
    events: Array.isArray(d[K.EVENTS]) ? d[K.EVENTS] : [],
  }
}

// Port of garmin-watch-app/source/Units.mc.
export function convertSpeedFromKmh(kmh, unit) {
  switch (unit) {
    case 'mph':
      return kmh * 0.621371
    case 'ms':
      return kmh / 3.6
    case 'kn':
      return kmh * 0.539957
    default:
      return kmh
  }
}

export function speedUnitLabel(unit) {
  switch (unit) {
    case 'mph':
      return 'mph'
    case 'ms':
      return 'm/s'
    case 'kn':
      return 'kn'
    default:
      return 'km/h'
  }
}
