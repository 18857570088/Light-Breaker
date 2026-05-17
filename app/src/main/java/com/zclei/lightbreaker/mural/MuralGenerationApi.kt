package com.zclei.lightbreaker.mural

import kotlinx.coroutines.delay
import kotlin.math.abs

data class GeneratedMural(
    val id: String,
    val title: String,
    val theme: String,
    val prompt: String,
    val seed: Int,
)

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
