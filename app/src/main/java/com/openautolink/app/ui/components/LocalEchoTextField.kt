package com.openautolink.app.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * OutlinedTextField wrapper that fixes the IME-vs-DataStore typing race.
 *
 * Problem: When you bind `OutlinedTextField(value = uiState.someField, ...)`
 * to a String that's flowing in from a StateFlow backed by DataStore, every
 * keystroke does:
 *   1. user types → onValueChange runs immediately
 *   2. ViewModel.update… → DataStore write (suspends one or more frames)
 *   3. StateFlow eventually re-emits the new value
 *   4. Compose snaps the TextField back to the upstream value
 *
 * The gap between 1 and 4 means a fast typist's next keystroke fires against
 * a stale composition, so the IME's pending characters end up replayed
 * against the wrong base. Visible symptom: first char eaten, last char
 * appended at the end, cursor jumps. Affects every DataStore-backed
 * OutlinedTextField in the app.
 *
 * Fix: keep an authoritative local TextFieldValue while the field has
 * focus. Only resync from upstream when the field is NOT focused (so an
 * external write — e.g. another screen, a setting reset — still propagates
 * cleanly when the user isn't typing). The local state preserves selection
 * and cursor position too, so the IME composition stays consistent.
 *
 * Behavior parity with OutlinedTextField is otherwise unchanged.
 *
 * @param value          The upstream (DataStore-backed) value.
 * @param onValueChange  Called with the FILTERED string after each edit.
 * @param filter         Optional input filter (e.g. digits-only). Default: identity.
 */
@Composable
fun LocalEchoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    filter: (String) -> String = { it },
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    isError: Boolean = false,
    supportingText: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    var local by remember { mutableStateOf(TextFieldValue(value)) }
    var focused by remember { mutableStateOf(false) }

    // Resync from upstream ONLY while unfocused. Avoids fighting the IME.
    LaunchedEffect(value) {
        if (!focused && local.text != value) {
            local = TextFieldValue(value)
        }
    }

    OutlinedTextField(
        value = local,
        onValueChange = { input ->
            val filtered = filter(input.text)
            // Preserve the IME's cursor/selection from the incoming TextFieldValue,
            // but clamp to the filtered text length.
            val clampedSel = TextRange(
                input.selection.start.coerceAtMost(filtered.length),
                input.selection.end.coerceAtMost(filtered.length),
            )
            local = TextFieldValue(filtered, clampedSel, input.composition)
            onValueChange(filtered)
        },
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        isError = isError,
        supportingText = supportingText,
        keyboardOptions = keyboardOptions,
    )
}
