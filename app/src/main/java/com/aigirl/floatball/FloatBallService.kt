package com.aigirl.floatball

import android.annotation.SuppressLint
import android.app.AlertDialog
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
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.RotateAnimation
import android.view.animation.ScaleAnimation
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 悬浮球前台服务
 * 改编自 wngyj/AI_Bento (MIT) 的 PC 桌面宠物流程，移植到 Android 端
 * 功能：拖拽、工具栏（聊天/改名/重置/转圈/爱心/设置/关闭）、气泡、三视图行走、余额显示、AI 对话
 */
class FloatBallService : Service() {

    companion object {
        const val ACTION_SHOW = "com.aigirl.floatball.ACTION_SHOW"
        const val ACTION_HIDE = "com.aigirl.floatball.ACTION_HIDE"
        const val ACTION_REFRESH = "com.aigirl.floatball.ACTION_REFRESH"
        private const val NOTIF_ID = 10086
        private const val CLICK_TIMEOUT_MS = 260L
        private const val BUBBLE_DURATION_MS = 3500L
        private const val EDGE_ANIM_MS = 280L
    }

    private lateinit var wm: WindowManager
    private var ballView: View? = null
    private var bubbleView: View? = null
    private var toolbarView: View? = null
    // 追踪所有已 addView 到 WindowManager 的工具栏，防止幽灵窗口泄漏
    private val toolbarViews = mutableSetOf<View>()
    private var heartView: ImageView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val screenSize = Point()

    // 触摸 & 拖拽
    private var isDragging = false
    private var downX = 0f
    private var downY = 0f
    private var startParamsX = 0
    private var startParamsY = 0
    private var movedBeyondTap = false
    private var lastTapMs = 0L

    // 动画 runnables（用 token 防止重复/过期回调）
    private var helloToken = 0
    private var bubbleToken = 0
    private var breatheRunnable: Runnable? = null
    private var blinkRunnable: Runnable? = null

    // 散步模式
    private var wanderRunnable: Runnable? = null
    private var wanderStepX = 0f
    private var wanderStepY = 0f
    private var wanderStepsLeft = 0
    private var isWandering = false

    // 对话历史
    private val chatHistory = mutableListOf<Pair<String, String>>()
    private var chatBusy = false

    // 余额标签
    private var balanceView: TextView? = null
    private var balanceRunnable: Runnable? = null

