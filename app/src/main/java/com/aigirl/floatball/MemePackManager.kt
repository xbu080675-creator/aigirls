package com.aigirl.floatball

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 可安装梗包管理器。
 *
 * v1.4.3 目标：把“梗包”从仓库内置的单一 JSON 升级成“用户可导入 ZIP 的可安装包”格式。
 * 程序本体只提供引擎，用户自己导入有权使用的图片内容，仓库不打包来源不明的二创表情包。
 *
 * 梗包 ZIP 结构（与用户提案一致）：
 *   whale_pack.zip
 *   ├── pack.json
 *   ├── images/
 *   │   ├── eat_rice.webp
 *   │   └── steal_token.webp
 *   └── README.txt        （可选，忽略）
 *
 * pack.json（v1 渐进兼容格式：字段名兼容内置包，memes/trigger 均可，items/events 也可）：
 *   {
 *     "formatVersion": 1,
 *     "id": "user.whale.memes",
 *     "name": "我的鲸鲸梗包",
 *     "author": "用户自定义",
 *     "license": "user-provided",
 *     "memes" 或 "items": [
 *       { "id":"eat_rice", "image":"images/eat_rice.webp", "text":"...", "trigger":["BALANCE_OK"], ... }
 *     ]
 *   }
 *
 * 解压后存于私有目录：/files/meme_packs/<pack_id>/
 * 不依赖原文件位置，无需每次重申请读取权限。
 *
 * “快速添加表情包”UI 放在下一轮，本版只提供 ZIP 导入入口。
 */
object MemePackManager {

    private const val DIR_PACKS = "meme_packs"
    private const val PACK_JSON = "pack.json"

    /** 已安装梗包的元信息（内置 + 用户）。 */
    data class PackInfo(
        val id: String,
        val name: String,
        val author: String,
        val license: String,
        val itemCount: Int,
        val enabled: Boolean,
        val isBuiltIn: Boolean,
        /** 用户包的解压根目录；内置包为 null。 */
        val dir: File?,
    )

    private fun packsRoot(ctx: Context): File =
        File(ctx.filesDir, DIR_PACKS).apply { mkdirs() }

    /** 全量重载：内置 + 已启用用户包。会先 [MemeManager.reset] 再装。 */
    fun reloadAll(ctx: Context) {
        MemeManager.reset()
        MemeManager.load(ctx) // 内置
        val enabled = Prefs.enabledUserPacks
        for (pack in listUserPacks(ctx)) {
            if (pack.id !in enabled) continue
            val parsed = parsePack(pack.dir!!)
            if (parsed != null) {
                val (info, memes) = parsed
                // 给用户包的 meme id 加前缀，避免与内置/其他包重名导致冷却串台
                val prefixed = memes.map { it.copy(id = "${info.id}::${it.id}") }
                MemeManager.appendMemes(prefixed)
            }
        }
    }

    /**
     * 从 ZIP URI 导入梗包。解压到 /files/meme_packs/<pack_id>/。
     * @return 成功返回 pack_id；失败返回 null（调用方提示）。
     */
    fun importPack(ctx: Context, zipUri: android.net.Uri): String? {
        return try {
            val tmpDir = File(packsRoot(ctx), ".tmp_${System.currentTimeMillis()}")
            tmpDir.mkdirs()
            ctx.contentResolver.openInputStream(zipUri).use { ins ->
                if (ins == null) return null
                unzipInto(ins, tmpDir)
            }
            // 找 pack.json：可能在 tmpDir 根，也可能在唯一一级子目录里
            val (_, baseDir) = locatePackJson(tmpDir) ?: run {
                tmpDir.deleteRecursively()
                return null
            }
            val parsed = parsePack(baseDir) ?: run {
                tmpDir.deleteRecursively()
                return null
            }
            val (info, _) = parsed
            val finalDir = File(packsRoot(ctx), info.id)
            // 已存在则覆盖
            if (finalDir.exists()) finalDir.deleteRecursively()
            if (!tmpDir.renameTo(finalDir)) {
                // rename 跨目录可能失败，复制
                tmpDir.copyRecursively(finalDir, overwrite = true)
                tmpDir.deleteRecursively()
            }
            // 导入后默认启用
            Prefs.enabledUserPacks = Prefs.enabledUserPacks + info.id
            reloadAll(ctx)
            info.id
        } catch (e: Exception) {
            null
        }
    }

