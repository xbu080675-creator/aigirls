package com.aigirl.floatball

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.aigirl.floatball.databinding.ActivitySettingsBinding

/**
 * 设置界面：角色选择、外观、交互行为
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var charAdapter: CharacterAdapter

    // 梗包 ZIP 导入：用系统文件选择器，import 永久性 URI 权限不必要
    private val importPackLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val packId = MemePackManager.importPack(this, uri)
                if (packId != null) {
                    Toast.makeText(this, "梗包已导入：$packId", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "导入失败：找不到 pack.json 或格式错误", Toast.LENGTH_LONG).show()
                }
                refreshPackList()
            }
        }

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
            setupMemePacks()

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
        binding.switchMemeBubbles.isChecked = Prefs.memeBubblesEnabled
        binding.switchDevMode.isChecked = Prefs.isDeveloperMode
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

    private fun setupMemePacks() {
        binding.btnImportMemePack.setOnClickListener {
            // application/zip + octet-stream 兜底；某些手机 zip 走 octet-stream
            importPackLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        refreshPackList()
    }

    /** 渲染已安装梗包列表（内置 + 用户包），每包一行：名称/元信息 + 启用开关 + 删除按钮 */
    private fun refreshPackList() {
        val container = binding.llPackList
        container.removeAllViews()
        val packs = MemePackManager.listPacks(this)
        if (packs.isEmpty()) {
            container.addView(emptyHint("还没有已安装的用户梗包，点上方按钮导入 ZIP"))
            return
        }
        for (pack in packs) {
            container.addView(buildPackRow(pack))
        }
    }

    private fun buildPackRow(pack: MemePackManager.PackInfo): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        // 文本区：名称 + 元信息
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(this).apply {
            this.text = if (pack.isBuiltIn) "📦 ${pack.name}" else "🗂 ${pack.name}"
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
        })
        text.addView(TextView(this).apply {
            this.text = "${pack.author} · ${pack.license} · ${pack.itemCount} 条"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
        })
        row.addView(text)
        // 启用开关（内置恒启用、不可关）
        val sw = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            isChecked = pack.enabled
            isEnabled = !pack.isBuiltIn
            setOnCheckedChangeListener { _, checked ->
                if (pack.isBuiltIn) return@setOnCheckedChangeListener
                if (checked) MemePackManager.enablePack(this@SettingsActivity, pack.id)
                else MemePackManager.disablePack(this@SettingsActivity, pack.id)
            }
        }
        row.addView(sw)
        // 删除按钮（内置不可删）
        if (!pack.isBuiltIn) {
            val del = com.google.android.material.button.MaterialButton(this).apply {
                this.text = "删除"
                setCornerRadius(dp(8))
                setOnClickListener {
                    MemePackManager.deletePack(this@SettingsActivity, pack.id)
                    refreshPackList()
                }
            }
            row.addView(del)
        }
        return row
    }

    private fun emptyHint(msg: String): View =
        TextView(this).apply {
            text = msg
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

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
            Prefs.memeBubblesEnabled = binding.switchMemeBubbles.isChecked
            Prefs.isDeveloperMode = binding.switchDevMode.isChecked
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
