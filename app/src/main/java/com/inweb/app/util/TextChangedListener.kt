package com.inweb.app.util

import android.text.Editable
import android.text.TextWatcher

/**
 * One-liner TextWatcher: fires [onChange] on every text change. Perfect
 * for live-preview fields (URL preview, search box, etc.).
 *
 *   editText.addTextChangedListener(TextChangedListener { refreshPreview() })
 */
class TextChangedListener(private val onChange: () -> Unit) : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun afterTextChanged(s: Editable?) { onChange() }
}