    /** 列出所有已安装包（含内置）。 */
    fun listPacks(ctx: Context): List<PackInfo> {
        val result = mutableListOf<PackInfo>()
        // 内置
        result.add(
            PackInfo(
                id = "__builtin__",
                name = "鲸鲸梗宇宙（内置）",
                author = "App 内置",
                license = "CC BY-NC-SA 4.0",
                itemCount = countBuiltInItems(ctx),
                enabled = true,
                isBuiltIn = true,
                dir = null,
            )
        )
        result.addAll(listUserPacks(ctx))
        return result
    }

    private fun listUserPacks(ctx: Context): List<PackInfo> {
        val enabled = Prefs.enabledUserPacks
        val out = mutableListOf<PackInfo>()
        val root = packsRoot(ctx)
        root.listFiles { f -> f.isDirectory }?.forEach { dir ->
            val parsed = parsePack(dir)
            if (parsed != null) {
                val (info, memes) = parsed
                out.add(
                    PackInfo(
                        id = info.id,
                        name = info.name,
                        author = info.author,
                        license = info.license,
                        itemCount = memes.size,
                        enabled = info.id in enabled,
                        isBuiltIn = false,
                        dir = dir,
                    )
                )
            }
        }
        return out
    }

    fun enablePack(ctx: Context, packId: String) {
        Prefs.enabledUserPacks = Prefs.enabledUserPacks + packId
        reloadAll(ctx)
    }

    fun disablePack(ctx: Context, packId: String) {
        Prefs.enabledUserPacks = Prefs.enabledUserPacks - packId
        reloadAll(ctx)
    }

    /** 删除用户包（解压目录 + 从启用集合移除）。内置不可删。 */
    fun deletePack(ctx: Context, packId: String): Boolean {
        if (packId == "__builtin__") return false
        val dir = File(packsRoot(ctx), packId)
        val ok = if (dir.exists()) dir.deleteRecursively() else true
        Prefs.enabledUserPacks = Prefs.enabledUserPacks - packId
        reloadAll(ctx)
        return ok
    }

    // ---------- 内部 ----------

    private data class ParsedPack(val id: String, val name: String, val author: String, val license: String)
    private data class Parsed(val info: ParsedPack, val memes: List<MemeManager.Meme>)

    private fun parsePack(dir: File): Parsed? {
        return try {
            val jsonFile = File(dir, PACK_JSON)
            if (!jsonFile.exists()) return null
            val json = JSONObject(jsonFile.readText())
            val info = ParsedPack(
                id = json.optString("id", dir.name),
                name = json.optString("name", dir.name),
                author = json.optString("author", "未知"),
                license = json.optString("license", "user-provided"),
            )
            // 兼容 memes / items 两种字段名
            val arr = json.optJSONArray("memes") ?: json.optJSONArray("items") ?: return null
            val memes = MemeManager.parseMemes(arr).map { m ->
                // 把相对 image 路径解析成绝对路径；若已经是绝对/无图则原样
                if (m.image != null && !File(m.image).isAbsolute) {
                    m.copy(image = File(dir, m.image).absolutePath)
                } else m
            }
            Parsed(info, memes)
        } catch (e: Exception) {
            null
        }
    }

    /** 在 ZIP 解压后的临时目录里定位 pack.json。返回 (jsonFile, baseDir)。 */
    private fun locatePackJson(tmpDir: File): Pair<File, File>? {
        val rootJson = File(tmpDir, PACK_JSON)
        if (rootJson.exists()) return rootJson to tmpDir
        // 单一一级子目录
        val subs = tmpDir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
        if (subs.size == 1) {
            val subJson = File(subs[0], PACK_JSON)
            if (subJson.exists()) return subJson to subs[0]
        }
        return null
    }

    private fun unzipInto(ins: InputStream, destDir: File) {
        ZipInputStream(ins).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                // 防 Zip Slip
                val canonicalDest = destDir.canonicalPath
                if (!outFile.canonicalPath.startsWith(canonicalDest)) {
                    entry = zis.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { os -> zis.copyTo(os) }
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun countBuiltInItems(ctx: Context): Int {
        return try {
            val json = ctx.resources.openRawResource(R.raw.meme_pack).bufferedReader().use { it.readText() }
            JSONObject(json).getJSONArray("memes").length()
        } catch (e: Exception) {
            0
        }
    }
}
