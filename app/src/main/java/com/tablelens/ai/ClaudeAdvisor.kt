package com.tablelens.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration

/** What the overlay shows after one scan. */
data class Advice(
    val action: String,
    val playerHand: String,
    val dealerCard: String,
    val reason: String,
    val confidence: String,
)

/**
 * Sends one screenshot to Claude and turns its answer into an [Advice].
 * Plain Kotlin/JVM (no Android imports) so it can be compiled and tested off-device.
 */
class ClaudeAdvisor(apiKey: String) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(60))
        .build()

    private val mapper = ObjectMapper()

    fun advise(jpegBase64: String): Advice {
        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(4000L)
            // Low effort keeps each scan to a few seconds; a blackjack read is a short task.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .system(SYSTEM_PROMPT)
            .addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(
                                Base64ImageSource.builder()
                                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                    .data(jpegBase64)
                                    .build()
                            )
                            .build()
                    ),
                    ContentBlockParam.ofText("Read this blackjack screen and give your recommendation."),
                )
            )
            .build()

        val response = try {
            client.messages().create(params)
        } catch (e: UnauthorizedException) {
            return problem("API key was rejected. Re-enter it in the TableLens AI app.")
        } catch (e: RateLimitException) {
            return problem("Too many scans too fast. Wait a few seconds and tap SCAN again.")
        } catch (e: AnthropicServiceException) {
            return problem("Claude API error ${e.statusCode()}. Try again.")
        } catch (e: AnthropicIoException) {
            return problem("No connection to Claude. Check internet and try again.")
        }

        val stop = response.stopReason().orElse(null)
        if (stop == StopReason.REFUSAL) return problem("Claude declined to read this screen.")
        if (stop == StopReason.MAX_TOKENS) return problem("Answer was cut off. Tap SCAN again.")

        val text = response.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("\n")
        return parse(text)
    }

    internal fun parse(text: String): Advice {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return problem("Couldn't understand the answer. Tap SCAN again.")
        return try {
            val json = mapper.readTree(text.substring(start, end + 1))
            Advice(
                action = json.path("action").asText("NONE").uppercase(),
                playerHand = json.path("player_hand").asText(""),
                dealerCard = json.path("dealer_upcard").asText(""),
                reason = json.path("reason").asText(""),
                confidence = json.path("confidence").asText("low"),
            )
        } catch (e: Exception) {
            problem("Couldn't understand the answer. Tap SCAN again.")
        }
    }

    private fun problem(message: String) = Advice("ERROR", "", "", message, "low")

    companion object {
        const val MODEL = "claude-opus-5-5"

        private val SYSTEM_PROMPT = """
            You read screenshots of an online blackjack table and recommend the next move.

            Table rules shown on this table: dealer hits soft 17, blackjack pays 3:2, insurance pays 2:1.
            Assume 6+ decks, double on any first two cards, double after split allowed, no surrender,
            unless the screen clearly shows otherwise. Use standard basic strategy for those rules.
            Never recommend insurance.

            Only use what is visible. If no hand is in progress, the cards are unreadable, or it is not
            the player's turn, answer with action NONE and say why in reason.
            If DOUBLE or SPLIT is the best play but that button is not visible, give the best available
            move instead and mention it in reason.

            Reply with only one JSON object, no other text:
            {"action": "HIT" | "STAND" | "DOUBLE" | "SPLIT" | "NONE",
             "player_hand": "cards and total, e.g. 9 + K = 19",
             "dealer_upcard": "e.g. 9",
             "reason": "one short sentence",
             "confidence": "high" | "medium" | "low"}
        """.trimIndent()
    }
}
