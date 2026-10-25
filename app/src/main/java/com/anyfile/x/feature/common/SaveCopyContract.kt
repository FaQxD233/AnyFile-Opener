package com.anyfile.x.feature.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/** System "create document" picker, prefilled with a file name and type. */
class SaveCopyContract : ActivityResultContract<SaveCopyContract.Request, Uri?>() {

    data class Request(val fileName: String, val mimeType: String)

    override fun createIntent(context: Context, input: Request): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}
