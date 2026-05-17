package com.zclei.lightbreaker.mural

import com.zclei.lightbreaker.network.ServerConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

data class GeneratedMural(
    val id: String,
    val title: String,
    val theme: String,
    val prompt: String,
    val seed: Int,
    val categoryId: String = "mock",
    val imageUrl: String? = null,
    val sourceUrl: String? = null,
    val license: String? = null,
)

data class MuralImageCategory(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val aliases: List<String>,
)

object MuralImageCategories {
    val all =
        listOf(
            MuralImageCategory("nature", "自然风光", "Nature Landscapes", listOf("自然", "风光", "极光", "瀑布", "海浪", "雪山", "nature")),
            MuralImageCategory("masterworks", "名画再现", "Classic Masterworks", listOf("名画", "经典", "星空", "睡莲", "呐喊", "master")),
            MuralImageCategory("city", "城市建筑", "City Architecture", listOf("城市", "建筑", "夜景", "街道", "地标", "市集", "city")),
            MuralImageCategory("abstract", "抽象艺术", "Abstract Art", listOf("抽象", "几何", "水墨", "波普", "表现", "abstract")),
        )

    fun match(
        theme: String,
        prompt: String,
    ): MuralImageCategory {
        val text = (theme + " " + prompt).lowercase()
        return all.firstOrNull { category ->
            category.aliases.any { alias -> text.contains(alias.lowercase()) }
        } ?: all[(text.hashCode() and Int.MAX_VALUE) % all.size]
    }
}

interface MuralGenerationApi {
    suspend fun generate(
        prompt: String,
        theme: String,
    ): GeneratedMural
}

class MockMuralGenerationApi : MuralGenerationApi {
    private val titles =
        listOf(
            "城市夜雨后的霓虹",
            "晨光穿过山脊",
            "深海珊瑚花园",
            "星空下的旷野",
            "森林里的微光",
            "节日广场的光带",
        )

    override suspend fun generate(
        prompt: String,
        theme: String,
    ): GeneratedMural {
        delay(700L)
        val normalizedPrompt = prompt.ifBlank { "击碎压力，揭开一幅治愈画作" }
        val seed = abs((normalizedPrompt + theme + System.currentTimeMillis().toString()).hashCode())
        return GeneratedMural(
            id = "mock-$seed",
            title = titles[seed % titles.size],
            theme = theme.ifBlank { "治愈光影" },
            prompt = normalizedPrompt,
            seed = seed,
        )
    }
}

class CloudMuralGenerationApi(
    private val manifestUrl: String = ServerConfig.IMAGE_MANIFEST_URL,
    private val fallback: MuralGenerationApi = MockMuralGenerationApi(),
) : MuralGenerationApi {
    override suspend fun generate(
        prompt: String,
        theme: String,
    ): GeneratedMural {
        val normalizedPrompt = prompt.ifBlank { "击碎压力，揭开一幅治愈画作" }
        val category = MuralImageCategories.match(theme, normalizedPrompt)
        val cloudMural =
            withContext(Dispatchers.IO) {
                runCatching { generateFromManifest(normalizedPrompt, theme, category) }.getOrNull()
            }
        return cloudMural ?: fallback.generate(normalizedPrompt, category.nameZh).copy(categoryId = category.id)
    }

    private fun generateFromManifest(
        prompt: String,
        theme: String,
        category: MuralImageCategory,
    ): GeneratedMural {
        val json = JSONObject(readText(manifestUrl))
        val images = json.getJSONArray("images")
        val candidates = mutableListOf<JSONObject>()
        for (index in 0 until images.length()) {
            val item = images.getJSONObject(index)
            if (item.optString("category") == category.id) {
                candidates += item
            }
        }
        if (candidates.isEmpty()) error("No cloud images for ${category.id}")
        val seed = abs((prompt + theme + System.currentTimeMillis().toString()).hashCode())
        val item = candidates[seed % candidates.size]
        val title = item.optString("title").ifBlank { category.nameZh }
        return GeneratedMural(
            id = item.optString("id").ifBlank { "cloud-$seed" },
            title = title,
            theme = theme.ifBlank { category.nameZh },
            prompt = prompt,
            seed = seed,
            categoryId = category.id,
            imageUrl = item.optString("url").takeIf { it.isNotBlank() },
            sourceUrl = item.optString("sourceUrl").takeIf { it.isNotBlank() },
            license = item.optString("license").takeIf { it.isNotBlank() },
        )
    }

    private fun readText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("User-Agent", "LightBreakerAndroid/1.0")
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
