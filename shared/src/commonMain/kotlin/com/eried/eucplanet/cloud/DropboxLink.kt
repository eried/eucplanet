package com.eried.eucplanet.cloud

/**
 * Platform seam for the Dropbox OAuth redirect. The PKCE/token/REST logic is shared
 * ([DropboxClient]); only the consent round-trip is native:
 *
 *  - **iOS**: Swift (AppDelegate) sets [nativeAuthorize] to run an
 *    `ASWebAuthenticationSession` against the authorize URL with the `db-<key>`
 *    callback scheme, then hands the `?code=…` back here.
 *  - **Android / other**: unset → [authorize] reports cancellation (the Android app
 *    has its own Custom Tab flow).
 */
object DropboxLink {
    /** (authorizeUrl, callbackScheme, onResult(code|null)) -> Unit. */
    var nativeAuthorize: ((String, String, (String?) -> Unit) -> Unit)? = null

    fun authorize(authorizeUrl: String, callbackScheme: String, onResult: (String?) -> Unit) {
        val native = nativeAuthorize
        if (native != null) native(authorizeUrl, callbackScheme, onResult) else onResult(null)
    }
}
