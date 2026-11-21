package dev.xiaonuoboard.app.ai

/**
 * Prompt templates for the AI 魔法画坊 3-step pipeline.
 * Subjects are described as ORIGINAL characters/themes (no third-party character likenesses).
 */
object PromptBuilder {

    enum class Difficulty(val label: String, val spec: String) {
        EASY("幼儿大色块", "very thick bold black outlines (about 10px at 1200x1600), 10 to 18 large closed regions, no background clutter, plain white background"),
        STANDARD("进阶细线", "clear bold black outlines (about 6px at 1200x1600), 20 to 40 closed regions, a cute sidekick and a simple background"),
    }

    data class Chip(val label: String, val english: String)

    val CHARACTERS = listOf(
        Chip("冰雪公主", "an original cute winter princess with a snowflake dress"),
        Chip("小狗狗", "an original cheerful cartoon puppy"),
        Chip("小猪猪", "an original happy cartoon piglet in rain boots"),
        Chip("面包小英雄", "an original friendly bakery hero made of bread loaves"),
        Chip("小恐龙", "an original cute baby dinosaur"),
        Chip("小猫咪", "an original cute kitten"),
    )

    val SCENES = listOf(
        Chip("雪地堆雪人", "building a snowman in the snow"),
        Chip("海边建城堡", "building a sandcastle at the beach"),
        Chip("生日大蛋糕", "with a big birthday cake"),
        Chip("彩虹热气球", "riding a hot air balloon under a rainbow"),
        Chip("太空火箭", "flying a little rocket in space"),
        Chip("花园野餐", "having a picnic in a flower garden"),
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

    /** Alphabet helper: A–Z themed objects (same theme words as built-in packs). */
    val ALPHABET_OBJECTS = listOf(
        "apple", "bear", "cat", "dog", "egg", "fish", "grapes", "house", "ice cream", "jellyfish", "kite", "lion",
        "moon", "nest", "octopus", "penguin", "queen's crown", "rainbow", "sun", "tree", "umbrella", "volcano",
        "whale", "xylophone", "yo-yo", "zebra",
    )
}
