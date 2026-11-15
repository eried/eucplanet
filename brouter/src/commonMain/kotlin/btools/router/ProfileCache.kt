/**
 * Container for routig configs
 *
 * @author ab
 */
package btools.router

import btools.expressions.BExpressionContextNode
import btools.expressions.BExpressionContextWay
import btools.expressions.BExpressionMetaData
import btools.util.BFiles
import btools.util.currentTimeMillis
import btools.util.joinPath
import btools.util.parentPath
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

class ProfileCache {

  private var expctxWay: BExpressionContextWay? = null
  private var expctxNode: BExpressionContextNode? = null
  private var lastProfileFile: String? = null
  private var lastProfileTimestamp: Long = 0L
  private var profilesBusy: Boolean = false
  private var lastUseTime: Long = 0L

  companion object {
    // the static synchronized methods of upstream lock on this
    private val lock = SynchronizedObject()

    private var lastLookupFile: String? = null
    private var lastLookupTimestamp: Long = 0L

    private var apc: Array<ProfileCache?> = arrayOfNulls(1)
    // (upstream: debug = Boolean.getBoolean("debugProfileCache"), only for log lines, dropped)

    fun setSize(size: Int) {
      synchronized(lock) {
        apc = arrayOfNulls(size)
      }
    }

    fun parseProfile(rc: RoutingContext): Boolean {
      synchronized(lock) {
        // (upstream: System.getProperty("profileBaseDir"), never set by EUC Planet, so always null)
        val profileDir: String? = parentPath(rc.localFunction!!)
        val profileFile: String = rc.localFunction!!

        rc.profileTimestamp = (BFiles.lastModified(profileFile) + rc.getKeyValueChecksum()) shl 24
        val lookupFile = inDir(profileDir, "lookups.dat")

        // invalidate cache at lookup-table update
        if (!(lookupFile == lastLookupFile && BFiles.lastModified(lookupFile) == lastLookupTimestamp)) {
          apc = arrayOfNulls(apc.size)
          lastLookupFile = lookupFile
          lastLookupTimestamp = BFiles.lastModified(lookupFile)
        }

        var lru: ProfileCache? = null
        var unusedSlot = -1

        // check for re-use
        for (i in 0 until apc.size) {
          val pc = apc[i]

          if (pc != null) {
            if ((!pc.profilesBusy) && profileFile == pc.lastProfileFile) {
              if (rc.profileTimestamp == pc.lastProfileTimestamp) {
                rc.expctxWay = pc.expctxWay
                rc.expctxNode = pc.expctxNode
                rc.readGlobalConfig()
                pc.profilesBusy = true
                return true
              }
              lru = pc // name-match but timestamp-mismatch -> we overide this one
              unusedSlot = -1
              break
            }
            if (lru == null || lru.lastUseTime > pc.lastUseTime) {
              lru = pc
            }
          } else if (unusedSlot < 0) {
            unusedSlot = i
          }
        }

        val meta = BExpressionMetaData()

        val expctxWay = BExpressionContextWay(rc.memoryclass * 512, meta)
        rc.expctxWay = expctxWay
        val expctxNode = BExpressionContextNode(0, meta)
        rc.expctxNode = expctxNode
        expctxNode.setForeignContext(expctxWay)

        meta.readMetaData(inDir(profileDir, "lookups.dat"))

        expctxWay.parseFile(profileFile, "global", rc.keyValues)
        expctxNode.parseFile(profileFile, "global", rc.keyValues)

        rc.readGlobalConfig()

        if (rc.processUnusedTags) {
          expctxWay.setAllTagsUsed()
        }

        if (lru == null || unusedSlot >= 0) {
          lru = ProfileCache()
          if (unusedSlot >= 0) {
            apc[unusedSlot] = lru
          }
        }

        lru.lastProfileTimestamp = rc.profileTimestamp
        lru.lastProfileFile = profileFile
        lru.expctxWay = rc.expctxWay
        lru.expctxNode = rc.expctxNode
        lru.profilesBusy = true
        lru.lastUseTime = currentTimeMillis()
        return false
      }
    }

    fun releaseProfile(rc: RoutingContext) {
      synchronized(lock) {
        for (i in 0 until apc.size) {
          val pc = apc[i]

          if (pc != null) {
            // only the thread that holds the cached instance can release it
            if (rc.expctxWay === pc.expctxWay && rc.expctxNode === pc.expctxNode) {
              pc.profilesBusy = false
              break
            }
          }
        }
        rc.expctxWay = null
        rc.expctxNode = null
      }
    }

    // new File(dir, name) where dir may be null (a profile path without a folder)
    private fun inDir(dir: String?, name: String): String = if (dir == null) name else joinPath(dir, name)
  }

}
