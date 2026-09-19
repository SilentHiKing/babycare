package com.zero.components.base.util

import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.util.XPopupUtils

/** 手势导航栏也会缩小旧版可见窗口，只有真实 IME 可见时才收键盘。 */
internal fun BasePopupView.dismissOrHideSystemIme() {
    val window = hostWindow ?: return dismiss()
    val imeVisible = ViewCompat.getRootWindowInsets(this)
        ?.isVisible(WindowInsetsCompat.Type.ime())
        ?: (XPopupUtils.getDecorViewInvisibleHeight(window) > 0)
    if (imeVisible) {
        WindowInsetsControllerCompat(window, this).hide(WindowInsetsCompat.Type.ime())
    } else {
        dismiss()
    }
}

/** 新返回链处理后，框架仍将取消的 UP 送给旧链；不可把同一次返回再交给消费者。 */
internal fun isCanceledBackEvent(keyCode: Int, event: KeyEvent): Boolean =
    keyCode == KeyEvent.KEYCODE_BACK && event.isCanceled

/** 将新系统返回接到 XPopup 的原有处理链，不让底层 Dialog 直接关闭而漏掉业务取消通知。 */
internal fun <T : BasePopupView> T.bindSystemBack(): T = apply {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val bridge = PopupSystemBackBridge(this)
        addOnAttachStateChangeListener(bridge)
        if (isAttachedToWindow) bridge.onViewAttachedToWindow(this)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class PopupSystemBackBridge(private val popup: BasePopupView) : View.OnAttachStateChangeListener {
    private var dispatcher: OnBackInvokedDispatcher? = null
    private val callback = OnBackInvokedCallback {
        if (popup.isAttachedToWindow) {
            // 复用公开入口保留子类拦截、dismissOnBackPressed、业务回调和先收键盘等既有顺序。
            // 这里只调用弹窗自己的处理器，不向系统注入已被 API36 停止分发的按键事件。
            popup.onUnhandledKeyEvent(popup, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
    }

    override fun onViewAttachedToWindow(view: View) {
        dispatcher?.unregisterOnBackInvokedCallback(callback)
        // XPopup 已在附着前创建宿主窗口，直接向窗口注册，避免依赖 View 树返回分发器的查找时机。
        dispatcher = popup.hostWindow?.onBackInvokedDispatcher?.also {
            it.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
    }

    override fun onViewDetachedFromWindow(view: View) {
        // 加载弹窗可以复用；每次离开窗口撤销旧注册，再次附着时注册到新的窗口。
        dispatcher?.unregisterOnBackInvokedCallback(callback)
        dispatcher = null
    }
}
