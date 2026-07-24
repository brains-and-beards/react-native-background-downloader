package com.eko

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.eko.utils.StorageManager
import org.json.JSONObject
import java.io.File

class DownloadCompletionNotificationReceiver : BroadcastReceiver() {

  companion object {
    private const val COMPLETION_NOTIFICATION_CHANNEL_ID = "rnbgd_download_completed"
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
      return
    }

    val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
    if (downloadId <= 0L) {
      return
    }

    val storageManager = StorageManager(context, RNBackgroundDownloaderModuleImpl.NAME)
    val downloadIdToConfig = storageManager.loadDownloadIdToConfigMap()
    val config = downloadIdToConfig[downloadId] ?: return

    val metadata = try {
      JSONObject(config.metadata)
    } catch (_: Exception) {
      JSONObject()
    }

    if (metadata.optString("showCompletionNotification") != "true") {
      return
    }

    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val query = DownloadManager.Query().setFilterById(downloadId)
    val cursor = downloadManager.query(query)
    cursor.use {
      if (!it.moveToFirst()) {
        return
      }

      val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
      if (status != DownloadManager.STATUS_SUCCESSFUL) {
        return
      }
    }

    val pendingResult = goAsync()
    val applicationContext = context.applicationContext

    Thread {
      try {
        showCompletionNotification(applicationContext, config, metadata)
      } finally {
        pendingResult.finish()
      }
    }.start()
  }

  private fun showCompletionNotification(context: Context, config: RNBGDTaskConfig, metadata: JSONObject) {
    createCompletionNotificationChannel(context)

    val title = metadata.optString("completionNotificationTitle").ifEmpty {
      metadata.optString("title").ifEmpty { config.id }
    }
    val description = metadata.optString("completionNotificationDescription").ifEmpty {
      "Download complete"
    }

    val completionNotificationLink = metadata.optString("completionNotificationLink").ifEmpty {
      null
    }

    val thumbnailUrl = metadata.optString("completionNotificationThumbnail").ifEmpty {
      null
    }
    val largeIcon = loadThumbnailBitmap(thumbnailUrl)

    val launchIntent = completionNotificationLink
      ?.let { link ->
        Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
          `package` = context.packageName
          addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
              Intent.FLAG_ACTIVITY_SINGLE_TOP or
              Intent.FLAG_ACTIVITY_CLEAR_TOP
          )
        }.takeIf { deepLinkIntent ->
          deepLinkIntent.resolveActivity(context.packageManager) != null
        }
      }
      ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.apply {
          addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
              Intent.FLAG_ACTIVITY_SINGLE_TOP or
              Intent.FLAG_ACTIVITY_CLEAR_TOP
          )
        }

    val pendingIntent = launchIntent?.let {
      PendingIntent.getActivity(
        context,
        config.id.hashCode(),
        it,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
    }

    val notification = NotificationCompat.Builder(context, COMPLETION_NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_download_done)
      .setContentTitle(title)
      .setContentText(description)
      .apply {
        if (largeIcon != null) {
          setLargeIcon(largeIcon)
          setStyle(
            NotificationCompat.BigPictureStyle()
              .bigPicture(largeIcon)
              .bigLargeIcon(null as Bitmap?)
              .setBigContentTitle(title)
              .setSummaryText(description)
          )
        } else {
          setStyle(NotificationCompat.BigTextStyle().bigText(description))
        }
      }
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setDefaults(NotificationCompat.DEFAULT_ALL)
      .setAutoCancel(true)
      .setOngoing(false)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
      .apply {
        if (pendingIntent != null) {
          setContentIntent(pendingIntent)
        }
      }
      .build()

    NotificationManagerCompat.from(context).notify(
      (config.id.hashCode() and 0x7fffffff) + 200000,
      notification
    )

    largeIcon?.recycle()
  }

  private fun createCompletionNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return
    }

    val notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val channel = NotificationChannel(
      COMPLETION_NOTIFICATION_CHANNEL_ID,
      "Downloaded Videos",
      NotificationManager.IMPORTANCE_HIGH
    ).apply {
      description = "Notifications for completed background video downloads"
      enableVibration(true)
      setShowBadge(true)
    }

    notificationManager.createNotificationChannel(channel)
  }

  private fun loadThumbnailBitmap(thumbnailUrl: String?): Bitmap? {
    if (thumbnailUrl.isNullOrEmpty()) {
      return null
    }

    return try {
      val localPath = if (thumbnailUrl.startsWith("file://")) {
        thumbnailUrl.removePrefix("file://")
      } else {
        thumbnailUrl
      }

      loadLocalThumbnail(localPath)
    } catch (e: Exception) {
      Log.e("DSCNotification", "Failed to load notification thumbnail: ${e.message}")
      null
    }
  }

  private fun loadLocalThumbnail(path: String): Bitmap? {
    return try {
      val file = File(path)
      if (!file.exists() || !file.isFile) {
        return null
      }
      file.inputStream().use { inputStream ->
        BitmapFactory.decodeStream(inputStream)
      }
    } catch (e: Exception) {
      null
    }
  }
}
