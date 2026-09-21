package com.sdk.growthbook.evaluators

import com.sdk.growthbook.model.GBJson
import com.sdk.growthbook.model.GBNull
import com.sdk.growthbook.model.GBArray
import com.sdk.growthbook.model.GBValue
import com.sdk.growthbook.utils.GBUtils
import com.sdk.growthbook.model.GBNumber
import com.sdk.growthbook.model.GBString
import com.sdk.growthbook.model.GBBoolean

/**
 * Both experiments and features can define targeting conditions using a syntax modeled
 * after MongoDB queries. These conditions can have arbitrary nesting levels
 * and evaluating them requires recursion. There are a handful of functions to define,
 * and be aware that some of them may reference function definitions further below.
 */

/**
 * Enum For different Attribute Types supported by GrowthBook
 */
internal enum class GBAttributeType {
    /**
     * String Type Attribute
     */
    GbString {
        override fun toString(): String = "string"
    },

    /**
     * Number Type Attribute
     */
    GbNumber {
        override fun toString(): String = "number"
    },

    /**
     * Boolean Type Attribute
     */
    GbBoolean {
        override fun toString(): String = "boolean"
    },

    /**
     * Array Type Attribute
     */
    GbArray {
        override fun toString(): String = "array"
    },

    /**
     * Object Type Attribute
     */
    GbObject {
        override fun toString(): String = "object"
    },

    /**
     * Null Type Attribute
     */
    GbNull {
        override fun toString(): String = "null"
    },

    /**
     * Not Supported Type Attribute
     */
    GbUnknown {
        override fun toString(): String = "unknown"
    }
}

/**
 * Evaluator Class for Conditions
 */
internal class GBConditionEvaluator {

    /**
     * This is the main function used to evaluate a condition.
     * It loops through the condition key/value pairs and checks each entry:
     * - attributes is the user's attributes
     * - condition to be evaluated
     */
    fun evalCondition(
        attributes: Map<String, GBValue>,
        conditionObj: GBJson,
        savedGroups: Map<String, GBValue>?
    ): Boolean {
        for ((key, value) in conditionObj) {
            when (key) {
                "\$or" -> {
                    // If conditionObj has a key $or, return evalOr(attributes, condition["$or"])
                    val targetItems = conditionObj[key] as? GBArray
                    if (targetItems != null) {
                        if (!evalOr(attributes, targetItems, savedGroups)) {
                            return false
                        }
                    }
                }

                "\$nor" -> {
                    // If conditionObj has a key $nor, return !evalOr(attributes, condition["$nor"])
                    val targetItems = conditionObj[key] as? GBArray
                    if (targetItems != null) {
                        if (evalOr(attributes, targetItems, savedGroups)) {
                            return false
                        }
                    }
                }

                "\$and" -> {
                    // If conditionObj has a key $and, return !evalAnd(attributes, condition["$and"])
                    val targetItems = conditionObj[key] as? GBArray
                    if (targetItems != null) {
                        if (!evalAnd(attributes, targetItems, savedGroups)) {
                            return false
                        }
                    }
                }

                "\$not" -> {
                    // If conditionObj has a key $not, return !evalCondition(attributes, condition["$not"])
                    val targetItem = conditionObj[key] as? GBJson
                    if (targetItem != null) {
                        if (evalCondition(attributes, targetItem, savedGroups)) {
                            return false
                        }
                    }
                }

                else -> {
                    val element = getPath(attributes, key)
                    // If evalConditionValue(value, getPath(attributes, key)) is false,
                    // break out of loop and return false
                    if (!evalConditionValue(value, element, savedGroups)) {
                        return false
                    }
                }
            }
        }

        // If none of the entries failed their checks, `evalCondition` returns true
        return true
    }

    /**
     * Evaluate OR conditions against given attributes
     */
    private fun evalOr(
        attributes: Map<String, GBValue>,
        conditionObjs: GBArray,
        savedGroups: Map<String, GBValue>?
    ): Boolean {
        // If conditionObjs is empty, return true
        if (conditionObjs.isEmpty()) {
            return true
        } else {
            // Loop through the conditionObjects
            for (item in conditionObjs) {
                val gbJson = item as? GBJson ?: return false

                // If evalCondition(attributes, conditionObjs[i]) is true,
                // break out of the loop and return true
                if (evalCondition(attributes, gbJson, savedGroups)) {
                    return true
                }
            }
        }
        // Return false
        return false
    }

