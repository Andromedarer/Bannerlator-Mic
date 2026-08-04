package com.winlator.star.core.unpack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.winlator.star.UnpackArchiveActivity
import com.winlator.star.core.StringUtils
import java.io.File

/**
 * Foreground service that runs one archive extraction to completion, surviving the app going to
 * background for the hour-or-two a big repack takes. It drives [SevenZip.extract] on a worker
 * thread, mirrors progress into [UnpackManager] (which the Compose screen collects) and into an
 * ongoing notification with a Cancel action, and kills the 7zz process on cancel.
 *
 * Modelled on [com.winlator.star.store.download.DownloadForegroundService]; kept separate because a
 * download and an unpack can legitimately run at once and want distinct notifications.
 */
class UnpackService : Service() {

    companion object {
        private const val TAG = "UnpackService"
        private const val CHANNEL_ID = "unpack_channel"
        private const val NOTIFICATION_ID = 9003

        const val ACTION_START = "com.winlator.star.unpack.START"
        const val ACTION_CANCEL = "com.winlator.star.unpack.CANCEL"
        const val EXTRA_ARCHIVE = "archive"
        const val EXTRA_DEST = "dest"
        const val EXTRA_MMT = "mmt"
        const val EXTRA_BUFFER = "buffer"
        const val EXTRA_IS_INNO = "isInno"
        const val EXTRA_TOTAL_SIZE = "totalSize"

        // Process-static so the notification's Cancel action can reach the running process even if
        // onStartCommand hasn't re-published `instance` yet.
        @Volatile private var proc: Process? = null
        @Volatile private var cancelled = false

        fun start(ctx: Context, archive: String, dest: String, mmt: Int, bufferBytes: Int, isInno: Boolean, totalSize: Long) {
            val app = ctx.applicationContext
            val i = Intent(app, UnpackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ARCHIVE, archive)
                putExtra(EXTRA_DEST, dest)
                putExtra(EXTRA_MMT, mmt)
                putExtra(EXTRA_BUFFER, bufferBytes)
                putExtra(EXTRA_IS_INNO, isInno)
                putExtra(EXTRA_TOTAL_SIZE, totalSize)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i) else app.startService(i)
        }

