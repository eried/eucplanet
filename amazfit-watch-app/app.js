import { BaseApp } from '@zeppos/zml/base-app'

// Zepp OS mini program entry. ZML's BaseApp wires the BLE messaging channel
// the dial page uses to reach the Side Service (app-side/index.js), which
// in turn reaches EUC Planet over loopback HTTP on the phone.
App(
  BaseApp({
    globalData: {},
    onCreate() {},
    onDestroy() {},
  }),
)
