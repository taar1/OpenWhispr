package com.edib.openwhispr

/** Swiss German orthography has no "ß"; it is always written "ss". Applied to
 * every piece of text we insert, whatever produced it (Whisper, the cleanup
 * model or a voice command). */
object SwissSpelling {
    fun apply(text: String): String = text.replace("ß", "ss").replace("ẞ", "SS")
}
