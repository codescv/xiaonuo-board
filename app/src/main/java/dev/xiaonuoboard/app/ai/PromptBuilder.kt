package dev.xiaonuoboard.app.ai

import dev.xiaonuoboard.app.i18n.AppI18n
import dev.xiaonuoboard.app.i18n.AppStrings

/**
 * Prompt templates for the AI Magic Studio 3-step pipeline.
 * Subjects are described as ORIGINAL characters/themes (no third-party character likenesses).
 *
 * IMPORTANT: All model prompt strings (`Chip.english`, `Difficulty.spec`, `LINE_RULES`,
 * `COLORIZE`, `ALPHABET_OBJECTS`) MUST remain English for consistent image generation.
 */
object PromptBuilder {

    enum class Difficulty(val spec: String) {
        EASY("very thick bold black outlines (about 10px at 1200x1600), 10 to 18 large closed regions, no background clutter, plain white background"),
        STANDARD("clear bold black outlines (about 6px at 1200x1600), 20 to 40 closed regions, a cute sidekick and a simple background"),
        ;

        fun label(strings: AppStrings = AppI18n.strings()): String = strings.promptDifficultyLabel(this)
    }

    data class Chip(val id: String, val english: String) {
        fun label(strings: AppStrings = AppI18n.strings()): String =
            strings.promptCharacters[id] ?: strings.promptScenes[id] ?: id
    }

    val CHARACTERS = listOf(
        Chip("winter_princess", "an original cute winter princess with a snowflake dress"),
        Chip("puppy", "an original cheerful cartoon puppy"),
        Chip("piglet", "an original happy cartoon piglet in rain boots"),
        Chip("bread_hero", "an original friendly bakery hero made of bread loaves"),
        Chip("dinosaur", "an original cute baby dinosaur"),
        Chip("kitten", "an original cute kitten"),
    )

    val SCENES = listOf(
        Chip("snowman", "building a snowman in the snow"),
        Chip("sandcastle", "building a sandcastle at the beach"),
        Chip("birthday_cake", "with a big birthday cake"),
        Chip("hot_air_balloon", "riding a hot air balloon under a rainbow"),
        Chip("space_rocket", "flying a little rocket in space"),
        Chip("garden_picnic", "having a picnic in a flower garden"),
    )

    private const val LINE_RULES =
        "Black and white children's coloring book page, portrait 3:4. Pure black lines on pure white, " +
            "NO gray, NO shading, NO hatching, NO filled black areas, NO text. Every shape must be a fully " +
            "closed contour with no gaps so it can be paint-bucket filled."

    fun combo(character: Chip, scene: Chip, difficulty: Difficulty): String =
        "$LINE_RULES Subject: ${character.english}, ${scene.english}. Style: ${difficulty.spec}."

    fun alphabet(letter: Char, objectWord: String, difficulty: Difficulty = Difficulty.EASY): String {
        val up = letter.uppercaseChar()
        val low = letter.lowercaseChar()
        return "$LINE_RULES Subject: a big bold hollow bubble-letter uppercase '$up' and lowercase '$low' side by side, " +
            "with a cute $objectWord next to them. Style: ${difficulty.spec}."
    }

    fun freeText(description: String, difficulty: Difficulty): String =
        "$LINE_RULES Subject: ${description.trim()}. Style: ${difficulty.spec}."

    fun photoToLineArt(difficulty: Difficulty): String =
        "$LINE_RULES Convert the attached photo into a cute simplified cartoon coloring page that keeps the main " +
            "subject recognizable. Style: ${difficulty.spec}."

    const val COLORIZE =
        "Color this exact coloring page like a finished children's crayon artwork. Keep EVERY black line exactly " +
            "where it is — do not move, add, remove or thicken any line, keep the exact same composition and size. " +
            "Fill each closed region with ONE flat, bright, highly saturated color (no gradients, no texture, no " +
            "shading), suitable for a color E-Ink screen. Output the same 3:4 image."

    /** Alphabet helper: A–Z themed objects (English model prompt words, matching built-in packs). */
    val ALPHABET_OBJECTS = listOf(
        "apple", "bear", "cat", "dog", "egg", "fish", "grapes", "house", "ice cream", "jellyfish", "kite", "lion",
        "moon", "nest", "octopus", "penguin", "queen's crown", "rainbow", "sun", "tree", "umbrella", "volcano",
        "whale", "xylophone", "yo-yo", "zebra",
    )
}
