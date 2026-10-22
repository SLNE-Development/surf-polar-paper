package dev.slne.surf.polarpaper.integration

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Writes `results.json` for the Gradle task. Hand written JSON, so the test plugin needs no library for it. */
class ResultWriter(private val file: Path) {
    /** Written after every scenario, so a crash of the server still leaves the results so far. */
    fun write(results: List<ScenarioResult>, finished: Boolean) {
        val json = buildString {
            append("{\n")
            append("  \"finished\": ").append(finished).append(",\n")
            append("  \"scenarios\": [")
            results.forEachIndexed { index, result ->
                if (index > 0) append(",")
                append("\n    ").append(encode(result))
            }
            if (results.isNotEmpty()) append("\n  ")
            append("]\n}\n")
        }

        Files.createDirectories(file.parent)
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, json)
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Adds [result] to a file written by [write] earlier and marks the run as finished. */
    fun append(result: ScenarioResult) {
        val text = Files.readString(file)
        val end = text.lastIndexOf("\n  ]")
        require(end >= 0) { "$file has no scenario list to append to" }
        Files.writeString(file, text.substring(0, end) + ",\n    " + encode(result) + text.substring(end))
    }

    private fun encode(result: ScenarioResult) = buildString {
        append("{\"name\": ").append(quote(result.name))
        append(", \"result\": ").append(quote(result.outcome.name))
        append(", \"durationMillis\": ").append(result.durationMillis)
        result.message?.let { append(", \"message\": ").append(quote(it)) }
        result.stackTrace?.let { append(", \"stackTrace\": ").append(quote(it)) }
        append("}")
    }

    private fun quote(text: String) = buildString {
        append('"')
        for (char in text) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }
}
