package com.vistacore.launcher.ui

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.vistacore.launcher.R
import com.vistacore.launcher.data.PrefsManager

object PinDialogHelper {

    /**
     * Show a PIN entry dialog. Calls [onSuccess] if the entered PIN matches.
     */
    fun showPinDialog(
        activity: Activity,
        title: String = "Enter PIN",
        onSuccess: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val prefs = PrefsManager(activity)
        val input = createPinInput(activity)

        val dialog = AlertDialog.Builder(activity, R.style.Theme_VistaCore_Dialog)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == prefs.settingsPin) {
                    onSuccess()
                } else {
                    Toast.makeText(activity, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                    onCancel()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setCancelable(false)
            .show()

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }

        input.requestFocus()
    }

    /**
     * Two-step dialog to set a new PIN: enter, then confirm.
     */
    fun showSetPinDialog(
        activity: Activity,
        onSet: (String) -> Unit
    ) {
        val input = createPinInput(activity)

        val dialog = AlertDialog.Builder(activity, R.style.Theme_VistaCore_Dialog)
            .setTitle("Set a 4-digit PIN")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val pin = input.text.toString()
                if (pin.length != 4) {
                    Toast.makeText(activity, "PIN must be 4 digits", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                showConfirmPinDialog(activity, pin, onSet)
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }

        input.requestFocus()
    }

    private fun showConfirmPinDialog(
        activity: Activity,
        expectedPin: String,
        onSet: (String) -> Unit
    ) {
        val input = createPinInput(activity)

        val dialog = AlertDialog.Builder(activity, R.style.Theme_VistaCore_Dialog)
            .setTitle("Confirm PIN")
            .setView(input)
            .setPositiveButton("Set PIN") { _, _ ->
                if (input.text.toString() == expectedPin) {
                    onSet(expectedPin)
                    Toast.makeText(activity, "PIN set!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "PINs don't match. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }

        input.requestFocus()
    }

    private fun createPinInput(activity: Activity): EditText {
        return EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 1
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(4))
            textSize = 32f
            gravity = Gravity.CENTER
            letterSpacing = 0.5f
            hint = "----"
            setPadding(32, 24, 32, 24)
        }
    }
}
