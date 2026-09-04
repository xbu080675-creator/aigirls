package com.aigirl.floatball

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aigirl.floatball.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * 主界面：权限引导 + 启用开关
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStates()
            if (canDrawOverlays() && Prefs.enabled) {
                startFloatBall()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.switchEnable.setOnCheckedChangeListener { _, isChecked ->
            Prefs.enabled = isChecked
            if (isChecked) {
                if (!canDrawOverlays()) {
                    requestOverlayPermission()
                } else {
                    startFloatBall()
                }
            } else {
                stopFloatBall()
            }
        }

        binding.btnGrantPermission.setOnClickListener { requestOverlayPermission() }
        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        refreshCharacterPreview()
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
        refreshCharacterPreview()
    }

    private fun refreshStates() {
        val canDraw = canDrawOverlays()
        binding.btnGrantPermission.visibility = if (canDraw) android.view.View.GONE else android.view.View.VISIBLE
        // 已启用但无权限时，提示权限
        binding.switchEnable.isChecked = Prefs.enabled
    }

    private fun refreshCharacterPreview() {
        val c = CharacterStore.find(Prefs.characterId)
        binding.ivCurrentCharacter.setImageResource(c.drawableRes)
        binding.tvCharName.setText(c.nameRes)
        binding.tvCharHello.setText(c.helloRes)
    }

    private fun canDrawOverlays(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            Toast.makeText(this, R.string.request_overlay_permission, Toast.LENGTH_LONG).show()
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startFloatBall() {
        val i = Intent(this, FloatBallService::class.java).apply {
            action = FloatBallService.ACTION_SHOW
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    private fun stopFloatBall() {
        startService(Intent(this, FloatBallService::class.java).apply {
            action = FloatBallService.ACTION_HIDE
        })
    }
}
