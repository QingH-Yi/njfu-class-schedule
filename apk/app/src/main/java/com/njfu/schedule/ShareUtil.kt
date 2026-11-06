package com.njfu.schedule

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 导出/分享的公共动作。
 *
 * 用户点「导出 CSV」想要的是"立刻能发给别人"，所以不再走「选个文件夹保存」那条路：
 * 先把文件写进 cacheDir/exports/（FileProvider 授权），再拉系统分享面板，
 * 面板里自然就有 QQ / 微信 / 邮件 / 保存到文件 等所有选项。
 */
object ShareUtil {

    /** FileProvider 的 authority，必须和 AndroidManifest 里的 `${applicationId}.fileprovider` 一致 */
    fun authority(ctx: Context): String = "${ctx.packageName}.fileprovider"

    /**
     * 把文本写成分享用的文件，返回它的 content:// URI。
     * 每次导出前清掉同目录旧文件，避免缓存里堆一堆课表。
     */
    fun writeTextFile(ctx: Context, fileName: String, text: String): Uri {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val safe = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "schedule.csv" }
        val file = File(dir, safe)
        file.writeText(text, Charsets.UTF_8)
        return FileProvider.getUriForFile(ctx, authority(ctx), file)
    }

    /**
     * 拉起系统分享面板。
     * @param subject 邮件/部分 App 用作标题
     * @param text 一起发给对方的正文（可为空）
     */
    fun shareFile(
        ctx: Context,
        uri: Uri,
        mime: String,
        subject: String = "",
        text: String = "",
        chooserTitle: String = "分享课表到"
    ) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            if (subject.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, subject)
            if (text.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newRawUri(subject.ifEmpty { "课表" }, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 有些机型分享面板需要显式授予读权限；整体加 try 兜底，没有可分享的 App 时静默失败
        runCatching {
            ctx.startActivity(Intent.createChooser(send, chooserTitle))
        }
    }

    /** 分享纯文本（分享口令用它，对方直接粘贴到 WakeUp 里） */
    fun shareText(ctx: Context, text: String, subject: String = "", chooserTitle: String = "发到" ) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        runCatching { ctx.startActivity(Intent.createChooser(send, chooserTitle)) }
    }

    /** 复制到剪贴板并 Toast 由调用方决定 */
    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("课表", text))
    }
}
