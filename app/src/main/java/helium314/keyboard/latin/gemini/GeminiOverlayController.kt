package helium314.keyboard.latin.gemini

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import java.util.concurrent.Executors

class GeminiOverlayController(private val ime: InputMethodService) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val promptBuffer = StringBuilder()

    var isGeminiMode: Boolean = false
        private set

    private var isLoading: Boolean = false
    private var idleBar: LinearLayout? = null
    private var activePanel: LinearLayout? = null
    private var tvPrompt: TextView? = null
    private var suggestionStrip: View? = null

    fun attachToInputView(rootView: View) {
        idleBar = rootView.findViewById(R.id.gemini_idle_bar)
        activePanel = rootView.findViewById(R.id.gemini_active_panel)
        tvPrompt = rootView.findViewById(R.id.tv_gemini_prompt)
        suggestionStrip = rootView.findViewById(R.id.suggestion_strip_view)

        val btnOpen = rootView.findViewById<TextView>(R.id.btn_gemini_open)
        val btnTranslate = rootView.findViewById<TextView>(R.id.btn_gemini_translate)
        val btnFix = rootView.findViewById<TextView>(R.id.btn_gemini_fix)
        val btnKey = rootView.findViewById<TextView>(R.id.btn_gemini_key)
        val btnGrab = rootView.findViewById<TextView>(R.id.btn_gemini_grab)
        val btnSend = rootView.findViewById<TextView>(R.id.btn_gemini_send)
        val btnClose = rootView.findViewById<TextView>(R.id.btn_gemini_close)

        applyMaterialYouStyling(
            btnOpen, btnTranslate, btnFix, btnKey, activePanel, tvPrompt, btnGrab, btnSend, btnClose
        )

        btnOpen?.setOnClickListener { enterGeminiMode() }
        btnClose?.setOnClickListener { exitGeminiMode() }
        btnGrab?.setOnClickListener { grabContextText() }
        btnSend?.setOnClickListener { submitPrompt() }

        btnKey?.setOnClickListener { pasteApiKeyFromClipboard() }

        btnTranslate?.setOnClickListener {
            runQuickAction("Terjemahkan teks berikut ke Bahasa Inggris yang natural (jika sudah Bahasa Inggris, terjemahkan ke Bahasa Indonesia):")
        }

        btnFix?.setOnClickListener {
            runQuickAction("Perbaiki ejaan dan tata bahasa teks berikut agar rapi dan natural tanpa mengubah maknanya:")
        }
    }

    fun handleCodeInput(primaryCode: Int): Boolean {
        if (!isGeminiMode) return false

        return when {
            primaryCode == Constants.CODE_DELETE -> {
                if (!isLoading && promptBuffer.isNotEmpty()) {
                    promptBuffer.deleteCharAt(promptBuffer.length - 1)
                    refreshPromptText()
                }
                true
            }
            primaryCode == Constants.CODE_ENTER -> {
                submitPrompt()
                true
            }
            primaryCode >= Constants.CODE_SPACE -> {
                if (!isLoading) {
                    promptBuffer.append(String(Character.toChars(primaryCode)))
                    refreshPromptText()
                }
                true
            }
            else -> false // Biarkan Shift, ?123, dll. diproses HeliBoard secara normal
        }
    }

    fun handleTextInput(rawText: String?): Boolean {
        if (!isGeminiMode || rawText.isNullOrEmpty()) return false
        if (!isLoading) {
            promptBuffer.append(rawText)
            refreshPromptText()
        }
        return true
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun enterGeminiMode() {
        isGeminiMode = true
        idleBar?.visibility = View.GONE
        suggestionStrip?.visibility = View.GONE
        activePanel?.visibility = View.VISIBLE
        refreshPromptText()
    }

    private fun exitGeminiMode() {
        isGeminiMode = false
        isLoading = false
        promptBuffer.clear()
        activePanel?.visibility = View.GONE
        idleBar?.visibility = View.VISIBLE
        suggestionStrip?.visibility = View.VISIBLE
    }

    private fun refreshPromptText() {
        val tv = tvPrompt ?: return
        if (promptBuffer.isEmpty()) {
            tv.text = "Ketik pertanyaan untuk Gemini..."
            tv.alpha = 0.7f
        } else {
            tv.text = promptBuffer.toString()
            tv.alpha = 1.0f
        }
    }

    private fun grabContextText(): String {
        val ic = ime.currentInputConnection ?: return ""
        val selected = ic.getSelectedText(0)?.toString()
        val text = if (!selected.isNullOrBlank()) {
            selected
        } else {
            ic.getTextBeforeCursor(500, 0)?.toString().orEmpty()
        }

        if (text.isNotBlank()) {
            if (promptBuffer.isNotEmpty() && !promptBuffer.endsWith(" ")) {
                promptBuffer.append(" ")
            }
            promptBuffer.append("\"").append(text.trim()).append("\"")
            refreshPromptText()
        }
        return text.trim()
    }

    private fun runQuickAction(instruction: String) {
        val targetText = grabContextText()
        if (targetText.isBlank()) {
            Toast.makeText(ime, "Ketik atau blok teks di kolom chat dulu", Toast.LENGTH_SHORT).show()
            return
        }
        enterGeminiMode()
        promptBuffer.clear()
        promptBuffer.append(instruction).append("\n\n\"").append(targetText).append("\"")
        refreshPromptText()
        submitPrompt()
    }

    private fun submitPrompt() {
        if (isLoading) return
        val prompt = promptBuffer.toString().trim()
        if (prompt.isEmpty()) {
            tvPrompt?.text = "Tulis pertanyaan dulu..."
            return
        }

        val apiKey = getSavedApiKey()
        isLoading = true
        tvPrompt?.text = "✨ Gemini sedang menyusun jawaban..."

        executor.execute {
            val result = GeminiApiClient.askGemini(apiKey, prompt)
            mainHandler.post {
                isLoading = false
                result.onSuccess { reply ->
                    ime.currentInputConnection?.commitText(reply, 1)
                    exitGeminiMode()
                }.onFailure { err ->
                    tvPrompt?.text = "Gagal: ${err.message}"
                }
            }
        }
    }

    private fun pasteApiKeyFromClipboard() {
        val cm = ime.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(ime)?.toString()?.trim().orEmpty()
        if (clipText.startsWith("AIza") && clipText.length > 20) {
            ime.getSharedPreferences("gemini_heliboard", Context.MODE_PRIVATE)
                .edit()
                .putString("api_key", clipText)
                .apply()
            Toast.makeText(ime, "API Key Gemini berhasil disimpan!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                ime,
                "Salin API Key (AIza...) dulu ke clipboard, lalu ketuk ikon 🔑 lagi",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun getSavedApiKey(): String {
        return ime.getSharedPreferences("gemini_heliboard", Context.MODE_PRIVATE)
            .getString("api_key", "")
            .orEmpty()
    }

    private fun applyMaterialYouStyling(
        btnOpen: TextView?,
        btnTranslate: TextView?,
        btnFix: TextView?,
        btnKey: TextView?,
        activePanel: LinearLayout?,
        tvPrompt: TextView?,
        btnGrab: TextView?,
        btnSend: TextView?,
        btnClose: TextView?
    ) {
        val isDark = (ime.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        val accentColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.getColor(ime, android.R.color.system_accent1_600)
        } else {
            Color.parseColor("#4C8DF6")
        }

        val chipBgColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.getColor(
                ime,
                if (isDark) android.R.color.system_neutral1_800 else android.R.color.system_neutral1_100
            )
        } else {
            if (isDark) Color.parseColor("#2B2D31") else Color.parseColor("#E8EAED")
        }

        val textColor = if (isDark) Color.parseColor("#F1F3F4") else Color.parseColor("#1F1F1F")

        btnOpen?.background = createPillDrawable(chipBgColor, accentColor)
        btnOpen?.setTextColor(textColor)

        listOf(btnTranslate, btnFix, btnKey, btnGrab).forEach { view ->
            view?.background = createPillDrawable(chipBgColor, Color.TRANSPARENT)
            view?.setTextColor(textColor)
        }

        activePanel?.background = createPillDrawable(chipBgColor, accentColor)
        tvPrompt?.setTextColor(textColor)
        btnClose?.setTextColor(textColor)

        btnSend?.background = createPillDrawable(accentColor, Color.TRANSPARENT)
        btnSend?.setTextColor(Color.WHITE)
    }

    private fun createPillDrawable(fillColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 48f
            setColor(fillColor)
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(2, strokeColor)
            }
        }
    }
}