        fun cancel(ctx: Context) {
            val app = ctx.applicationContext
            app.startService(Intent(app, UnpackService::class.java).apply { action = ACTION_CANCEL })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled = true
                runCatching { proc?.destroy() }
                Log.i(TAG, "Cancel requested")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val archive = intent.getStringExtra(EXTRA_ARCHIVE)
                val dest = intent.getStringExtra(EXTRA_DEST)
                val mmt = intent.getIntExtra(EXTRA_MMT, 1)
                val buffer = intent.getIntExtra(EXTRA_BUFFER, ReadBuffer.MB1.bytes)
                val isInno = intent.getBooleanExtra(EXTRA_IS_INNO, false)
                val totalSize = intent.getLongExtra(EXTRA_TOTAL_SIZE, 0L)
                if (archive == null || dest == null) { stopNow(); return START_NOT_STICKY }
                // Refuse a second concurrent job — one at a time, like the DownloadCoordinator.
                if (UnpackManager.current.isRunning) {
                    Log.w(TAG, "Unpack already running; ignoring start")
                    return START_NOT_STICKY
                }
                startForegroundCompat(buildNotification(UnpackManager.current.copy(
                    phase = UnpackPhase.LISTING, archiveName = File(archive).name,
                )))
                runExtraction(File(archive), File(dest), mmt, buffer, isInno, totalSize)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun runExtraction(archive: File, destDir: File, mmt: Int, buffer: Int, isInno: Boolean, totalSize: Long) {
        cancelled = false
        val ctx = applicationContext
        // Speed/ETA and the reported size track the DATA 7-Zip reads. For an InnoSetup installer that
        // is the Setup-*.bin payload total (passed in), not the small Setup.exe we point 7-Zip at.
        val dataSize = if (totalSize > 0) totalSize else archive.length()
        Thread {
            val startMs = SystemClock.elapsedRealtime()

            UnpackManager.set(
                UnpackState(
                    phase = UnpackPhase.LISTING,
                    archivePath = archive.absolutePath,
                    archiveName = archive.name,
                    destPath = destDir.absolutePath,
                    archiveSize = dataSize,
                    isInno = isInno,
                )
            )
            refresh()

            val info = SevenZip.list(ctx, archive)
            UnpackManager.update {
                it.copy(
                    phase = UnpackPhase.EXTRACTING,
                    archiveType = if (isInno) "InnoSetup installer" else info?.type,
                )
            }
            refresh()

            // Speed/ETA: 7-Zip reports percent, not bytes, so processed-bytes = percent/100 * archive
            // size. That is genuine read throughput of the source, smoothed with a light EMA. Honest:
            // it tracks how fast the archive is being consumed, which for one huge file is the truth.
            var lastTick = SystemClock.elapsedRealtime()
            var lastBytes = 0L
            var emaBps = 0L
            var files = 0
            val size = dataSize.coerceAtLeast(1L)

            val result = runCatching {
                SevenZip.extract(
                    ctx, archive, destDir, mmt, buffer,
                    object : SevenZip.Listener {
                        override fun onProgress(percent: Int, currentFile: String?) {
                            val now = SystemClock.elapsedRealtime()
                            val bytes = (size * percent / 100).coerceIn(0, size)
                            if (now - lastTick >= 500) {
                                val dt = (now - lastTick).coerceAtLeast(1)
                                val inst = ((bytes - lastBytes) * 1000 / dt).coerceAtLeast(0)
                                emaBps = if (emaBps == 0L) inst else (emaBps * 2 + inst) / 3
                                lastTick = now
                                lastBytes = bytes
                            }
                            val remaining = size - bytes
                            val eta = if (emaBps > 0) remaining / emaBps else -1L
                            UnpackManager.update {
                                it.copy(
                                    phase = UnpackPhase.EXTRACTING,
                                    percent = percent,
                                    currentFile = currentFile ?: it.currentFile,
                                    bytesProcessed = bytes,
                                    speedBps = emaBps,
                                    etaSeconds = eta,
                                    elapsedMs = now - startMs,
                                    filesExtracted = files,
                                )
                            }
                            refresh()
                        }

                        override fun onFile(name: String) {
                            files++
                            UnpackManager.update { it.copy(currentFile = name, filesExtracted = files) }
                        }
                    },
                    onProcess = { proc = it },
                )
            }.getOrElse { SevenZip.Result(-1, it.message ?: "exec failed") }

            proc = null
            val elapsed = SystemClock.elapsedRealtime() - startMs
            val terminal = when {
                cancelled -> UnpackState(
                    phase = UnpackPhase.CANCELLED, archivePath = archive.absolutePath,
                    archiveName = archive.name, destPath = destDir.absolutePath, elapsedMs = elapsed,
                    filesExtracted = files, archiveSize = dataSize, isInno = isInno,
                )
                result.exitCode <= 1 -> UnpackManager.current.copy(
                    phase = UnpackPhase.DONE, percent = 100, elapsedMs = elapsed,
                    filesExtracted = files, speedBps = 0, etaSeconds = 0, currentFile = null,
                )
                else -> UnpackManager.current.copy(
                    phase = UnpackPhase.ERROR, elapsedMs = elapsed, filesExtracted = files,
                    errorTail = result.stderrTail.takeIf { it.isNotBlank() } ?: "7-Zip exit code ${result.exitCode}",
                )
            }
            UnpackManager.set(terminal)
            postTerminalNotification(terminal)
            stopForeground(Service.STOP_FOREGROUND_DETACH)
            stopSelf()
        }.also { it.name = "unpack-worker"; it.start() }
    }

    // ── Notification ──

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Unpacking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows archive extraction progress and keeps it running in the background"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(s: UnpackState): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            UnpackArchiveActivity.intent(this, s.archivePath.ifEmpty { s.archiveName }),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, UnpackService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val body = when (s.phase) {
            UnpackPhase.LISTING -> "Reading ${s.archiveName}…"
            else -> buildString {
                append("${s.percent}%")
                if (s.speedBps > 0) append("  •  ${StringUtils.formatBytes(s.speedBps)}/s")
                if (s.etaSeconds >= 0) append("  •  ETA ${formatDuration(s.etaSeconds * 1000)}")
            }
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Unpacking ${s.archiveName}")
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, s.percent, s.phase == UnpackPhase.LISTING)
            .setContentIntent(tap)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }

    private fun refresh() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(UnpackManager.current))
    }

    /** Replace the ongoing notification with a dismissible terminal one (done / error / cancelled). */
    private fun postTerminalNotification(s: UnpackState) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val tap = PendingIntent.getActivity(
            this, 0,
            UnpackArchiveActivity.intent(this, s.archivePath.ifEmpty { s.archiveName }),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, text) = when (s.phase) {
            UnpackPhase.DONE -> "Unpacked ${s.archiveName}" to
                "${s.filesExtracted} files • ${StringUtils.formatBytes(s.archiveSize)} in ${formatDuration(s.elapsedMs)}"
            UnpackPhase.CANCELLED -> "Unpack cancelled" to s.archiveName
            else -> "Unpack failed" to (s.errorTail?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: s.archiveName)
        }
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun stopNow() {
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}
