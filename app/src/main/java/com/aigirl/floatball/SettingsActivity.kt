package com.aigirl.floatball

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.aigirl.floatball.databinding.ActivitySettingsBinding

/**
 * 设置界面：角色选择、外观、交互行为
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var charAdapter: CharacterAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        try {
            binding = ActivitySettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            binding.toolbar.setNavigationOnClickListener { finish() }

            setupCharacterList()
            setupSliders()
            setupSwitches()
            setupActions()

            binding.btnSaveAndRestart.setOnClickListener { saveAndRestart() }
        } catch (e: Exception) {
            Toast.makeText(this, "设置界面加载失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun setupCharacterList() {
        charAdapter = CharacterAdapter(
            this,
            CharacterStore.CHARACTERS,
            Prefs.characterId,
            onClick = { /* immediate UI feedback only */ }
        )
        binding.rvCharacters.layoutManager = GridLayoutManager(this, 4)
        binding.rvCharacters.adapter = charAdapter
    }

    private fun setupSliders() {
        // Prefs.sizeDp 已是 5 的倍数（Slider stepSize=5），直接赋值不会崩
        val initSizeDp = Prefs.sizeDp.toFloat()
        binding.sliderSize.value = initSizeDp
        binding.tvSizeValue.text = "${initSizeDp.toInt()}dp"
        binding.sliderSize.addOnChangeListener { _, value, _ ->
            binding.tvSizeValue.text = "${value.toInt()}dp"
        }

        val initOp = Prefs.opacity.toFloat()
        binding.sliderOpacity.value = initOp
        binding.tvOpacityValue.text = "${initOp.toInt()}%"
        binding.sliderOpacity.addOnChangeListener { _, value, _ ->
            binding.tvOpacityValue.text = "${value.toInt()}%"
        }
    }

    private fun setupSwitches() {
        binding.switchAutoEdge.isChecked = Prefs.autoEdge
        binding.switchHello.isChecked = Prefs.showHelloOnStart
        binding.switchHeart.isChecked = Prefs.heartEnabled
        binding.switchWander.isChecked = Prefs.wanderMode
        binding.switchBalance.isChecked = Prefs.showBalance
        binding.etApiKey.setText(Prefs.dsApiKey)
    }

    private fun setupActions() {
        val options = listOf(
            getString(R.string.action_toolbar),
            getString(R.string.action_hello),
            getString(R.string.action_settings),
            getString(R.string.action_none),
        )
        val values = listOf("toolbar", "hello", "settings", "none")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerClickAction.adapter = adapter
        val idx = values.indexOf(Prefs.clickAction).coerceAtLeast(0)
        binding.spinnerClickAction.setSelection(idx)
        binding.spinnerClickAction.tag = values
    }

    private fun saveAndRestart() {
        try {
            Prefs.characterId = charAdapter.getSelectedId()
            Prefs.sizeDp = binding.sliderSize.value.toInt()
            Prefs.opacity = binding.sliderOpacity.value.toInt()
            Prefs.autoEdge = binding.switchAutoEdge.isChecked
            Prefs.showHelloOnStart = binding.switchHello.isChecked
            Prefs.heartEnabled = binding.switchHeart.isChecked
            Prefs.wanderMode = binding.switchWander.isChecked
            Prefs.showBalance = binding.switchBalance.isChecked
            Prefs.dsApiKey = binding.etApiKey.text.toString().trim()
            val values = binding.spinnerClickAction.tag as? List<String> ?: listOf("toolbar", "hello", "settings", "none")
            Prefs.clickAction = values.getOrElse(binding.spinnerClickAction.selectedItemPosition) { "toolbar" }

            Toast.makeText(this, "已保存，正在重启悬浮球…", Toast.LENGTH_SHORT).show()

            // 先停止再启动（需要权限 & 开关）
            val hide = Intent(this, FloatBallService::class.java).apply {
                action = FloatBallService.ACTION_HIDE
            }
            startService(hide)

            if (Prefs.enabled) {
                binding.root.postDelayed({
                    val i = Intent(this, FloatBallService::class.java).apply {
                        action = FloatBallService.ACTION_SHOW
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(i)
                    } else {
                        startService(i)
                    }
                }, 500)
            }
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
