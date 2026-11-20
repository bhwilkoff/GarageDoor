package org.humanshaped.garageagent

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process

/**
 * Runs as the shell user (app_process), never inside the app. Creates a
 * hidden, always-unlocked display with its own focus, and keeps myQ
 * running on it so the agent can read and operate myQ without ever
 * touching the phone's real screen.
 *
 *   CLASSPATH=<agent apk> app_process / org.humanshaped.garageagent.DisplayHost
 */
object DisplayHost {
    const val DISPLAY_NAME = "garage-myq"
    private const val SHELL = "com.android.shell"

    private const val FLAG_PUBLIC = 1 shl 0
    private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    private const val FLAG_OWN_FOCUS = 1 shl 14
    private const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15
    private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL
        override fun getOpPackageName() = SHELL
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.SHELL_UID).setPackageName(SHELL).build()
        override fun getApplicationContext(): Context = this
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val ctor = at.getDeclaredConstructor().apply { isAccessible = true }
        val thread = ctor.newInstance()
        at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
        return at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(thread) as Context
    }

    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        val ctx = ShellContext(systemContext())
        val dmCtor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val dm = dmCtor.newInstance(ctx)

        val width = 1080; val height = 2400; val dpi = 420
        val drain = HandlerThread("drain").apply { start() }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, Handler(drain.looper))

        val flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or FLAG_DESTROY_CONTENT_ON_REMOVAL or
            FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED or
            FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP or FLAG_STEAL_TOP_FOCUS_DISABLED
        val vd: VirtualDisplay = dm.createVirtualDisplay(DISPLAY_NAME, width, height, dpi, reader.surface, flags)
        val id = vd.display.displayId
        println("DISPLAY $id")
        System.out.flush()

        val launch = {
            Runtime.getRuntime().exec(arrayOf(
                "am", "start", "--display", id.toString(), "-n",
                "com.chamberlain.android.liftmaster.myq/com.chamberlain.myq.features.splashscreen.SplashActivity",
            )).waitFor()
        }
        launch()
        Looper.loop()
    }
}
