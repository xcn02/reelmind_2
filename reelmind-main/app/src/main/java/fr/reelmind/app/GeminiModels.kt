package fr.reelmind.app

/** Correct only known invalid legacy identifiers, preserving user-defined model IDs. */
object GeminiModels {
    const val DEFAULT = "gemini-3.5-flash-lite"
    fun resolve(value: String?): String = when (value?.trim().orEmpty()) {
        "", "gemini-3.8-flash-lite" -> if (value.isNullOrBlank()) DEFAULT else "gemini-3.8-flash"
        "gemini-3.5-transcribe" -> DEFAULT
        else -> value!!.trim()
    }
}
