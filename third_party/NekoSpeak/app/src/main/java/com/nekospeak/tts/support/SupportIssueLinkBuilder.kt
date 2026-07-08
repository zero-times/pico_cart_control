package com.nekospeak.tts.support

import android.content.Context
import android.net.Uri

object SupportIssueLinkBuilder {
    fun buildIssueUri(context: Context, reportFileName: String?): Uri {
        val metadata = SupportReportManager.collectMetadata(context)
        val title = "Bug: "
        val body = buildString {
            appendLine("## Summary")
            appendLine("Describe what happened.")
            appendLine()
            appendLine("## Steps to Reproduce")
            appendLine("1. ")
            appendLine("2. ")
            appendLine("3. ")
            appendLine()
            appendLine("## Expected Behavior")
            appendLine("What did you expect?")
            appendLine()
            appendLine("## Actual Behavior")
            appendLine("What happened instead?")
            appendLine()
            appendLine("## Attached Support Report")
            appendLine(reportFileName ?: "(attach the generated support report zip)")
            appendLine()
            appendLine("## Environment")
            appendLine("- App Version: ${metadata["app_version_name"]}")
            appendLine("- Build Type: ${metadata["build_type"]}")
            appendLine("- Device: ${metadata["manufacturer"]} ${metadata["model"]}")
            appendLine("- Android: ${metadata["android_version"]} (SDK ${metadata["sdk_int"]})")
            appendLine("- ABI: ${metadata["abis"]}")
            appendLine("- Selected Model: ${metadata["selected_model"]}")
            appendLine("- Selected Voice: ${metadata["selected_voice"]}")
        }

        return Uri.parse("https://github.com/siva-sub/NekoSpeak/issues/new").buildUpon()
            .appendQueryParameter("template", "bug_report.yml")
            .appendQueryParameter("labels", "bug,needs-triage")
            .appendQueryParameter("title", title)
            .appendQueryParameter("body", body)
            .build()
    }
}
