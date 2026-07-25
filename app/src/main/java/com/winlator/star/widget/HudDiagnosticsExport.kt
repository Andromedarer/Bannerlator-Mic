package com.winlator.star.widget

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-tap "Export HUD diagnostics" action, shared by both HUD config surfaces (the pre-launch
 * [com.winlator.star.ui.screens.FpsCounterConfigDialog] and the in-game XServerDrawer HUD pane).
 *
 * Runs [HudMetrics.buildDiagnosticsReport] on a background thread (it primes + samples the sysfs
 * readers, so it must never touch the main thread), writes the plain-text report to the public
 * Downloads folder, then fires an ACTION_SEND share sheet and toasts the saved path. This is a
 * manual, invoked-only action — nothing here runs on the HUD refresh path, so there is no gameplay
 * impact. The app already writes to public Downloads with the same File API elsewhere (save export,
 * community-config export) and declares MANAGE_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE, so scoped
 * storage doesn't block the write; failures are caught and reported via Toast regardless.
 */
fun exportHudDiagnostics(context: Context) {
    Toast.makeText(context, "Collecting HUD diagnostics…", Toast.LENGTH_SHORT).show()
    val main = Handler(Looper.getMainLooper())
    Thread {
        val result = runCatching {
            val report = HudMetrics(context.applicationContext).buildDiagnosticsReport(context.applicationContext)
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloads != null && !downloads.exists()) downloads.mkdirs()
            val out = File(downloads, "bannerlator-hud-diag-$ts.txt")
            out.writeText(report)
            out.setReadable(true, false)
            MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), null, null)
            out
        }
        main.post {
            result.onSuccess { out ->
                Toast.makeText(context, "Saved ${out.absolutePath}", Toast.LENGTH_LONG).show()
                runCatching {
                    val authority = context.packageName + ".tileprovider"
                    val uri = FileProvider.getUriForFile(context, authority, out)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Bannerlator HUD diagnostics")
                        putExtra(Intent.EXTRA_TEXT, "Bannerlator HUD sensor diagnostics report")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(send, "Share HUD diagnostics")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }.onFailure {
                Toast.makeText(context, "Couldn't export diagnostics.", Toast.LENGTH_SHORT).show()
            }
        }
    }.start()
}