    // 梗图 & 状态追踪
    private var lastChatMs = 0L
    private var rapidChatCount = 0
    private var idleRunnable: Runnable? = null
    private var lastInteractMs = System.currentTimeMillis()
    private var chatStartMs = 0L
    private var consecutiveErrors = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        MemeManager.load(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = applicationContext.resources.displayMetrics
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
                    helloToken++
                    val token = helloToken
                    handler.postDelayed({
                        if (token == helloToken && ballView != null) showHelloBubble()
                    }, 700L)
                }
            }
            ACTION_HIDE -> {
                helloToken++
                hideFloatBall()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_REFRESH -> showFloatBall(forceRefresh = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hideFloatBall()
        super.onDestroy()
    }

    // ---------- 显示/隐藏 ----------

    private fun showFloatBall(forceRefresh: Boolean) {
        if (ballView != null) {
            if (forceRefresh) removeBallViewSafe()
            else { refreshAppearance(); return }
        }
        createAndAddBallView()
    }

    private fun hideFloatBall() {
        helloToken++
        bubbleToken++
        stopWander()
        idleRunnable?.let { handler.removeCallbacks(it) }; idleRunnable = null
        balanceRunnable?.let { handler.removeCallbacks(it) }; balanceRunnable = null
        hideBalanceView()
        cancelAllAnimations()
        removeAllToolbars()
        removeBubbleImmediate()
        removeBallViewSafe()
    }

    private fun removeBallViewSafe() {
        val v = ballView ?: return
        ballView = null
        try { wm.removeView(v) } catch (_: Throwable) {}
    }

    private fun removeBubbleImmediate() {
        val v = bubbleView ?: return
        bubbleView = null
        restorePose()
        try { wm.removeView(v) } catch (_: Throwable) {}
    }

    private fun removeToolbarSafe() {
        val v = toolbarView ?: return
        removeToolbar(v)
    }

    /** 删除指定工具栏 View（同步、确定性） */
    private fun removeToolbar(tb: View) {
        toolbarViews.remove(tb)
        if (toolbarView === tb) toolbarView = null
        try {
            tb.clearAnimation()
            wm.removeViewImmediate(tb)
        } catch (_: Throwable) {}
    }

    /** 清理所有工具栏（含幽灵窗口），用于 Service 销毁 */
    private fun removeAllToolbars() {
        toolbarViews.toList().forEach { tb ->
            try {
                tb.clearAnimation()
                wm.removeViewImmediate(tb)
            } catch (_: Throwable) {}
        }
        toolbarViews.clear()
        toolbarView = null
    }

    // ---------- 创建悬浮球 ----------

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createAndAddBallView() {
        val ball = LayoutInflater.from(this).inflate(R.layout.float_ball_layout, null, false)
        val iv = ball.findViewById<ImageView>(R.id.ivCharacter)
        iv.setImageResource(CharacterStore.find(Prefs.characterId).drawableRes)

        val size = Prefs.sizePx
        ball.layoutParams?.width = size
        ball.layoutParams?.height = size

        val type = overlayType()
        val params = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.LEFT

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

        ball.setOnTouchListener { _, e -> handleTouch(e) }

        try { wm.addView(ball, params) } catch (_: Throwable) { return }

        startEnterAnimation(ball)
        startBreatheAnimation(ball.findViewById(R.id.flBallRoot))
        startBlinkRoutine()
        startWanderIfEnabled()
        startBalanceLoop()
        startIdleMemeRoutine()
        markInteracted()
    }

    private fun refreshAppearance() {
        val ball = ballView ?: return
        val size = Prefs.sizePx
        ball.findViewById<ImageView>(R.id.ivCharacter)
            .setImageResource(CharacterStore.find(Prefs.characterId).drawableRes)
        ball.alpha = Prefs.opacity / 100f
        ballParams?.let { p ->
            if (p.width != size || p.height != size) {
                p.width = size; p.height = size
                safeUpdate(ball, p)
            }
        }
    }

    private fun safeUpdate(v: View, p: WindowManager.LayoutParams) {
        try { wm.updateViewLayout(v, p) } catch (_: Throwable) {}
        if (v === ballView) syncBalancePosition()
    }

    /** 让余额标签跟随球的位置移动 */
    private fun syncBalancePosition() {
        val tv = balanceView ?: return
        val p = ballParams ?: return
        tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val lp = tv.layoutParams as WindowManager.LayoutParams
        lp.x = p.x + p.width / 2 - tv.measuredWidth / 2
        lp.y = p.y + p.height + 4
        try { wm.updateViewLayout(tv, lp) } catch (_: Throwable) {}
    }

    private fun overlayType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ---------- 触摸 & 拖拽 ----------

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
                hideToolbar()
                stopWander() // 拖拽时停止散步
                ball.animate().cancel()
                ball.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120L)
                    .setInterpolator(OvershootInterpolator()).start()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!isDragging && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                    isDragging = true
                    movedBeyondTap = true
                    removeBubbleImmediate()
                }
                if (isDragging) {
                    p.x = clampX(startParamsX + dx.toInt(), p.width)
                    p.y = clampY(startParamsY + dy.toInt(), p.height)
                    safeUpdate(ball, p)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ball.animate().cancel()
                ball.animate().scaleX(1f).scaleY(1f).setDuration(150L)
                    .setInterpolator(DecelerateInterpolator()).start()
                if (isDragging) {
                    if (Prefs.autoEdge) animateToNearestEdge(ball, p)
                    Prefs.lastX = p.x
                    Prefs.lastY = p.y
                    handler.postDelayed({ startWanderIfEnabled() }, 500)
                } else {
                    // 判定单击/双击
                    val now = System.currentTimeMillis()
                    if (now - lastTapMs < 350L) {
                        lastTapMs = 0
                        onDoubleTap()
                    } else {
                        lastTapMs = now
                        handler.postDelayed({
                            if (lastTapMs != 0L && System.currentTimeMillis() - lastTapMs >= CLICK_TIMEOUT_MS) {
                                lastTapMs = 0
                                onClick()
                            }
                        }, CLICK_TIMEOUT_MS + 20)
                    }
                }
                return true
            }
        }
        return false
    }

    private fun clampX(x: Int, w: Int) = max(0, min(screenSize.x - w, x))
    private fun clampY(y: Int, h: Int) = max(0, min(screenSize.y - h, y))

    // ---------- 交互事件 ----------

    private fun onClick() {
        markInteracted()
        when (Prefs.clickAction) {
            "hello" -> showHelloBubble()
            "settings" -> openSettings()
            "none" -> pulseOnce()
            else -> showToolbar() // 默认 toolbar
        }
    }

    private fun onDoubleTap() {
        markInteracted()
        val ball = ballView ?: return
        ball.animate().cancel()
        ball.animate().scaleX(1.2f).scaleY(1.2f).setDuration(140L)
            .withEndAction {
                ball.animate().scaleX(1f).scaleY(1f).setDuration(220L)
                    .setInterpolator(OvershootInterpolator(2f)).start()
            }.setInterpolator(DecelerateInterpolator()).start()
        showHelloBubble()
    }

    // ---------- 气泡（修复重复 bug：立即移除旧气泡 + token 防过期） ----------

    @SuppressLint("InflateParams")
    private fun showHelloBubble() {
        val p = ballParams ?: return
        // 立即移除旧气泡（关键修复：不等动画）
        removeBubbleImmediate()
        bubbleToken++

        val def = CharacterStore.find(Prefs.characterId)
        val view = LayoutInflater.from(this).inflate(R.layout.float_bubble_layout, null, false)
        val text = if (Prefs.petName.isNotEmpty()) {
            "${Prefs.petName}：${getString(def.helloRes)}"
        } else {
            getString(def.helloRes)
        }
        view.findViewById<TextView>(R.id.tvBubbleText).text = text
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val bw = view.measuredWidth
        val bh = view.measuredHeight

        val ballCenterX = p.x + p.width / 2
        val onLeftSide = ballCenterX < screenSize.x / 2
        val bp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        bp.gravity = Gravity.TOP or Gravity.LEFT
        bp.x = if (onLeftSide) p.x + p.width - 12 else max(0, p.x - bw + 12)
        bp.y = max(0, p.y + p.height / 2 - bh / 2)
        bp.x = clampX(bp.x, bw)
        bp.y = clampY(bp.y, bh)

        try { wm.addView(view, bp) } catch (_: Throwable) { return }
        bubbleView = view

        val anim = AlphaAnimation(0f, 1f).apply { duration = 200L }
        val scale = ScaleAnimation(
            0.7f, 1f, 0.7f, 1f,
            Animation.RELATIVE_TO_SELF, if (onLeftSide) 0f else 1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
        ).apply { duration = 220L; interpolator = OvershootInterpolator() }
        AnimationSet(true).apply { addAnimation(anim); addAnimation(scale); view.startAnimation(this) }

        val token = bubbleToken
        handler.postDelayed({
            if (token == bubbleToken) hideBubbleAnimated()
        }, BUBBLE_DURATION_MS)
    }

    private fun hideBubbleAnimated() {
        val v = bubbleView ?: return
        val anim = AlphaAnimation(v.alpha, 0f).apply {
            duration = 180L
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(a: Animation?) {}
                override fun onAnimationRepeat(a: Animation?) {}
                override fun onAnimationEnd(a: Animation?) { removeBubbleImmediate() }
            })
        }
        v.startAnimation(anim)
    }

    // ---------- DSH 风格工具栏 ----------

    @SuppressLint("InflateParams")
    private fun showToolbar() {
        if (toolbarView != null) { hideToolbar(); return }
        val p = ballParams ?: return
        val tb = LayoutInflater.from(this).inflate(R.layout.float_toolbar_layout, null, false)

        setupTbBtn(tb.findViewById(R.id.btnChat), R.drawable.ic_chat, R.string.tb_chat) { showChatDialog() }
        setupTbBtn(tb.findViewById(R.id.btnRename), R.drawable.ic_rename, R.string.tb_rename) { showRenameDialog() }
        setupTbBtn(tb.findViewById(R.id.btnReset), R.drawable.ic_reset, R.string.tb_reset) { resetPosition() }
        setupTbBtn(tb.findViewById(R.id.btnSpin), R.drawable.ic_spin, R.string.tb_spin) { doSpin() }
        setupTbBtn(tb.findViewById(R.id.btnHeart), R.drawable.ic_heart, R.string.tb_heart) { doHeart() }
        setupTbBtn(tb.findViewById(R.id.btnSettings), R.drawable.ic_tb_settings, R.string.tb_settings) { openSettings() }
        setupTbBtn(tb.findViewById(R.id.btnClose), R.drawable.ic_close, R.string.tb_close) {
            hideToolbar()
            stopSelf()
        }

        tb.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val tw = tb.measuredWidth
        val th = tb.measuredHeight

        val tp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        tp.gravity = Gravity.TOP or Gravity.LEFT
        // 工具栏显示在球的上方；若空间不够则放下方
        val above = p.y - th - 8
        tp.x = clampX(p.x + p.width / 2 - tw / 2, tw)
        tp.y = if (above >= 0) above else p.y + p.height + 8
        tp.y = clampY(tp.y, th)

        try { wm.addView(tb, tp) } catch (_: Throwable) { return }
        toolbarViews.add(tb)
        toolbarView = tb

        val s = ScaleAnimation(0.5f, 1f, 0.5f, 1f,
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 1f
        ).apply { duration = 200L; interpolator = OvershootInterpolator() }
        val a = AlphaAnimation(0f, 1f).apply { duration = 180L }
        AnimationSet(true).apply { addAnimation(s); addAnimation(a); tb.startAnimation(this) }
    }

    private fun hideToolbar() {
        val tb = toolbarView ?: return
        // 同步删除：先解除引用，再立即从 WindowManager 移除，
        // 避免旧 View 未销毁就允许新建导致幽灵窗口叠加
        toolbarView = null
        removeToolbar(tb)
    }

    private fun setupTbBtn(container: View, iconRes: Int, labelRes: Int, action: () -> Unit) {
        container.findViewById<ImageView>(R.id.ivIcon).setImageResource(iconRes)
        container.findViewById<TextView>(R.id.tvLabel).setText(labelRes)
        container.setOnClickListener {
            hideToolbar()
            handler.postDelayed({ action() }, 160)
        }
    }

    // ---------- 工具栏动作 ----------

    @SuppressLint("InflateParams")
    private fun showRenameDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.rename_hint)
            setText(Prefs.petName)
            setPadding(40, 30, 40, 10)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle(R.string.tb_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Prefs.petName = input.text.toString().trim()
                showHelloBubble()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        )
        dialog.show()
    }

    // ---------- AI 对话 ----------

    @SuppressLint("InflateParams")
    private fun showChatDialog() {
        if (chatBusy) { showBubbleText("等等，上一句还没回完呢"); return }
        val input = EditText(this).apply {
            hint = getString(R.string.chat_hint)
            setPadding(40, 30, 40, 10)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle(R.string.tb_chat)
            .setView(input)
            .setPositiveButton("发送") { _, _ ->
                val msg = input.text.toString().trim()
                if (msg.isNotEmpty()) doChat(msg)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        )
        dialog.show()
    }

    private fun doChat(msg: String) {
        chatBusy = true
        chatStartMs = System.currentTimeMillis()
        markInteracted()
        // 检测用户是否"狂发消息"（愤怒）：30s 内连续 3 次以上
        val now = System.currentTimeMillis()
        if (now - lastChatMs < 30000L) {
            rapidChatCount++
            if (rapidChatCount >= 3) {
                triggerMeme("USER_ANGRY")
                rapidChatCount = 0
            }
        } else {
            rapidChatCount = 1
        }
        lastChatMs = now

        showBubbleText("思考中…")
        DeepSeekApi.chat(Prefs.dsApiKey, msg, chatHistory,
            onResult = { reply ->
                chatBusy = false
                consecutiveErrors = 0
                chatHistory.add(msg to reply)
                if (chatHistory.size > 40) chatHistory.removeAt(0)
                showBubbleText(reply)
                // 思考时长 > 8s 触发 THINKING_LONG；否则消耗 token 梗
                // 延迟 2.5s 让用户先读到回复，再冒状态梗
                val elapsed = System.currentTimeMillis() - chatStartMs
                val event = if (elapsed > 8000L) "THINKING_LONG" else "TOKEN_SPENT"
                handler.postDelayed({ triggerMeme(event) }, 2500L)
            },
            onError = { err ->
                chatBusy = false
                consecutiveErrors++
                showBubbleText(err)
                // 超时 / 连续失败 -> 重试梗；否则失败梗
                val isTimeout = err.contains("超时") || err.contains("网络")
                if (isTimeout || consecutiveErrors >= 2) {
                    triggerMeme("API_RETRY")
                } else {
                    triggerMeme("API_FAILED")
                }
            }
        )
    }

    private fun showBubbleText(text: String) {
        val p = ballParams ?: return
        removeBubbleImmediate()
        bubbleToken++
        val view = LayoutInflater.from(this).inflate(R.layout.float_bubble_layout, null, false)
        view.findViewById<TextView>(R.id.tvBubbleText).text = text
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val bw = view.measuredWidth
        val bh = view.measuredHeight
        val ballCenterX = p.x + p.width / 2
        val onLeftSide = ballCenterX < screenSize.x / 2
        val bp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        bp.gravity = Gravity.TOP or Gravity.LEFT
        bp.x = if (onLeftSide) p.x + p.width - 12 else max(0, p.x - bw + 12)
        bp.y = max(0, p.y + p.height / 2 - bh / 2)
        bp.x = clampX(bp.x, bw); bp.y = clampY(bp.y, bh)
        try { wm.addView(view, bp) } catch (_: Throwable) { return }
        bubbleView = view
        val anim = AlphaAnimation(0f, 1f).apply { duration = 200L }
        view.startAnimation(anim)
        val token = bubbleToken
        handler.postDelayed({ if (token == bubbleToken) hideBubbleAnimated() }, BUBBLE_DURATION_MS)
    }

    // ---------- 鲸鲸梗宇宙：事件 -> 文案 + 表情 pose ----------

    /** 触发一个事件，自动挑选对应梗图并以气泡+pose 展示 */
    private fun triggerMeme(event: String) {
        if (!Prefs.memeBubblesEnabled) return
        if (!MemeManager.isLoaded()) MemeManager.load(this)
        val meme = MemeManager.pickForEvent(event, Prefs.isDeveloperMode) ?: return
        showMemeBubble(meme)
    }

    /** 展示一张梗图：切换 pose + 气泡文案 */
    private fun showMemeBubble(meme: MemeManager.Meme) {
        val p = ballParams ?: return
        removeBubbleImmediate()
        applyPose(meme.pose)
        bubbleToken++
        val view = LayoutInflater.from(this).inflate(R.layout.float_bubble_layout, null, false)
        val prefix = if (Prefs.petName.isNotEmpty()) "${Prefs.petName}：" else ""
        view.findViewById<TextView>(R.id.tvBubbleText).text = prefix + meme.text
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val bw = view.measuredWidth
        val bh = view.measuredHeight
        val ballCenterX = p.x + p.width / 2
        val onLeftSide = ballCenterX < screenSize.x / 2
        val bp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        bp.gravity = Gravity.TOP or Gravity.LEFT
        bp.x = if (onLeftSide) p.x + p.width - 12 else max(0, p.x - bw + 12)
        bp.y = max(0, p.y + p.height / 2 - bh / 2)
        bp.x = clampX(bp.x, bw); bp.y = clampY(bp.y, bh)
        try { wm.addView(view, bp) } catch (_: Throwable) { return }
        bubbleView = view
        val anim = AlphaAnimation(0f, 1f).apply { duration = 200L }
        val scale = ScaleAnimation(
            0.7f, 1f, 0.7f, 1f,
            Animation.RELATIVE_TO_SELF, if (onLeftSide) 0f else 1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
        ).apply { duration = 220L; interpolator = OvershootInterpolator() }
        AnimationSet(true).apply { addAnimation(anim); addAnimation(scale); view.startAnimation(this) }

        val token = bubbleToken
        handler.postDelayed({
            if (token == bubbleToken) {
                hideBubbleAnimated()
                restorePose()
            }
        }, BUBBLE_DURATION_MS)
    }

    /**
     * 表情 pose -> 三视图素材 + 一次性动画
     * pose 取值：happy / smug / grievance / shocked / sneaky / caught / guilty / endure / thinking / teaching / determined
     * 仅用现有 front/side/back 三视图 + 缩放/抖动/旋转组合出情绪
     */
    private fun applyPose(pose: String) {
        val ball = ballView ?: return
        val iv = ball.findViewById<ImageView>(R.id.ivCharacter)
        val def = CharacterStore.find(Prefs.characterId)
        ball.animate().cancel()
        iv.scaleX = 1f; iv.scaleY = 1f; iv.rotation = 0f
        when (pose) {
            "smug" -> { // 得意：正面 + 放大
                iv.setImageResource(def.drawableRes)
                ball.animate().scaleX(1.12f).scaleY(1.12f).setDuration(180L)
                    .setInterpolator(OvershootInterpolator()).start()
            }
            "grievance" -> { // 委屈：正面 + 微缩
                iv.setImageResource(def.drawableRes)
                ball.animate().scaleX(0.92f).scaleY(0.92f).setDuration(180L).start()
            }
            "shocked" -> { // 震惊：正面 + 快速抖动
                iv.setImageResource(def.drawableRes)
                shakeOnce(ball)
            }
            "sneaky" -> { // 摸鱼/偷吃：侧面
                if (def.hasThreeViews) iv.setImageResource(def.sideRes) else iv.setImageResource(def.drawableRes)
                ball.animate().scaleX(0.98f).scaleY(0.98f).setDuration(150L).start()
            }
            "caught" -> { // 被抓包：背面 + 抖动
                if (def.hasThreeViews) iv.setImageResource(def.backRes) else iv.setImageResource(def.drawableRes)
                shakeOnce(ball)
            }
            "guilty" -> { // 心虚：正面 + 缩小半透明
                iv.setImageResource(def.drawableRes)
                ball.animate().scaleX(0.85f).scaleY(0.85f).alpha(0.7f).setDuration(200L).start()
            }
            "endure" -> { // 忍耐：正面 + 慢速小抖动
                iv.setImageResource(def.drawableRes)
                ball.animate().scaleX(0.97f).scaleY(0.97f).setDuration(300L).start()
                handler.postDelayed({ shakeOnce(ball) }, 150)
            }
            "thinking" -> { // 思考：正面 + 微倾
                iv.setImageResource(def.drawableRes)
                ball.animate().rotation(-4f).setDuration(200L).start()
            }
            "teaching", "determined" -> { // 教学/坚定：正面 + 放大
                iv.setImageResource(def.drawableRes)
                ball.animate().scaleX(1.1f).scaleY(1.1f).setDuration(180L)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
            else -> { // happy / 默认：正面
                iv.setImageResource(def.drawableRes)
                ball.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150L)
                    .setInterpolator(OvershootInterpolator()).start()
            }
        }
    }

    /** 快速左右抖动一次 */
    private fun shakeOnce(v: View) {
        v.animate().cancel()
        v.animate().translationX(-12f).setDuration(60L)
            .withEndAction {
                v.animate().translationX(12f).setDuration(60L)
                    .withEndAction {
                        v.animate().translationX(0f).setDuration(60L).start()
                    }.start()
            }.start()
    }

    /** 恢复默认正面姿态（气泡消失时调用） */
    private fun restorePose() {
        val ball = ballView ?: return
        val iv = ball.findViewById<ImageView>(R.id.ivCharacter)
        ball.animate().cancel()
        ball.animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f).translationX(0f)
            .setDuration(200L).setInterpolator(DecelerateInterpolator()).start()
        iv.scaleX = 1f
        if (!isWandering) iv.setImageResource(CharacterStore.find(Prefs.characterId).drawableRes)
    }

    /** 更新最后交互时间（点击/聊天/拖拽时调用） */
    private fun markInteracted() {
        lastInteractMs = System.currentTimeMillis()
    }

    /** 空闲摸鱼检测：超过 60s 无交互则触发 IDLE_MOFISH */
    private fun startIdleMemeRoutine() {
        idleRunnable?.let { handler.removeCallbacks(it) }
        idleRunnable = object : Runnable {
            override fun run() {
                val idle = System.currentTimeMillis() - lastInteractMs
                if (idle >= 60000L && ballView != null) {
                    triggerMeme("IDLE_MOFISH")
                    markInteracted() // 触发后重置，避免连续刷
                }
                handler.postDelayed(this, 20000L)
            }
        }
        handler.postDelayed(idleRunnable!!, 60000L)
    }

    // ---------- 散步模式 & 三视图切换 ----------

    private fun startWanderIfEnabled() {
        if (!Prefs.wanderMode) { stopWander(); return }
        if (isWandering) return
        isWandering = true
        scheduleNextWander()
    }

    private fun stopWander() {
        isWandering = false
        wanderRunnable?.let { handler.removeCallbacks(it) }; wanderRunnable = null
        // 回到正面
        switchView(CharacterStore.find(Prefs.characterId).drawableRes, mirror = false)
    }

    private fun scheduleNextWander() {
        if (!isWandering) return
        val delay = (800..2200).random().toLong()
        wanderRunnable = Runnable {
            if (!isWandering) return@Runnable
            // 随机方向与步数
            val angle = (Math.random() * 2 * Math.PI).toFloat()
            val speed = 1.5f + kotlin.random.Random.nextFloat() * 2.0f
            wanderStepX = kotlin.math.cos(angle) * speed
            wanderStepY = kotlin.math.sin(angle) * speed
            wanderStepsLeft = (15..45).random()
            switchViewForDirection(wanderStepX, wanderStepY)
            wanderTick()
        }
        handler.postDelayed(wanderRunnable!!, delay)
    }

    private fun wanderTick() {
        if (!isWandering || wanderStepsLeft <= 0) {
            scheduleNextWander()
            return
        }
        val ball = ballView ?: return
        val p = ballParams ?: return
        p.x = clampX(p.x + wanderStepX.toInt(), p.width)
        p.y = clampY(p.y + wanderStepY.toInt(), p.height)
        // 撞墙就停下换方向
        if (p.x <= 0 || p.x >= screenSize.x - p.width || p.y <= 0 || p.y >= screenSize.y - p.height) {
            wanderStepsLeft = 0
            safeUpdate(ball, p)
            scheduleNextWander()
            return
        }
        safeUpdate(ball, p)
        wanderStepsLeft--
        if (wanderStepsLeft > 0) {
            handler.postDelayed({ wanderTick() }, 30L)
        } else {
            scheduleNextWander()
        }
    }

    private fun switchViewForDirection(dx: Float, dy: Float) {
        val def = CharacterStore.find(Prefs.characterId)
        if (!def.hasThreeViews) return
        val adx = abs(dx); val ady = abs(dy)
        when {
            adx >= ady -> {
                // 左右走用侧面；向右走镜像
                switchView(def.sideRes, mirror = dx > 0)
            }
            dy < 0 -> {
                // 向上走用背面
                switchView(def.backRes, mirror = false)
            }
            else -> {
                // 向下走用正面
                switchView(def.drawableRes, mirror = false)
            }
        }
    }

    private fun switchView(@DrawableRes res: Int, mirror: Boolean) {
        if (res == 0) return
        val ball = ballView ?: return
        val iv = ball.findViewById<ImageView>(R.id.ivCharacter)
        iv.setImageResource(res)
        iv.scaleX = if (mirror) -1f else 1f
    }

    // ---------- 余额显示 ----------

    private fun refreshBalanceView() {
        if (!Prefs.showBalance) { hideBalanceView(); return }
        if (ballParams == null) return
        if (balanceView == null) {
            val tv = TextView(this).apply {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xE616A34A.toInt())
                setPadding(16, 6, 16, 6)
                textSize = 11f
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.TOP or Gravity.LEFT
            try { wm.addView(tv, lp) } catch (_: Throwable) { return }
            balanceView = tv
        }
        val tv = balanceView ?: return
        tv.text = "吃token中…"
        syncBalancePosition()
        DeepSeekApi.balance(Prefs.dsApiKey) { text, ok ->
            handler.post {
                balanceView?.text = text
                balanceView?.setBackgroundColor(if (ok) 0xE616A34A.toInt() else 0xE6DC2626.toInt())
                syncBalancePosition() // 文本变了重新居中
                // 余额状态梗：>=1 元触发 BALANCE_OK，否则 BALANCE_LOW
                if (ok) {
                    val low = text.contains("吃不起") || text.contains("0.00")
                    triggerMeme(if (low) "BALANCE_LOW" else "BALANCE_OK")
                }
            }
        }
    }

    private fun hideBalanceView() {
        balanceView?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        balanceView = null
    }

    private fun startBalanceLoop() {
        balanceRunnable?.let { handler.removeCallbacks(it) }
        if (!Prefs.showBalance) return
        refreshBalanceView()
        balanceRunnable = Runnable {
            refreshBalanceView()
            handler.postDelayed(balanceRunnable!!, 30000L)
        }
        handler.postDelayed(balanceRunnable!!, 30000L)
    }

    private fun resetPosition() {
        val ball = ballView ?: return
        val p = ballParams ?: return
        p.x = screenSize.x - p.width - 24
        p.y = screenSize.y / 3
        Prefs.lastX = p.x; Prefs.lastY = p.y
        safeUpdate(ball, p)
        if (Prefs.autoEdge) animateToNearestEdge(ball, p)
    }

    private fun doSpin() {
        val ball = ballView ?: return
        ball.animate().cancel()
        ball.animate().rotationBy(360f).setDuration(500L)
            .setInterpolator(LinearInterpolator()).start()
    }

    private fun doHeart() {
        if (!Prefs.heartEnabled) return
        val ball = ballView ?: return
        val p = ballParams ?: return
        val heart = ImageView(this).apply {
            setImageResource(R.drawable.ic_heart)
            layoutParams = FrameLayout.LayoutParams(60, 60)
        }
        val hp = WindowManager.LayoutParams(60, 60, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT)
        hp.gravity = Gravity.TOP or Gravity.LEFT
        hp.x = p.x + p.width / 2 - 30
        hp.y = p.y
        try { wm.addView(heart, hp) } catch (_: Throwable) { return }
        heartView = heart

        heart.animate().cancel()
        heart.animate()
            .translationYBy(-(p.height).toFloat())
            .scaleX(1.5f).scaleY(1.5f)
            .alpha(0f)
            .setDuration(900L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                try { wm.removeView(heart) } catch (_: Throwable) {}
                heartView = null
            }.start()

        // 球也跳一下
        ball.animate().cancel()
        ball.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120L)
            .withEndAction {
                ball.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
            }.start()
    }

    private fun openSettings() {
        try {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            showBubbleText("打开设置失败: ${e.message?.take(15) ?: "未知错误"}")
        }
    }

    // ---------- 边缘吸附 ----------

    private fun animateToNearestEdge(ball: View, p: WindowManager.LayoutParams) {
        val centerX = p.x + p.width / 2
        val targetX = if (centerX < screenSize.x / 2) 2 else screenSize.x - p.width - 2
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
                    Prefs.lastX = p.x; Prefs.lastY = p.y
                    return
                }
                p.x = (startX + delta * interp.getInterpolation(t)).toInt()
                safeUpdate(ball, p)
                handler.postDelayed(this, 10L)
            }
        })
    }

    // ---------- 动画 ----------

    private fun startEnterAnimation(v: View) {
        val s = ScaleAnimation(0f, 1f, 0f, 1f,
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f
        ).apply { duration = 260L; interpolator = OvershootInterpolator(1.2f) }
        val a = AlphaAnimation(0f, 1f).apply { duration = 220L }
        AnimationSet(true).apply { addAnimation(s); addAnimation(a); v.startAnimation(this) }
    }

    private fun pulseOnce() {
        val ball = ballView ?: return
        val s = ScaleAnimation(1f, 1.15f, 1f, 1.15f,
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 140L
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(a: Animation?) {}
                override fun onAnimationRepeat(a: Animation?) {}
                override fun onAnimationEnd(a: Animation?) {
                    ball.startAnimation(ScaleAnimation(1.15f, 1f, 1.15f, 1f,
                        Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f
                    ).apply { duration = 160L; interpolator = AccelerateInterpolator() })
                }
            })
        }
        ball.startAnimation(s)
    }

    private fun startBreatheAnimation(target: View) {
        breatheRunnable?.let { handler.removeCallbacks(it) }
        breatheRunnable = object : Runnable {
            var up = true
            override fun run() {
                val scale = if (up) 1.03f else 1.0f
                up = !up
                target.animate().cancel()
                target.animate().scaleX(scale).scaleY(scale)
                    .setDuration(1600L).setInterpolator(DecelerateInterpolator())
                    .withEndAction { if (ballView != null) handler.postDelayed(this, 80L) }
                    .start()
            }
        }.also { handler.postDelayed(it, 600L) }
    }

    private fun startBlinkRoutine() {
        blinkRunnable?.let { handler.removeCallbacks(it) }
        val run = object : Runnable {
            override fun run() {
                val ball = ballView ?: return
                ball.animate().cancel()
                ball.animate().scaleY(0.15f).setDuration(80L)
                    .withEndAction {
                        ball.animate().scaleY(1f).setDuration(110L)
                            .setInterpolator(OvershootInterpolator()).start()
                    }.start()
                handler.postDelayed(this, (3200..6400).random().toLong())
            }
        }
        blinkRunnable = run
        handler.postDelayed(run, 3500L)
    }

    private fun cancelAllAnimations() {
        breatheRunnable?.let { handler.removeCallbacks(it) }; breatheRunnable = null
        blinkRunnable?.let { handler.removeCallbacks(it) }; blinkRunnable = null
        ballView?.clearAnimation()
        bubbleView?.clearAnimation()
        heartView?.let { try { wm.removeView(it) } catch (_: Throwable) {} }; heartView = null
    }

    // ---------- 通知 ----------

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
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
