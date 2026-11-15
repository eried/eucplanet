package com.eried.eucplanet.app

import btools.util.IOException
import com.eried.eucplanet.nav.offline.TileHttp
import com.eried.eucplanet.nav.offline.TileReply
import com.eried.eucplanet.platform.toByteArray
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSData
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLocale
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.addValue
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeZoneForSecondsFromGMT
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.NSObject
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/**
 * A tile GET on the iPhone: an NSURLSession data task whose bytes go to the
 * shared download as they arrive, so a 300 MB map never sits in memory.
 * Its own session (not cached, 30 s without data is a failure, as Android's
 * read timeout), one per request so the delegate is let go after.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosTileHttp : TileHttp {
    override fun get(
        url: String,
        headers: List<Pair<String, String>>,
        onHead: (TileReply) -> Boolean,
        onBody: (buf: ByteArray, len: Int) -> Boolean,
    ) {
        if (IosNetwork.forcedOffline) throw IOException("offline (EUC_NO_NETWORK)")
        val target = NSURL.URLWithString(url) ?: throw IOException("bad link $url")
        val req = NSMutableURLRequest.requestWithURL(target, NSURLRequestReloadIgnoringLocalCacheData, 30.0)
        headers.forEach { (k, v) -> req.addValue(v, forHTTPHeaderField = k) }
        val config = NSURLSessionConfiguration.ephemeralSessionConfiguration
        config.timeoutIntervalForRequest = 30.0
        config.timeoutIntervalForResource = 7.0 * 86_400
        config.URLCache = null
        val delegate = StreamDelegate(onHead, onBody)
        val queue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 }
        val session = NSURLSession.sessionWithConfiguration(config, delegate, queue)
        session.dataTaskWithRequest(req).resume()
        dispatch_semaphore_wait(delegate.done, DISPATCH_TIME_FOREVER)
        // The session keeps its delegate until it is let go.
        session.finishTasksAndInvalidate()
        delegate.thrown?.let { throw it }
        if (!delegate.stoppedByUs) delegate.failure?.let { throw IOException(it) }
        if (delegate.reply == null) throw IOException(delegate.failure ?: "no reply")
    }

    /** Last-Modified as HttpURLConnection's getHeaderFieldDate reads it, 0 without one. */
    fun httpDateMs(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val f = NSDateFormatter().apply {
            locale = NSLocale(localeIdentifier = "en_US_POSIX")
            timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
            dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        }
        return f.dateFromString(value.trim())?.let { (it.timeIntervalSince1970 * 1000).toLong() } ?: 0L
    }
}

/** Hands the reply's head and body to the shared download, from the session's queue. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class StreamDelegate(
    private val onHead: (TileReply) -> Boolean,
    private val onBody: (ByteArray, Int) -> Boolean,
) : NSObject(), NSURLSessionDataDelegateProtocol {
    val done = dispatch_semaphore_create(0)!!
    var reply: TileReply? = null
    var failure: String? = null
    var thrown: Throwable? = null
    var stoppedByUs = false

    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit,
    ) {
        val http = didReceiveResponse as? NSHTTPURLResponse
        if (http == null) {
            failure = "not HTTP"
            stoppedByUs = false
            completionHandler(NSURLSessionResponseCancel)
            return
        }
        val r = TileReply(
            code = http.statusCode.toInt(),
            contentLength = http.expectedContentLength,
            lastModifiedMs = IosTileHttp.httpDateMs(http.valueForHTTPHeaderField("Last-Modified")),
            headers = { name -> http.valueForHTTPHeaderField(name) },
        )
        reply = r
        val go = try {
            onHead(r)
        } catch (t: Throwable) {
            thrown = t
            false
        }
        if (!go) stoppedByUs = true
        completionHandler(if (go) NSURLSessionResponseAllow else NSURLSessionResponseCancel)
    }

    @ObjCSignatureOverride
    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        if (stoppedByUs) return
        val bytes = didReceiveData.toByteArray()
        val go = try {
            onBody(bytes, bytes.size)
        } catch (t: Throwable) {
            thrown = t
            false
        }
        if (!go) {
            stoppedByUs = true
            dataTask.cancel()
        }
    }

    @ObjCSignatureOverride
    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        if (didCompleteWithError != null && failure == null) failure = didCompleteWithError.localizedDescription
        dispatch_semaphore_signal(done)
    }
}
