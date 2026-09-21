package com.sdk.growthbook.tests

import com.sdk.growthbook.evaluators.GBConditionEvaluator
import com.sdk.growthbook.kotlinx.serialization.from
import com.sdk.growthbook.model.GBJson
import com.sdk.growthbook.model.GBValue
import kotlinx.serialization.json.Json
import org.intellij.lang.annotations.Language
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `$regex` family against attributes that are not strings.
 *
 * The attribute used to be cast to `GBString`, so anything else failed the match outright and a
 * regex rule on an id or a build number sent as a JSON number could never fire. The reference SDK
 * hands the attribute to `RegExp.prototype.test`, which converts it first.
 *
 * Two conversions the reference SDK performs are deliberately **not** reproduced, and the tests
 * below pin that decision so it is not "corrected" later by someone diffing against `mongrule.ts`:
 *
 * - `null` would render as the text `"null"`, which makes a pattern like `ull` match a user who
 *   has no such attribute at all. That is an artefact of JavaScript's string conversion, not
 *   targeting anyone meant to express.
 * - An array would render as its joined elements (`[1, 2]` → `"1,2"`), which is equally arbitrary.
 *   Array attributes are routed to `$elemMatch` / `$size` before reaching the regex operators, so
 *   this needs no guard of its own — the test records the resulting behaviour.
 *
 * The shared spec fixtures (`cases.json`, spec 0.8.0) only ever match a string pattern against a
 * string attribute.
 */
class RegexOperatorTests {

    @Test
    fun testStringAttributeStillMatches() {
        assertTrue(eval("""{"ua": {"${'$'}regex": "Android"}}""", """{"ua": "Android Mobile"}"""))
        assertFalse(eval("""{"ua": {"${'$'}regex": "Android"}}""", """{"ua": "Chrome Desktop"}"""))
    }

    @Test
    fun testCaseSensitivityIsUnchanged() {
        assertFalse(eval("""{"ua": {"${'$'}regex": "android"}}""", """{"ua": "Android"}"""))
        assertTrue(eval("""{"ua": {"${'$'}regexi": "android"}}""", """{"ua": "Android"}"""))
    }

    @Test
    fun testNegatedVariantsAreUnchangedForStrings() {
        assertTrue(eval("""{"ua": {"${'$'}notRegex": "Android"}}""", """{"ua": "Chrome"}"""))
        assertFalse(eval("""{"ua": {"${'$'}notRegex": "Android"}}""", """{"ua": "Android"}"""))
        assertTrue(eval("""{"ua": {"${'$'}notRegexi": "android"}}""", """{"ua": "Chrome"}"""))
    }

    @Test
    fun testInvalidPatternIsNotAMatch() {
        assertFalse(eval("""{"ua": {"${'$'}regex": "("}}""", """{"ua": "anything"}"""))
    }

    @Test
    fun testNumericAttributeIsMatchedAsText() {
        assertTrue(eval("""{"id": {"${'$'}regex": "^12"}}""", """{"id": 123}"""))
        assertFalse(eval("""{"id": {"${'$'}regex": "^9"}}""", """{"id": 123}"""))
    }

    @Test
    fun testNumericAttributeAnchoredWholeMatch() {
        assertTrue(eval("""{"id": {"${'$'}regex": "^123${'$'}"}}""", """{"id": 123}"""))
        assertFalse(eval("""{"id": {"${'$'}regex": "^12${'$'}"}}""", """{"id": 123}"""))
    }

    /** Same rendering rule as the version operators: an integral value carries no `.0`. */
    @Test
    fun testIntegralDecimalRendersWithoutFraction() {
        assertTrue(eval("""{"id": {"${'$'}regex": "^10${'$'}"}}""", """{"id": 10.0}"""))
        assertFalse(eval("""{"id": {"${'$'}regex": "\\."}}""", """{"id": 10.0}"""))
    }

    @Test
    fun testNonIntegralDecimalKeepsItsFraction() {
        assertTrue(eval("""{"id": {"${'$'}regex": "^1\\.5${'$'}"}}""", """{"id": 1.5}"""))
    }

    @Test
    fun testNegativeNumberIsMatchedAsText() {
        assertTrue(eval("""{"d": {"${'$'}regex": "^-3${'$'}"}}""", """{"d": -3}"""))
    }

