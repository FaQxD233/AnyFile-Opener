package com.anyfile.x.feature.common

import android.view.View
import com.anyfile.x.databinding.ViewTaskProgressBinding

/** Drives the shared progress row (view_task_progress) of the bottom sheets. */
class TaskProgress(private val binding: ViewTaskProgressBinding) {

    private var label = ""
    private var total: Long? = null

    fun start(label: String, total: Long?) {
        this.label = label
        this.total = total?.takeIf { it > 0 }
        binding.taskIndicator.visibility = if (this.total != null) View.VISIBLE else View.GONE
        binding.taskIndicator.progress = 0
        update(0)
        binding.root.visibility = View.VISIBLE
    }

    fun update(done: Long) {
        val totalBytes = total
        if (totalBytes != null) {
            binding.taskIndicator.progress = (done * 100 / totalBytes).toInt().coerceIn(0, 100)
            binding.taskLabel.text = "$label ${formatFileSize(done)} / ${formatFileSize(totalBytes)}"
        } else {
            binding.taskLabel.text = "$label ${formatFileSize(done)}"
        }
    }

    fun finish() {
        binding.root.visibility = View.GONE
    }

    /** Progress callback that may be invoked from any thread. */
    fun listener(): (Long, Long?) -> Unit = { done, _ ->
        binding.root.post { update(done) }
    }
}