    /**
     * Evaluate AND conditions against given attributes
     */
    private fun evalAnd(
        attributes: Map<String, GBValue>,
        conditionObjs: GBArray,
        savedGroups: Map<String, GBValue>?
    ): Boolean {

        // Loop through the conditionObjects
        for (item in conditionObjs) {
            val gbJson = item as? GBJson ?: return false

            // If evalCondition(attributes, conditionObjs[i]) is false,
            // break out of the loop and return false
            if (!evalCondition(attributes, gbJson, savedGroups)) {
                return false
            }
        }

        // Return true
        return true
    }

    /**
     * This accepts a GBJson as input and returns true
     * if every key in the object starts with $
     */
    fun isOperatorObject(obj: GBJson): Boolean {
        var isOperator = true
        if (obj.keys.isNotEmpty()) {
            for (key in obj.keys) {
                if (!key.startsWith("$")) {
                    isOperator = false
                    break
                }
            }
        } else {
            isOperator = false
        }
        return isOperator
    }

    /**
     * This returns the data type of the passed in argument.
     */
    fun getType(obj: GBValue?): GBAttributeType {

        if (obj == GBNull) {
            return GBAttributeType.GbNull
        }

        if (obj?.isPrimitiveValue() == true) {
            return when (obj) {
                is GBString -> GBAttributeType.GbString
                is GBBoolean -> GBAttributeType.GbBoolean
                is GBNumber -> GBAttributeType.GbNumber
                else -> GBAttributeType.GbUnknown
            }
        }

        if (obj is GBArray) {
            return GBAttributeType.GbArray
        }

        if (obj is GBJson) {
            return GBAttributeType.GbObject
        }

        return GBAttributeType.GbUnknown
    }

    /**
     * Given attributes and a dot-separated path string,
     * @return the value at that path (or null if the path doesn't exist)
     */
    fun getPath(attributes: Map<String, GBValue>, key: String): GBValue {
        val paths: ArrayList<String>

        if (key.contains(".")) {
            paths = key.split(".") as ArrayList<String>
        } else {
            paths = ArrayList()
            paths.add(key)
        }

        var element: GBValue = attributes[paths[0]] ?: GBNull

        for (path in paths.subList(1, paths.size)) {
            if (element is GBJson) {
                element = element[path] ?: GBNull
            }
        }

        return element
    }

    /**
     * Evaluates Condition Value against given condition & attributes
     */
    fun evalConditionValue(conditionValue: GBValue, attributeValue: GBValue?, savedGroups: Map<String, GBValue>?, inSensitive: Boolean = false): Boolean {

        // Simple equality comparison with optional case-insensitivity
        if (inSensitive && conditionValue is GBString && attributeValue is GBString) {
            return conditionValue.value.equals(attributeValue.value, ignoreCase = true)
        }

        // If conditionValue is a string, number, boolean, return true
        // if it's "equal" to attributeValue and false if not.
        if (
            conditionValue.isPrimitiveValue() &&
            (attributeValue == null || attributeValue.isPrimitiveValue())
        ) {
            return conditionValue == attributeValue
        }

        if (conditionValue.isPrimitiveValue() && attributeValue == null) {
            return false
        }

        // If conditionValue is array, return true if it's "equal" - "equal"
        // should do a deep comparison for arrays.
        if (conditionValue is GBArray) {
            return if (attributeValue is GBArray) {
                arraysEqual(conditionValue, attributeValue)
            } else {
                false
            }
        }

        // If conditionValue is an object, loop over each key/value pair:
        if (conditionValue is GBJson) {

            if (isOperatorObject(conditionValue)) {
                for (key in conditionValue.keys) {
                    // If evalOperatorCondition(key, attributeValue, value) is false, return false
                    if (!evalOperatorCondition(
                            key,
                            attributeValue,
                            conditionValue[key]!!,
                            savedGroups
                        )
                    ) {
                        return false
                    }
                }
            } else if (attributeValue != null) {
                return conditionValue == attributeValue
            } else {
                return false
            }
        }

        // Return true
        return true
    }

