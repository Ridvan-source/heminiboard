package helium314.keyboard.latin.gemini

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GeminiApiClient {

    @Volatile
    private var cachedModel: String? = null

    fun askGemini(apiKey: String, prompt: String): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(Exception("API Key belum diisi. Ketuk ikon 🔑 untuk menempel API Key."))
        }

        cachedModel?.let { model ->
            val res = requestContent(apiKey, model, prompt)
            if (res.isSuccess) return res
            cachedModel = null
        }

        val defaultCandidates = listOf(
            "models/gemini-3-flash-preview",
            "models/gemini-2.5-flash-lite",
            "models/gemini-2.5-flash"
        )

        var lastError = Exception("Gagal terhubung ke Gemini API.")

        for (candidate in defaultCandidates) {
            val res = requestContent(apiKey, candidate, prompt)
            if (res.isSuccess) {
                cachedModel = candidate
                return res
            }
            lastError = res.exceptionOrNull() as? Exception ?: lastError
        }

        val discovered = fetchAvailableFlashModels(apiKey)
        for (model in discovered) {
            if (model in defaultCandidates) continue
            val res = requestContent(apiKey, model, prompt)
            if (res.isSuccess) {
                cachedModel = model
                return res
            }
            lastError = res.exceptionOrNull() as? Exception ?: lastError
        }

        return Result.failure(lastError)
    }

    private fun fetchAvailableFlashModels(apiKey: String): List<String> {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
            }
            if (conn.responseCode !in 200..299) return emptyList()

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val modelsArray = JSONObject(body).optJSONArray("models") ?: return emptyList()
            val result = mutableListOf<String>()

            for (i in 0 until modelsArray.length()) {
                val item = modelsArray.getJSONObject(i)
                val name = item.optString("name", "")
                val methods = item.optJSONArray("supportedGenerationMethods") ?: continue
                var supportsGenerate = false
                for (j in 0 until methods.length()) {
                    if (methods.getString(j) == "generateContent") {
                        supportsGenerate = true
                        break
                    }
                }
                val lower = name.lowercase()
                if (supportsGenerate &&
                    lower.contains("flash") &&
                    !lower.contains("image") &&
                    !lower.contains("tts") &&
                    !lower.contains("audio") &&
                    !lower.contains("embedding")
                ) {
                    result.add(name)
                }
            }
            result.sortedDescending()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun requestContent(apiKey: String, modelName: String, prompt: String): Result<String> {
        return try {
            val normalizedModel = if (modelName.startsWith("models/")) modelName else "models/$modelName"
            val endpoint =
                "https://generativelanguage.googleapis.com/v1beta/$normalizedModel:generateContent?key=$apiKey"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connectTimeout = 15000
                readTimeout = 25000
                doOutput = true
            }

            val payload = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put(
                            "text",
                            "Kamu adalah asisten AI bawaan keyboard Android. Jawab dengan ringkas, natural, dan langsung ke inti agar siap dikirim di kolom chat tanpa basa-basi pembuka."
                        ))
                    })
                })
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                        })
                    })
                })
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(payload.toString())
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (responseCode !in 200..299) {
                val errMsg = try {
                    JSONObject(responseText).getJSONObject("error").getString("message")
                } catch (_: Exception) {
                    "HTTP Error $responseCode"
                }
                return Result.failure(Exception(errMsg))
            }

            val json = JSONObject(responseText)
            val reply = json
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            Result.success(reply)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
