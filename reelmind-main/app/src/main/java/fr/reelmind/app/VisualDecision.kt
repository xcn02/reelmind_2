package fr.reelmind.app

/** Offline heuristic: no extra API request to decide whether video frames are useful. */
object VisualDecision {
    private val visualReferences = Regex(
        """(?i)\b(regarde(?:z)?|voici|comme (?:ça|ceci)|ici|à l’écran|sur l’écran|tu vois|vous voyez|cette image|ce schéma|ce graphique|clique(?:z)?|appuie(?:z)?|ce bouton|cette étape|ce geste|démonstration|en haut|en bas)\b"""
    )

    fun needsFrames(transcription: String): Boolean {
        val text = transcription.trim()
        if (text.isBlank()) return true
        val words = Regex("""[\p{L}\p{N}']+""").findAll(text).count()
        return words < 65 || visualReferences.containsMatchIn(text)
    }
}