    private fun arraysEqual(arr1: GBArray, arr2: GBArray): Boolean {
        if (arr1.size == arr2.size) {
            for (i in arr1.indices) {
                if (arr1[i] != arr2[i]) {
                    return false
                }
            }
        } else {
            return false
        }

        return true
    }

    /**
     * This checks if attributeValue is an array,
     * and if so at least one of the array items must match the condition
     */
    private fun elemMatch(
        attributeValue: GBValue,
        condition: GBValue,
        savedGroups: Map<String, GBValue>?
    ): Boolean {

        if (attributeValue is GBArray) {
            // Loop through items in attributeValue
            for (item in attributeValue) {
                // Skip null elements only, like the reference SDK. Falsy-but-present members
                // (0, false, "") are valid values and must still be tested against the condition
                // — guarding on truthiness instead is the defect sdk-js fixed in #6323.
                if (item is GBNull) continue

                val attributes = if (item is GBJson) {
                    HashMap(item)
                } else {
                    mapOf("value" to item)
                }

                // If isOperatorObject(condition)
                if (condition is GBJson && isOperatorObject(condition)) {
                    // If evalConditionValue(condition, item), break out of loop and return true
                    if (evalConditionValue(condition, item, savedGroups)) {
                        return true
                    }
                }
                // Else if evalCondition(item, condition), break out of loop and return true
                else if (evalCondition(
                        attributes,
                        condition as? GBJson ?: GBJson(emptyMap()),
                        savedGroups
                    )
                ) {
                    return true
                }
            }
        }

        // If attributeValue is not an array, return false
        return false
    }

