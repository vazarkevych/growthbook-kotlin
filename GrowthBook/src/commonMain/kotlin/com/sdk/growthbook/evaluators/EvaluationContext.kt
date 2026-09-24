package com.sdk.growthbook.evaluators

import com.sdk.growthbook.model.GBValue
import com.sdk.growthbook.model.GBExperiment
import com.sdk.growthbook.model.GBExperimentResult
import com.sdk.growthbook.model.GBFeatureResult
import com.sdk.growthbook.utils.GBFeatures
import com.sdk.growthbook.GBTrackingCallback
import com.sdk.growthbook.plugin.tracking.PluginRegistry
import com.sdk.growthbook.model.GBContextualBandit
import com.sdk.growthbook.model.StackContext
import com.sdk.growthbook.model.StickyBucketAssignmentDocsType
import com.sdk.growthbook.stickybucket.GBStickyBucketService
import com.sdk.growthbook.utils.GBStickyAssignmentsDocument
import com.sdk.growthbook.utils.GBStickyAttributeKey

internal data class EvaluationContext(
    val enabled: Boolean,
    var features: GBFeatures,
    val userContext: UserContext,
    val loggingEnabled: Boolean,
    val savedGroups: Map<String, GBValue>?,
    var forcedVariations: Map<String, Any>,
    val trackingCallback: GBTrackingCallback,
    val gbExperimentHelper: GBExperimentHelper,
    val stickyBucketService: GBStickyBucketService?,
    val onFeatureUsage: ((String, GBFeatureResult) -> Unit)?,
    // Invoked for every experiment evaluated through a feature rule, whether or not the user ended up
    // in it, so GrowthBookSDK can report assignment changes to its subscribers. The reference JS SDK
    // exposes the same seam as `ctx.global.onExperimentEval` (core.ts). Deliberately NOT fired from
    // GBExperimentEvaluator itself: that evaluator also backs GrowthBookSDK.run(), which reports the
    // assignment directly, so firing there too would report every inline experiment twice.
    val onExperimentEval: ((GBExperiment, GBExperimentResult) -> Unit)? = null,
    // Invoked at the point a new sticky-bucket assignment doc is generated during evaluation, so the
    // single changed key can be merged back into the shared context atomically (see
    // GBContext.mergeStickyAssignmentDoc). Replaces the previous wholesale write-back of the whole
    // docs map after evaluation, which could clobber a concurrent background refresh.
    val onStickyAssignmentChanged: ((GBStickyAttributeKey, GBStickyAssignmentsDocument) -> Unit)? = null,
    val stackContext: StackContext,
    val pluginRegistry: PluginRegistry?,
    val contextualBandits: Map<String, GBContextualBandit>? = null
)

internal data class UserContext(
    val qaMode: Boolean,
    internal val attributes: Map<String, GBValue>,
    internal var stickyBucketAssignmentDocs: StickyBucketAssignmentDocsType?,
)
