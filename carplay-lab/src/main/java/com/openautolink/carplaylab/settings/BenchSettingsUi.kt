package com.openautolink.carplaylab.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Native bench shell: calm surfaces, one scroll owner, persistent section drafts. */
class BenchSettingsUi(private val context: Context) {
    enum class Section(val title: String) { CONNECTION("Connection"), DISPLAY("Display"), AUDIO("Audio"), ADVANCED("Advanced") }

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BACKGROUND)
    }
    val sections = Section.entries.associateWith { LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(24))
    } }
    private val scroll = ScrollView(context).apply { isFillViewport = true }
    private val navigation = mutableMapOf<Section, Button>()
    private val title = text("OpenAutoLink", 28f, true)
    val status = text("CarPlay disabled · experimental tablet bench", 18f)
    val footer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setBackgroundColor(PANEL)
    }
    val outcome = text("Changes apply only when you save or connect explicitly.", 18f).apply {
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    var currentSection = Section.CONNECTION
        private set

    init {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(180), dp(12))
            setBackgroundColor(PANEL)
            addView(title)
            addView(status)
        }
        root.addView(header)
        val wide = context.resources.configuration.screenWidthDp >= 720
        val body = LinearLayout(context).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        if (wide) {
            val rail = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setBackgroundColor(PANEL)
            }
            for (section in Section.entries) {
                val item = button(section.title) { show(section) }
                navigation[section] = item
                add(rail, item)
            }
            body.addView(rail, LinearLayout.LayoutParams(dp(192), -1))
        } else {
            val selector = Spinner(context).apply {
                minimumHeight = dp(64)
                adapter = darkAdapter(Section.entries.map { it.title })
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { show(Section.entries[position]) }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            body.addView(selector, LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(scroll, if (wide) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(footer)
        footer.addView(outcome, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        show(Section.CONNECTION)
    }

    fun show(section: Section) {
        currentSection = section
        scroll.removeAllViews()
        scroll.addView(sections.getValue(section))
        scroll.scrollTo(0, 0)
        navigation.forEach { (key, item) ->
            item.isSelected = key == section
            item.setTextColor(if (key == section) ACCENT else TEXT)
            item.contentDescription = key.title + if (key == section) ", selected" else ""
        }
    }

    fun heading(section: Section, label: String, description: String) {
        add(sections.getValue(section), text(label, 24f, true))
        add(sections.getValue(section), text(description, 20f))
    }

    fun text(label: String, size: Float = 20f, emphasized: Boolean = false): TextView = TextView(context).apply {
        text = label
        textSize = size
        setTextColor(if (emphasized) TEXT else SECONDARY)
        if (emphasized) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    fun field(label: String, initial: String = "", secret: Boolean = false): EditText = EditText(context).apply {
        hint = label
        contentDescription = label
        setText(initial)
        textSize = 20f
        setTextColor(TEXT)
        setHintTextColor(SECONDARY)
        minimumHeight = dp(64)
        isSingleLine = true
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = surface()
        if (secret) {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
    }

    fun button(label: String, primary: Boolean = false, action: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = 20f
        isAllCaps = false
        minimumHeight = dp(64)
        minHeight = dp(64)
        gravity = Gravity.CENTER
        setTextColor(if (primary) BACKGROUND else TEXT)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = RippleDrawable(ColorStateList.valueOf(0x445ea3ff), surface(if (primary) ACCENT else SURFACE), null)
        setOnClickListener { action() }
    }

    fun toggle(label: String, checked: Boolean): Switch = Switch(context).apply {
        text = label
        textSize = 20f
        setTextColor(TEXT)
        minimumHeight = dp(64)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = surface()
        isChecked = checked
        thumbTintList = ColorStateList.valueOf(ACCENT)
        trackTintList = ColorStateList.valueOf(BORDER)
    }

    fun darkAdapter(labels: List<String>): ArrayAdapter<String> = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, labels) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            style(super.getView(position, convertView, parent))
        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            style(super.getDropDownView(position, convertView, parent))
        private fun style(view: View): View = view.apply {
            minimumHeight = dp(64)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(SURFACE)
            (this as? TextView)?.apply { setTextColor(TEXT); textSize = 20f }
        }
    }

    fun add(container: LinearLayout, view: View) {
        container.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    private fun surface(color: Int = SURFACE) = GradientDrawable().apply {
        // Solid native shape, never a visual gradient.
        setColor(color)
        cornerRadius = dp(4).toFloat()
        setStroke(dp(1), BORDER)
    }

    companion object {
        const val BACKGROUND = 0xff08090a.toInt()
        const val PANEL = 0xff0f1011.toInt()
        const val SURFACE = 0xff191a1b.toInt()
        const val BORDER = 0xff34343a.toInt()
        const val TEXT = 0xfff7f8f8.toInt()
        const val SECONDARY = 0xffd0d6e0.toInt()
        const val ACCENT = 0xff85b7ff.toInt()
    }
}
