package app.aaps.plugins.aps.advisor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.aaps.core.interfaces.food.FoodAnalysisChecks
import app.aaps.core.interfaces.food.FoodAnalysisFailure
import app.aaps.core.interfaces.food.FoodAnalysisResult
import app.aaps.core.interfaces.food.FoodAnalyzer
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max

/**
 * Estimates a meal with the Claude API, with the API key of the AI advisor.
 *
 * Only the key is needed, as the person asked: the button is shown when a key is saved. The photos are shrunk in memory
 * and sent. They are never written to a file.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class FoodAnalyzerImpl(
    private val preferences: Preferences
) : FoodAnalyzer {

    private val client = AdvisorClient()

    override fun isAvailable(): Boolean = apiKey().isNotEmpty()

    override suspend fun analyze(description: String, photos: List<ByteArray>): FoodAnalysisResult {
        val key = apiKey()
        if (key.isEmpty()) return FoodAnalysisResult.Failure(FoodAnalysisFailure.NOT_READY)
        if (description.isBlank() && photos.isEmpty()) return FoodAnalysisResult.Failure(FoodAnalysisFailure.NO_INPUT)
        // Only as many photos as the limit allows, whatever the caller gave.
        val small = withContext(Dispatchers.Default) { photos.take(FoodAnalysisChecks.MAX_PHOTOS).map { shrink(it) } }
        if (small.any { it == null }) return FoodAnalysisResult.Failure(FoodAnalysisFailure.BAD_RESPONSE)
        val language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)
        return client.food(FoodPrompt.requestBody(description, small.filterNotNull(), language), key)
    }

    private fun apiKey(): String = preferences.get(StringKey.AdvisorApiKey).trim()

    companion object {

        /** The longest side of the photo that is sent. More pixels do not make the estimate better, only slower. */
        const val MAX_SIDE = 1_568
        private const val JPEG_QUALITY = 85

        /** The photo as a smaller JPEG, or null when it cannot be read. */
        fun shrink(jpeg: ByteArray): ByteArray? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val longest = max(bitmap.width, bitmap.height)
            val scaled = if (longest > MAX_SIDE) {
                val factor = MAX_SIDE.toDouble() / longest
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt().coerceAtLeast(1), (bitmap.height * factor).toInt().coerceAtLeast(1), true)
            } else bitmap
            return ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        }
    }
}
