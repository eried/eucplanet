package com.eried.eucplanet.app

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIStatusBarStyle
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.UIKit.addChildViewController
import platform.UIKit.didMoveToParentViewController

/**
 * The window's root: holds the Compose controller and answers the status bar
 * style, so the clock and battery read dark on a light theme and light on a
 * dark one, whatever the iPhone's own appearance is.
 */
@OptIn(ExperimentalForeignApi::class)
internal class StatusBarHost : UIViewController(nibName = null, bundle = null) {
    /** The controller shown full size inside; set before the window shows it. */
    lateinit var child: UIViewController

    /** Light text, for a dark theme. The app starts dark, as its launch screen. */
    var lightContent: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            setNeedsStatusBarAppearanceUpdate()
        }

    override fun viewDidLoad() {
        super.viewDidLoad()
        addChildViewController(child)
        child.view.setFrame(view.bounds)
        child.view.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        view.addSubview(child.view)
        child.didMoveToParentViewController(this)
    }

    override fun preferredStatusBarStyle(): UIStatusBarStyle =
        if (lightContent) UIStatusBarStyleLightContent else UIStatusBarStyleDarkContent
}
