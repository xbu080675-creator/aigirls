package com.aigirl.floatball

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.ScaleAnimation
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 悬浮球前台服务
 *
 * 特性：
 *  - TYPE_APPLICATION_OVERLAY 系统级悬浮窗
 *  - 拖拽移动 + 松手自动吸附到屏幕边缘（可关闭）
 *  - 单/双击区分，单击触发 action、双击触发弹跳动画 & 爱心
 *  - 角色呼吸 / 眨眼动画
 *  - 气泡问候语（带尾巴自动朝向）
 */
class FloatBallService : Service() {

    companion object {
        const val ACTION_SHOW = "com.aigirl.floatball.ACTION_SHOW"
        const val ACTION_HIDE = "com.aigirl.floatball.ACTION_HIDE"
        const val ACTION_REFRESH = "com.aigirl.floatball.ACTION_REFRESH"
        private const val NOTIF_ID = 10086
        private const val CLICK_TIMEOUT_MS = 250L
        private const val LONG_PRESS_MS = 600L
        private const val BUBBLE_DURATION_MS = 3500L
        private const val EDGE_ANIM_MS = 280L
        private const val ELASTIC_MARGIN_PX = 2
    }

    private lateinit var wm: WindowManager
    private var ballView: View? = null
    private var bubbleView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val screenSize = Point()

    // 触摸 & 拖拽
    private var isDragging = false
    private var downX = 0f
    private var downY = 0f
    private var startParamsX = 0
    private var startParamsY = 0
    private var lastActionDownMs = 0L
    private var pendingClickRunnable: Runnable? = null

