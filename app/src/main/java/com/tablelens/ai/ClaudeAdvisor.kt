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
    val game: String,
    val action: String,
    val situation: String,
    val reason: String,
    val confidence: String,
)

/**
 * Sends one screenshot of any casino game to Claude and turns its answer into an [Advice].
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
            // Low effort keeps each scan to a few seconds; one table read is a short task.
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
                    ContentBlockParam.ofText("Read this casino game screen and give your recommendation."),
                )
            )
            .build()

        val response = try {
            client.messages().create(params)
        } catch (e: UnauthorizedException) {
            return problem("API key was rejected. Re-enter it in the Casino Coach app.")
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
                game = json.path("game").asText(""),
                action = json.path("action").asText("NONE").uppercase(),
                situation = json.path("situation").asText(""),
                reason = json.path("reason").asText(""),
                confidence = json.path("confidence").asText("low"),
            )
        } catch (e: Exception) {
            problem("Couldn't understand the answer. Tap SCAN again.")
        }
    }

    private fun problem(message: String) = Advice("", "ERROR", "", message, "low")

    companion object {
        const val MODEL = "claude-opus-5-5"

        private val SYSTEM_PROMPT = """
            You are Casino Coach. You read a screenshot of an online casino game, work out which game it is,
            and recommend the single best next move for the player.

            Read the rules, pay table, bet limits and buttons that are visible and use them. When a rule is not
            shown, assume the common online default. Use the mathematically best strategy for each game:
            - Blackjack: basic strategy for the rules on the felt (assume 6+ decks, dealer hits soft 17,
              double any two, double after split, no surrender if not shown). Never take insurance or even money.
            - Video poker: the optimal hold for the exact game and pay table shown. Action is HOLD plus the cards
              to keep, e.g. "HOLD K♠ K♦", or "DRAW 5" to discard all.
            - Texas Hold'em and other poker vs players: FOLD, CHECK, CALL, or BET/RAISE with a size, based on
              hand strength, position, pot odds and stack sizes shown.
            - Three Card Poker, Ultimate Texas Hold'em, Let It Ride, Caribbean Stud, Pai Gow, Casino Hold'em,
              Mississippi Stud and similar: the standard optimal strategy for that game.
            - Baccarat: bet BANKER; never Tie or side bets.
            - Roulette, craps, sic bo, keno, wheels: say which bet on screen has the lowest house edge
              (e.g. craps Pass Line plus full odds; European over American roulette) and warn off bad bets.
            - Slots and other no-skill games: no move changes the odds. Explain any choice on screen
              (bonus pick, gamble feature: say skip the gamble) and otherwise answer SPIN.

            Only use what is visible. If no decision is waiting, the cards are unreadable, or it is not the
            player's turn, answer with action NONE and say why in reason. If the best play's button is not
            available, give the best available move and mention it in reason.

            Reply with only one JSON object, no other text:
            {"game": "name of the game, e.g. Blackjack or Jacks or Better",
             "action": "the move in 1-4 words, e.g. STAND, HOLD K♠ K♦, RAISE 3x, BET BANKER, NONE",
             "situation": "what you read, e.g. You 9 + K = 19 vs dealer 9",
             "reason": "one short sentence",
             "confidence": "high" | "medium" | "low"}
        """.trimIndent()
    }
}