    /**
     * Ids beyond 2^53 must keep every digit. Normalising an integral attribute through a double
     * first loses precision — `1234567890123456789` comes back as `1234567890123456768` — which
     * would silently rewrite the text the pattern is matched against. Snowflake-style ids are
     * exactly this size, so this is ordinary input, not an edge case.
     */
    @Test
    fun testLargeIntegerIdKeepsEveryDigit() {
        assertTrue(
            eval(
                """{"id": {"${'$'}regex": "^1234567890123456789${'$'}"}}""",
                """{"id": 1234567890123456789}""",
            )
        )
        assertFalse(
            eval(
                """{"id": {"${'$'}regex": "^1234567890123456768${'$'}"}}""",
                """{"id": 1234567890123456789}""",
            )
        )
    }

    @Test
    fun testIntegerJustPastDoublePrecisionKeepsItsValue() {
        assertTrue(
            eval(
                """{"id": {"${'$'}regex": "^9007199254740993${'$'}"}}""",
                """{"id": 9007199254740993}""",
            )
        )
        assertFalse(
            eval(
                """{"id": {"${'$'}regex": "^9007199254740992${'$'}"}}""",
                """{"id": 9007199254740993}""",
            )
        )
    }

    @Test
    fun testNegatedVariantWorksForNumbers() {
        assertTrue(eval("""{"id": {"${'$'}notRegex": "^9"}}""", """{"id": 123}"""))
        assertFalse(eval("""{"id": {"${'$'}notRegex": "^12"}}""", """{"id": 123}"""))
    }

    @Test
    fun testBooleanAttributeIsMatchedAsText() {
        assertTrue(eval("""{"b": {"${'$'}regex": "^true${'$'}"}}""", """{"b": true}"""))
        assertTrue(eval("""{"b": {"${'$'}regex": "^false${'$'}"}}""", """{"b": false}"""))
        assertFalse(eval("""{"b": {"${'$'}regex": "^true${'$'}"}}""", """{"b": false}"""))
    }

    @Test
    fun testBooleanAttributeIsCaseFoldedLikeAnyText() {
        assertFalse(eval("""{"b": {"${'$'}regex": "TRUE"}}""", """{"b": true}"""))
        assertTrue(eval("""{"b": {"${'$'}regexi": "TRUE"}}""", """{"b": true}"""))
    }

    /**
     * A pattern that matches the text `"null"` must not match a user without the attribute. The
     * reference SDK answers true here; reproducing that would turn a typo into a targeting rule.
     */
    @Test
    fun testAbsentAttributeNeverMatches() {
        assertFalse(eval("""{"v": {"${'$'}regex": "ull"}}""", """{"other": "x"}"""))
        assertFalse(eval("""{"v": {"${'$'}regex": ".*"}}""", """{"other": "x"}"""))
    }

    @Test
    fun testNullAttributeNeverMatches() {
        assertFalse(eval("""{"v": {"${'$'}regex": "ull"}}""", """{"v": null}"""))
        assertFalse(eval("""{"v": {"${'$'}regex": ".*"}}""", """{"v": null}"""))
    }

    @Test
    fun testArrayAttributeIsNotStringified() {
        assertFalse(eval("""{"v": {"${'$'}regex": "^1,2${'$'}"}}""", """{"v": [1, 2]}"""))
    }

    @Test
    fun testObjectAttributeIsNotStringified() {
        assertFalse(eval("""{"v": {"${'$'}regex": "object"}}""", """{"v": {"k": "v"}}"""))
    }

    /** The pattern side stays strict: a non-string pattern is no match, as in the reference SDK. */
    @Test
    fun testNonStringPatternIsNotAMatch() {
        assertFalse(eval("""{"v": {"${'$'}regex": 12}}""", """{"v": "123"}"""))
        assertFalse(eval("""{"v": {"${'$'}regex": 12}}""", """{"v": 123}"""))
    }

    private fun eval(@Language("json") condition: String, @Language("json") attributes: String): Boolean =
        GBConditionEvaluator().evalCondition(
            (GBValue.from(Json.parseToJsonElement(attributes)) as GBJson).toMap(),
            GBValue.from(Json.parseToJsonElement(condition)) as GBJson,
            null
        )
}