    /**
     * This function is just a case statement that handles all the possible operators
     * There are basic comparison operators in the form attributeValue {op} conditionValue
     */
    fun evalOperatorCondition(
        operator: String,
        attributeValue: GBValue?,
        conditionValue: GBValue,
        savedGroups: Map<String, GBValue>?,
    ): Boolean {

        // Evaluate TYPE operator - whether both are of same type
        if (operator == "\$type") {
            val expectedType = (conditionValue as? GBString)?.value
            return getType(attributeValue).toString() == expectedType
        }

        // Evaluate NOT operator - whether condition doesn't contain attribute
        if (operator == "\$not") {
            return !evalConditionValue(conditionValue, attributeValue, savedGroups)
        }

        // Evaluate EXISTS operator - whether condition contains attribute
        if (operator == "\$exists") {
            val targetPrimitiveValue = conditionValue as? GBBoolean
            val gate2 = (attributeValue == null || attributeValue is GBNull)
            if (targetPrimitiveValue?.value == false && gate2) {
                return true
            } else {
                val gate3 = (targetPrimitiveValue?.value == true)
                val gate4 = (attributeValue != null)
                val gate5 = (attributeValue !is GBNull)
                if (gate3 && gate4 && gate5) {
                    return true
                }
            }
        }

        // Evaluate EQ / NE. Dispatched on the operator alone so the pair stays a strict negation
        // for every shape of value: reaching them only for a primitive attribute made both answer
        // false for an array or object, which cannot be right either way round.
        //
        // The reference SDK compares with `===`, which is value equality for primitives but
        // reference identity for arrays and objects. A condition and an attribute are always
        // decoded from separate JSON, so a non-primitive operand can never be identical — `$eq` is
        // false and `$ne` true regardless of the contents. That is reproduced rather than
        // "improved" into a deep comparison: a deep `$eq` would match here and not on the other
        // SDKs, and a rule that behaves differently per platform is worse than one that is
        // uniformly useless. Note plain equality (`{"tags": ["a"]}`) does compare deeply, in this
        // SDK and in the reference alike — that inconsistency is inherited, not introduced here.
        if (operator == "\$eq" || operator == "\$ne") {
            val equal = attributeValue != null &&
                attributeValue.isPrimitiveValue() &&
                conditionValue.isPrimitiveValue() &&
                attributeValue == conditionValue
            return if (operator == "\$eq") equal else !equal
        }

        // Evaluate the version operators. Dispatched on the operator alone, like the reference
        // SDK: `asVersionInput` already renders every shape of value, so there is nothing for an
        // attribute-shape branch to decide. Reaching them only for a primitive attribute made an
        // array or object attribute skip the comparison and fall through to false, where the
        // reference SDK compares it as version "0" (`$veq - array attribute is version 0`).
        val compareVersions: ((String, String) -> Boolean)? = when (operator) {
            "\$veq" -> { source, target -> source == target }
            "\$vne" -> { source, target -> source != target }
            "\$vgt" -> { source, target -> source > target }
            "\$vgte" -> { source, target -> source >= target }
            "\$vlt" -> { source, target -> source < target }
            "\$vlte" -> { source, target -> source <= target }
            else -> null
        }
        if (compareVersions != null) {
            return compareVersions(
                GBUtils.paddedVersionString(attributeValue.asVersionInput()),
                GBUtils.paddedVersionString(conditionValue.asVersionInput()),
            )
        }

        // Evaluate INGROUP / NOTINGROUP operators - whether the attribute is a member of a saved
        // group. Dispatched on the operator alone, like the reference SDK, rather than from the
        // attribute-shape branches below: `isIn` already covers an array attribute (intersection),
        // a primitive one, and an absent one. Reaching these only for a primitive attribute made
        // both operators answer false for a multi-value attribute such as `tags: ["a", "b"]`, so
        // an exclusion by saved group silently matched nobody — and so did its inclusion twin.
        if (operator == "\$inGroup" || operator == "\$notInGroup") {
            val group = savedGroups?.get(conditionValue.asKey()) as? GBArray ?: GBArray(emptyList())
            val isMember = isIn(attributeValue, group)
            return if (operator == "\$inGroup") isMember else !isMember
        }

        /// There are three operators where conditionValue is an array
        if (conditionValue is GBArray) {
            when (operator) {
                // Evaluate IN operator - attributeValue in the conditionValue array
                "\$in" -> {
                    return if (attributeValue is GBArray) {
                        isIn(attributeValue, conditionValue)
                    } else {
                        attributeValue != null && conditionValue.contains(attributeValue)
                    }
                }
                // Evaluate INI operator - attributeValue in the conditionValue array
                "\$ini" -> {
                    return isIn(attributeValue, conditionValue, true)
                }
                // Evaluate NINI operator - attributeValue in the conditionValue array
                "\$nini" -> {
                    return !isIn(attributeValue, conditionValue, true)
                }
                // Evaluate NIN operator - attributeValue not in the conditionValue array
                "\$nin" -> {
                    return if (attributeValue is GBArray) {
                        !isIn(attributeValue, conditionValue)
                    } else {
                        attributeValue == null || !conditionValue.contains(attributeValue)
                    }
                }
                // Evaluate ALL operator - whether condition contains all attribute
                "\$all" -> {
                    if (attributeValue !is GBArray) return false
                    return isInAll(attributeValue, conditionValue, savedGroups, false)
                }
                // Evaluate ALLI operator - whether condition contains all attribute
                "\$alli" -> {
                    if (attributeValue !is GBArray) return false
                    return isInAll(attributeValue, conditionValue, savedGroups, true)
                }
            }
        } else if (attributeValue is GBArray) {

            when (operator) {
                // Evaluate ElemMATCH operator - whether condition matches attribute
                "\$elemMatch" -> {
                    return elemMatch(attributeValue, conditionValue, savedGroups)
                }
                // Evaluate SIE operator - whether condition size is same as that of attribute
                "\$size" -> {
                    return evalConditionValue(
                        conditionValue,
                        GBNumber(attributeValue.size),
                        savedGroups
                    )
                }
            }
        } else if (attributeValue?.isPrimitiveValue() == true) {
            fun template(
                stringComparator: (String, String) -> Boolean,
                numberComparator: (Double, Double) -> Boolean,
            ) = comparisonTemplate(
                attributeValue, conditionValue,
                stringComparator, numberComparator
            )

            when (operator) {
                // Evaluate LT operator - whether attribute less than to condition
                "\$lt" -> {
                    return template(
                        stringComparator = { actual, expected ->
                            actual < expected
                        },
                        numberComparator = { actual, expected ->
                            actual < expected
                        },
                    )
                }
                // Evaluate LTE operator - whether attribute less than or equal to condition
                "\$lte" -> {
                    return template(
                        stringComparator = { actual, expected ->
                            actual <= expected
                        },
                        numberComparator = { actual, expected ->
                            actual <= expected
                        },
                    )
                }
                // Evaluate GT operator - whether attribute greater than to condition
                "\$gt" -> {
                    return template(
                        stringComparator = { actual, expected ->
                            actual > expected
                        },
                        numberComparator = { actual, expected ->
                            actual > expected
                        },
                    )
                }
                // Evaluate GTE operator - whether attribute greater than or equal to condition
                "\$gte" -> {
                    return template(
                        stringComparator = { actual, expected ->
                            actual >= expected
                        },
                        numberComparator = { actual, expected ->
                            actual >= expected
                        },
                    )
                }
                // Evaluate REGEX operator - whether attribute contains condition regex
                "\$regex" -> {
                    return evalRegex(pattern = conditionValue,
                        attributeValue = attributeValue.asRegexInput(),
                        ignoreCase = false,
                        negate = false
                    )
                }

                "\$regexi" -> {
                    return evalRegex(pattern = conditionValue,
                        attributeValue = attributeValue.asRegexInput(),
                        ignoreCase = true,
                        negate = false
                    )
                }

                "\$notRegex" -> {
                    return evalRegex(pattern = conditionValue,
                        attributeValue = attributeValue.asRegexInput(),
                        ignoreCase = false,
                        negate = true
                    )
                }

                "\$notRegexi" -> {
                    return evalRegex(pattern = conditionValue,
                        attributeValue = attributeValue.asRegexInput(),
                        ignoreCase = true,
                        negate = true
                    )
                }
            }
        }

        return false
    }

