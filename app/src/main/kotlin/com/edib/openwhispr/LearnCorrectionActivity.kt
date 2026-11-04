package com.edib.openwhispr

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** PROCESS_TEXT action "Learn correction": compares the selected (corrected)
 * text with the recent dictations and offers the misrecognized words as
 * dictionary entries, each confirmed by you before it is saved. */
class LearnCorrectionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        learnFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        learnFrom(intent)
    }

    private fun learnFrom(source: Intent?) {
        val selected = source?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        if (source?.action != Intent.ACTION_PROCESS_TEXT || selected.isBlank()) {
            finishWith(R.string.dictionary_no_text)
            return
        }
        val dictations = RecentDictations.recent()
        if (dictations.isEmpty()) {
            finishWith(R.string.learn_correction_no_dictation)
            return
        }
        val suggestions = CorrectionLearner.suggest(dictations, selected)
        if (suggestions.isEmpty()) {
            finishWith(R.string.learn_correction_nothing_found)
            return
        }

        val labels = suggestions.map { entry ->
            if (entry.aliases.isEmpty()) entry.canonical
            else getString(R.string.learn_correction_item, entry.canonical, entry.aliases.joinToString(", "))
        }.toTypedArray()
        val checked = BooleanArray(suggestions.size) { true }
        AlertDialog.Builder(this)
            .setTitle(R.string.learn_correction_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.learn_correction_add) { _, _ ->
                val chosen = suggestions.filterIndexed { index, _ -> checked[index] }
                if (chosen.isNotEmpty()) {
                    val preferences = getSharedPreferences("openwhispr", MODE_PRIVATE)
                    Dictionary.save(preferences, Dictionary.load(preferences) + chosen.map(Dictionary.Entry::asLine))
                    WhisperAccessibilityService.instance?.reloadModel()
                    Toast.makeText(this, R.string.dictionary_added, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { finish() }
            .show()
    }

    private fun finishWith(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