    // 动画
    private var breatheRunnable: Runnable? = null
    private var blinkRunnable: Runnable? = null
    private var bubbleHideRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null
    private var movedBeyondTap = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val ctx = applicationContext
        val metrics = ctx.resources.displayMetrics
        screenSize.x = metrics.widthPixels
        screenSize.y = metrics.heightPixels
        val real = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(real)
        if (real.widthPixels > screenSize.x) screenSize.x = real.widthPixels
        if (real.heightPixels > screenSize.y) screenSize.y = real.heightPixels
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                startForeground(NOTIF_ID, buildNotification())
                showFloatBall(forceRefresh = false)
                if (Prefs.showHelloOnStart) {
                    handler.postDelayed({ showHelloBubble() }, 700L)
                }
            }
            ACTION_HIDE -> {
                hideFloatBall()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_REFRESH -> {
                showFloatBall(forceRefresh = true)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hideFloatBall()
        super.onDestroy()
    }

    // ---------------- 悬浮球显示 / 隐藏 ----------------

    private fun showFloatBall(forceRefresh: Boolean) {
        if (ballView != null) {
            if (forceRefresh) {
                removeBallViewSafe()
            } else {
                refreshAppearance()
                return
            }
        }
        createAndAddBallView()
    }

    private fun hideFloatBall() {
        cancelAllAnimations()
        removeBallViewSafe()
        removeBubbleSafe()
    }

    private fun removeBallViewSafe() {
        val v = ballView ?: return
        ballView = null
        try {
            wm.removeView(v)
        } catch (_: Throwable) {}
    }

    private fun removeBubbleSafe() {
        val v = bubbleView ?: return
        bubbleView = null
        try {
            wm.removeView(v)
        } catch (_: Throwable) {}
    }

    // ---------------- 创建悬浮球 ----------------

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createAndAddBallView() {
        val inflater = LayoutInflater.from(this)
        val ball = inflater.inflate(R.layout.float_ball_layout, null, false)
        val iv = ball.findViewById<ImageView>(R.id.ivCharacter)
        val def = CharacterStore.find(Prefs.characterId)
        iv.setImageResource(def.drawableRes)

        val size = Prefs.sizePx
        ball.layoutParams?.width = size
        ball.layoutParams?.height = size

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            size, size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.LEFT

        // 恢复上次位置
        if (Prefs.lastX in 0..screenSize.x && Prefs.lastY in 0..screenSize.y) {
            params.x = Prefs.lastX
            params.y = Prefs.lastY
        } else {
            params.x = screenSize.x - size - 24
            params.y = screenSize.y / 3
        }

        ball.alpha = Prefs.opacity / 100f
        ballParams = params
        ballView = ball

        ball.setOnTouchListener { _, event -> handleTouch(event) }
        ball.setOnClickListener(null) // touch 处理里自己判断

        try {
            wm.addView(ball, params)
        } catch (t: Throwable) {
            return
        }

        // 进入动画：缩放 + 淡入
        startEnterAnimation(ball)
        startBreatheAnimation(ball.findViewById(R.id.flBallContainer))
        startBlinkRoutine()
    }

    private fun refreshAppearance() {
        val ball = ballView ?: return
        val size = Prefs.sizePx
        ball.findViewById<ImageView>(R.id.ivCharacter)
            .setImageResource(CharacterStore.find(Prefs.characterId).drawableRes)
        ball.alpha = Prefs.opacity / 100f
        ballParams?.let { p ->
            val changed = p.width != size || p.height != size
            p.width = size
            p.height = size
            if (changed) safeUpdate(ball, p)
        }
    }

    private fun safeUpdate(v: View, p: WindowManager.LayoutParams) {
        try {
            wm.updateViewLayout(v, p)
        } catch (_: Throwable) {}
    }

    // ---------------- 触摸 & 拖拽 ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun handleTouch(e: MotionEvent): Boolean {
        val ball = ballView ?: return false
        val p = ballParams ?: return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                movedBeyondTap = false
                downX = e.rawX
                downY = e.rawY
                startParamsX = p.x
                startParamsY = p.y

                // 放大触感
                val c = ball.findViewById<View>(R.id.flBallContainer)
                c.animate().cancel()
                c.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120L)
                    .setInterpolator(OvershootInterpolator()).start()

                // 双击判定
                val now = System.currentTimeMillis()
                val doubleTap = now - lastActionDownMs < 350L
                lastActionDownMs = now
                if (doubleTap) {
                    // 取消即将触发的单击
                    pendingClickRunnable?.let { handler.removeCallbacks(it) }
                    pendingClickRunnable = null
                    handler.post { onDoubleTap() }
                } else {
                    // 安排长按
                    longPressRunnable = Runnable {
                        if (!movedBeyondTap) onLongPress()
                    }.also { handler.postDelayed(it, LONG_PRESS_MS) }
                    // 安排单击（仅当没有移动）
                    pendingClickRunnable = Runnable {
                        if (!movedBeyondTap) onClick()
                    }.also { handler.postDelayed(it, CLICK_TIMEOUT_MS) }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!isDragging && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                    isDragging = true
                    movedBeyondTap = true
                    // 取消点击 & 长按
                    pendingClickRunnable?.let { handler.removeCallbacks(it) }
                    pendingClickRunnable = null
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    longPressRunnable = null
                    hideBubbleIfAny()
                }
                if (isDragging) {
                    val nx = startParamsX + dx.toInt()
                    val ny = startParamsY + dy.toInt()
                    p.x = clampX(nx, p.width)
                    p.y = clampY(ny, p.height)
                    safeUpdate(ball, p)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 恢复缩放
                val c = ball.findViewById<View>(R.id.flBallContainer)
                c.animate().cancel()
                c.animate().scaleX(1f).scaleY(1f).setDuration(150L)
                    .setInterpolator(DecelerateInterpolator()).start()

                longPressRunnable?.let { handler.removeCallbacks(it) }
                longPressRunnable = null

                if (isDragging) {
                    // 拖拽结束：如果启用自动贴边，做吸附动画
                    if (Prefs.autoEdge) animateToNearestEdge(ball, p)
                    // 记住位置
                    Prefs.lastX = p.x
                    Prefs.lastY = p.y
                }
                return true
            }
        }
        return false
    }

    private fun clampX(x: Int, w: Int): Int = max(0, min(screenSize.x - w, x))
    private fun clampY(y: Int, h: Int): Int = max(0, min(screenSize.y - h, y))

    // ---------------- 交互事件 ----------------

    private fun onClick() {
        when (Prefs.clickAction) {
            "hello" -> showHelloBubble()
            "settings" -> openSettingsActivity()
            else -> {
                // 轻动画
                val ball = ballView ?: return
                val c = ball.findViewById<View>(R.id.flBallContainer)
                pulseOnce(c)
            }
        }
    }

    private fun onDoubleTap() {
        val ball = ballView ?: return
        val c = ball.findViewById<View>(R.id.flBallContainer)
        c.animate().cancel()
        c.animate()
            .scaleX(1.2f).scaleY(1.2f)
            .setDuration(140L)
            .withEndAction {
                c.animate().scaleX(1f).scaleY(1f).setDuration(200L)
                    .setInterpolator(OvershootInterpolator(2f)).start()
            }
            .setInterpolator(DecelerateInterpolator())
            .start()
        showHelloBubble()
    }

    private fun onLongPress() {
        // 长按触发换角色下一个
        val ids = CharacterStore.CHARACTERS.map { it.id }
        val cur = ids.indexOf(Prefs.characterId).let { (it + 1) % ids.size }
        Prefs.characterId = ids[cur]
        refreshAppearance()
        // 抖动一下
        val ball = ballView ?: return
        val c = ball.findViewById<View>(R.id.flBallContainer)
        c.animate().cancel()
        val base = c.rotation
        c.animate().rotation(base + 12f).setDuration(80L).withEndAction {
            c.animate().rotation(base - 10f).setDuration(80L).withEndAction {
                c.animate().rotation(base).setDuration(80L).start()
            }.start()
        }.start()
    }

    private fun openSettingsActivity() {
        val i = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(i)
    }

    // ---------------- 气泡（问候语） ----------------

    @SuppressLint("InflateParams")
    private fun showHelloBubble() {
        hideBubbleIfAny()
        val p = ballParams ?: return
        val def = CharacterStore.find(Prefs.characterId)
        val view = LayoutInflater.from(this).inflate(R.layout.float_bubble_layout, null, false)
        view.findViewById<TextView>(R.id.tvBubbleText).setText(def.helloRes)
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val bw = view.measuredWidth
        val bh = view.measuredHeight

        // 决定气泡显示方向：如果球偏左边就在球右边显示，偏右边就放左边
        val ballCenterX = p.x + p.width / 2
        val onLeftSide = ballCenterX < screenSize.x / 2
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val bp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        bp.gravity = Gravity.TOP or Gravity.LEFT
        if (onLeftSide) {
            bp.x = p.x + p.width - 12
            bp.y = max(0, p.y + p.height / 2 - bh / 2)
        } else {
            bp.x = max(0, p.x - bw + 12)
            bp.y = max(0, p.y + p.height / 2 - bh / 2)
        }
        // 边界纠正
        bp.x = clampX(bp.x, bw)
        bp.y = clampY(bp.y, bh)

        try {
            wm.addView(view, bp)
        } catch (_: Throwable) { return }
        bubbleView = view
        bubbleParams = bp

        // 入场动画
        val anim = AlphaAnimation(0f, 1f).apply {
            duration = 200L
        }
        val scale = ScaleAnimation(
            0.7f, 1f, 0.7f, 1f,
            Animation.RELATIVE_TO_SELF, if (onLeftSide) 0f else 1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
        ).apply { duration = 220L; interpolator = OvershootInterpolator() }
        AnimationSet(true).apply {
            addAnimation(anim); addAnimation(scale)
            view.startAnimation(this)
        }

        bubbleHideRunnable?.let { handler.removeCallbacks(it) }
        bubbleHideRunnable = Runnable { hideBubbleIfAny() }
            .also { handler.postDelayed(it, BUBBLE_DURATION_MS) }
    }

    private fun hideBubbleIfAny() {
        bubbleHideRunnable?.let { handler.removeCallbacks(it) }
        bubbleHideRunnable = null
        val v = bubbleView ?: return
        val anim = AlphaAnimation(v.alpha, 0f).apply {
            duration = 180L
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(a: Animation?) {}
                override fun onAnimationRepeat(a: Animation?) {}
                override fun onAnimationEnd(a: Animation?) {
                    removeBubbleSafe()
                }
            })
        }
        v.startAnimation(anim)
    }

    // ---------------- 边缘吸附动画 ----------------

    private fun animateToNearestEdge(ball: View, p: WindowManager.LayoutParams) {
        val centerX = p.x + p.width / 2
        val targetX = if (centerX < screenSize.x / 2) {
            ELASTIC_MARGIN_PX
        } else {
            screenSize.x - p.width - ELASTIC_MARGIN_PX
        }
        val startX = p.x
        val delta = targetX - startX
        if (delta == 0) return
        val startAt = System.currentTimeMillis()
        val interp = DecelerateInterpolator()
        handler.post(object : Runnable {
            override fun run() {
                val t = (System.currentTimeMillis() - startAt).toFloat() / EDGE_ANIM_MS
                if (t >= 1f) {
                    p.x = targetX
                    safeUpdate(ball, p)
                    Prefs.lastX = p.x
                    Prefs.lastY = p.y
                    return
                }
                val eased = interp.getInterpolation(t)
                p.x = (startX + delta * eased).roundToInt()
                safeUpdate(ball, p)
                handler.postDelayed(this, 10L)
            }
        })
    }

    // ---------------- 动画（呼吸 & 眨眼） ----------------

    private fun startEnterAnimation(v: View) {
        val s = ScaleAnimation(0f, 1f, 0f, 1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 260L
            interpolator = OvershootInterpolator(1.2f)
        }
        val a = AlphaAnimation(0f, 1f).apply { duration = 220L }
        AnimationSet(true).apply { addAnimation(s); addAnimation(a); v.startAnimation(this) }
    }

    private fun pulseOnce(v: View) {
        val s = ScaleAnimation(1f, 1.15f, 1f, 1.15f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 140L
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(a: Animation?) {}
                override fun onAnimationRepeat(a: Animation?) {}
                override fun onAnimationEnd(a: Animation?) {
                    val back = ScaleAnimation(1.15f, 1f, 1.15f, 1f,
                        Animation.RELATIVE_TO_SELF, 0.5f,
                        Animation.RELATIVE_TO_SELF, 0.5f).apply {
                        duration = 160L
                        interpolator = AccelerateInterpolator()
                    }
                    v.startAnimation(back)
                }
            })
        }
        v.startAnimation(s)
    }

    private fun startBreatheAnimation(target: View) {
        breatheRunnable?.let { handler.removeCallbacks(it) }
        breatheRunnable = object : Runnable {
            var up = true
            override fun run() {
                val scale = if (up) 1.025f else 1.0f
                up = !up
                target.animate().cancel()
                target.animate()
                    .scaleX(scale).scaleY(scale)
                    .setDuration(1600L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        if (ballView != null) {
                            handler.postDelayed(this, 80L)
                        }
                    }
                    .start()
            }
        }.also { handler.postDelayed(it, 600L) }
    }

    private fun startBlinkRoutine() {
        blinkRunnable?.let { handler.removeCallbacks(it) }
        val run = object : Runnable {
            override fun run() {
                val ball = ballView ?: return
                val c = ball.findViewById<View>(R.id.flBallContainer)
                // 眨眼：快速压缩 Y 轴再恢复
                c.animate().cancel()
                c.animate().scaleY(0.2f).setDuration(80L)
                    .withEndAction {
                        c.animate().scaleY(1f).setDuration(100L)
                            .setInterpolator(OvershootInterpolator())
                            .start()
                    }
                    .start()
                val next = (3200..6400).random().toLong()
                handler.postDelayed(this, next)
            }
        }
        blinkRunnable = run
        handler.postDelayed(run, 3500L)
    }

    private fun cancelAllAnimations() {
        pendingClickRunnable?.let { handler.removeCallbacks(it) }; pendingClickRunnable = null
        longPressRunnable?.let { handler.removeCallbacks(it) }; longPressRunnable = null
        bubbleHideRunnable?.let { handler.removeCallbacks(it) }; bubbleHideRunnable = null
        breatheRunnable?.let { handler.removeCallbacks(it) }; breatheRunnable = null
        blinkRunnable?.let { handler.removeCallbacks(it) }; blinkRunnable = null
        ballView?.clearAnimation()
        bubbleView?.clearAnimation()
    }

    // ---------------- 前台服务通知 ----------------

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID_FOREGROUND)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .build()
    }
}