    private fun GBValue.asKey(): String =
        when (this) {
            is GBString -> this.value // without quotes
            else -> this.toString()
        }

    /**
     * The text a version operand contributes to a `$v*` comparison.
     *
     * Mirrors the reference SDK's coercion (`util.ts`: a number becomes its string form, and
     * anything else that is not a non-empty string becomes `"0"`). Casting the operand to
     * [GBString] instead silently turned a numeric attribute into version `"0"` and a numeric
     * condition into the empty string, so a payload that sent, say, a build number as a JSON
     * number never matched any version rule — without an error anywhere.
     *
     * An integral number renders without a fractional part (`10`, not `10.0`) because the
     * reference SDK has a single number type and `10.0 + ""` is `"10"` there; keeping the `.0`
     * would split into an extra version segment and change the comparison.
     */
    private fun GBValue?.asVersionInput(): String =
        when (this) {
            is GBString -> value.ifEmpty { "0" }
            is GBNumber -> asPlainString()
            else -> "0"
        }

    /**
     * The text a `$regex` family operand is matched against.
     *
     * The reference SDK passes the attribute straight to `RegExp.prototype.test`, which converts
     * whatever it gets into a string. Casting to [GBString] instead made every non-string
     * attribute fail the match outright, so a regex rule on an id or a build number sent as a JSON
     * number could never match.
     *
     * Numbers and booleans are converted; `null` is deliberately **not**. JavaScript would render
     * it `"null"`, which makes a pattern like `ull` match a user who does not have the attribute
     * at all — an artefact of the conversion rather than intended targeting, so it is not
     * reproduced. Array and object attributes never reach here (they are handled by the
     * attribute-shape branch above) and are likewise not stringified.
     */
    private fun GBValue?.asRegexInput(): String? =
        when (this) {
            is GBString -> value
            is GBNumber -> asPlainString()
            is GBBoolean -> value.toString()
            else -> null
        }

