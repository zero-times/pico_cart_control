package com.nekospeak.tts.support

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.nekospeak.tts.BuildConfig
import java.io.File

object SupportShareHelper {
    fun shareReport(context: Context, reportFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            reportFile
        )

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, "NekoSpeak Support Report")
            putExtra(Intent.EXTRA_TEXT, "Support report attached for bug diagnosis.")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(sendIntent, "Share Support Report")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun openIssue(context: Context, reportFileName: String?) {
        val uri = SupportIssueLinkBuilder.buildIssueUri(context, reportFileName)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
