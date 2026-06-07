package com.eried.eucplanet.pebble

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * No-op stand-in for the slim `-PpebbleEnabled=false` build.
 *
 * The real listener (in the `pebbleEnabled` source set) extends pebblekit2's
 * `BasePebbleListenerService`, which isn't on the stub classpath. But the
 * service is declared in `src/main/AndroidManifest.xml` (so the manifest merge
 * never has to override main), so the class must exist in BOTH builds. This
 * plain [Service] is never started on a slim build — nothing binds it — it just
 * keeps the manifest entry from pointing at a missing class.
 */
class PebbleListenerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