    /**
     * A number rendered as the reference SDK's single number type would render it: an integral
     * value carries no fractional part (`10`, not `10.0`), matching `String(10.0) === "10"`.
     *
     * Integral Kotlin types are printed as they are rather than routed through [Double]. A `Long`
     * past 2^53 cannot round-trip through a double — `1234567890123456789` comes back as
     * `1234567890123456768` — and ids of that size are ordinary (a snowflake id is 19 digits), so
     * normalising first would quietly rewrite the digits a `$regex` rule matches against.
     *
     * Only a floating-point value needs the round-trip, which is also what decides whether it is
     * integral at all. A double too large for [Long] keeps its own notation (`1.0E20` where
     * JavaScript writes `100000000000000000000`); nothing sane targets a version or a pattern at
     * such a value, and rendering it faithfully would need arbitrary-precision formatting that
     * common code does not have.
     */
    private fun GBNumber.asPlainString(): String =
        when (val number = value) {
            is Byte, is Short, is Int, is Long -> number.toString()
            else -> {
                val asDouble = number.toDouble()
                if (asDouble.isFinite() && asDouble == asDouble.toLong().toDouble()) {
                    asDouble.toLong().toString()
                } else {
                    number.toString()
                }
            }
        }

    private fun isIn(actualValue: GBValue?, conditionValue: GBArray, inSensitive: Boolean = false): Boolean {
        fun caseFold(value: GBValue?): GBValue? {
            return if (inSensitive && value is GBString) {
                GBString(value.value.lowercase())
            } else {
                value
            }
        }

        // Case-sensitive path uses GBArray.contains → HashSet membership.
        if (!inSensitive) {
            if (actualValue is GBArray) {
                if (actualValue.isEmpty()) return false
                return actualValue.any { conditionValue.contains(it) }
            }
            return actualValue != null && conditionValue.contains(actualValue)
        }

        if (actualValue is GBArray) {
            if (actualValue.isEmpty()) return false

            return actualValue.any { attr ->
                conditionValue.any { cond ->
                    caseFold(attr) == caseFold(cond)
                }
            }
        }

        return conditionValue.any {
            caseFold(actualValue) == caseFold(it)
        }
    }

    private fun isInAll(
        actual: GBValue,
        expected: GBArray,
        savedGroups: Map<String, GBValue>?,
        inSensitive: Boolean = false
    ): Boolean {
        if (actual !is GBArray) return false

        for (expectedItem in expected) {
            var passed = false

            for (actualItem in actual) {
                if (evalConditionValue(
                        expectedItem,
                        actualItem,
                        savedGroups,
                        inSensitive)) {
                    passed = true
                    break
                }
            }
            if (!passed) return false

        }
        return true
    }

    private fun comparisonTemplate(
        actualGbValue: GBValue?,
        expectedGbValue: GBValue,
        stringComparator: (String, String) -> Boolean,
        numberComparator: (Double, Double) -> Boolean,
    ): Boolean {
        val isAlphabeticComparison = actualGbValue is GBString && expectedGbValue is GBString

        return if (isAlphabeticComparison) {
            stringComparator.invoke(
                (actualGbValue as? GBString)?.value.orEmpty(),
                (expectedGbValue as? GBString)?.value.orEmpty(),
            )
        } else {
            numberComparator.invoke(
                actualGbValue?.tryRetrieveDouble() ?: 0.0,
                expectedGbValue.tryRetrieveDouble(),
            )
        }
    }

    private fun GBValue.tryRetrieveDouble(): Double =
        when (this) {
            is GBNumber -> this.value.toDouble()
            is GBString -> this.value.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }

    /**
     * The two operands are treated differently on purpose, following the reference SDK.
     *
     * [attributeValue] is data: it arrives already converted by [asRegexInput], because
     * `RegExp.prototype.test` stringifies whatever it is handed.
     *
     * [pattern] is not data — it is compiled. The reference SDK calls `String.replace` on it while
     * building the `RegExp`, so a pattern that is not a string throws there and is caught as "no
     * match". Requiring a [GBString] here reproduces that without relying on an exception.
     */
    private fun evalRegex(pattern: GBValue, attributeValue: String?, ignoreCase: Boolean, negate: Boolean): Boolean {
        val patternText = (pattern as? GBString)?.value ?: return false
        if (attributeValue == null) return false

        return try {
            val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val regex = Regex(patternText, options)
            val matches = regex.containsMatchIn(attributeValue)
            if (negate) !matches else matches
        } catch (t: Throwable) {
            false
        }
    }
}