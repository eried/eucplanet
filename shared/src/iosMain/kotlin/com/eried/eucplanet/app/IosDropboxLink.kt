package com.eried.eucplanet.app

import com.eried.eucplanet.data.repository.DropboxRepository
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Dropbox's consent page in Apple's sign-in sheet, the iOS side of
 * startLinkFlow + handleAuthCallback. The sheet catches the
 * `db-<APPKEY>://1/connect?code=…` redirect itself, so no URL scheme has to
 * be registered. The rider signs in there; the app never sees the password.
 */
object IosDropboxLink {
    // Held while the sheet is up: the session is released otherwise.
    private var session: ASWebAuthenticationSession? = null

    private val anchor = object : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(
            session: ASWebAuthenticationSession,
        ): ASPresentationAnchor = keyWindow() ?: UIWindow()
    }

    /** True when Dropbox handed back a code and the tokens were saved. */
    suspend fun link(repo: DropboxRepository): Boolean {
        val url = NSURL.URLWithString(repo.beginLink()) ?: return false
        val code = suspendCoroutine<String?> { cont ->
            val s = ASWebAuthenticationSession(
                uRL = url,
                callbackURLScheme = "db-${DropboxRepository.APP_KEY}",
            ) { callback, _ ->
                session = null
                cont.resume(callback?.let { codeOf(it) })
            }
            s.presentationContextProvider = anchor
            session = s
            if (!s.start()) {
                session = null
                cont.resume(null)
            }
        }
        return repo.handleAuthCode(code)
    }

    private fun codeOf(url: NSURL): String? {
        val items = NSURLComponents(uRL = url, resolvingAgainstBaseURL = false).queryItems ?: return null
        return items.filterIsInstance<NSURLQueryItem>().firstOrNull { it.name == "code" }?.value
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun keyWindow(): UIWindow? =
        UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .flatMap { it.windows.filterIsInstance<UIWindow>() }
            .firstOrNull { it.isKeyWindow() }
}
