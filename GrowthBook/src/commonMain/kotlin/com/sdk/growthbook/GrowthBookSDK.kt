package com.sdk.growthbook

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import com.sdk.growthbook.evaluators.EvaluationContext
import com.sdk.growthbook.network.NetworkDispatcher
import com.sdk.growthbook.utils.BackoffPolicy
import com.sdk.growthbook.utils.Crypto
import com.sdk.growthbook.utils.Constants
import com.sdk.growthbook.utils.GBCacheRefreshHandler
import com.sdk.growthbook.utils.GBError
import com.sdk.growthbook.utils.GBFeatures
import com.sdk.growthbook.utils.GBFetchStatsHandler
import com.sdk.growthbook.utils.GBRemoteEvalParams
import com.sdk.growthbook.utils.Resource
import com.sdk.growthbook.utils.getFeaturesFromEncryptedFeatures
import com.sdk.growthbook.evaluators.GBExperimentHelper
import com.sdk.growthbook.evaluators.GBFeatureEvaluator
import com.sdk.growthbook.evaluators.GBExperimentEvaluator
import com.sdk.growthbook.evaluators.UserContext
import com.sdk.growthbook.features.FeaturesDataModel
import com.sdk.growthbook.features.FeaturesDataSource
import com.sdk.growthbook.features.FeaturesFlowDelegate
import com.sdk.growthbook.features.FeaturesViewModel
import com.sdk.growthbook.features.FetchResult
import com.sdk.growthbook.model.GBJson
import com.sdk.growthbook.model.GBNull
import com.sdk.growthbook.model.GBArray
import com.sdk.growthbook.model.GBValue
import com.sdk.growthbook.model.GBOptions
import com.sdk.growthbook.model.GBContext
import com.sdk.growthbook.model.EvalSnapshot
import com.sdk.growthbook.model.GBExperiment
import com.sdk.growthbook.model.GBFeatureResult
import com.sdk.growthbook.model.GBExperimentResult
import com.sdk.growthbook.kotlinx.serialization.from
import com.sdk.growthbook.logger.GB
import com.sdk.growthbook.plugin.tracking.PluginRegistry
import com.sdk.growthbook.model.GBContextualBandit
import com.sdk.growthbook.model.GBFeatureRefreshEvent
import com.sdk.growthbook.model.GBFeatureRefreshSource
import com.sdk.growthbook.model.StackContext
import com.sdk.growthbook.utils.GBFeaturesChangeHandler
import com.sdk.growthbook.sandbox.CachingImpl
import com.sdk.growthbook.sandbox.GBCachingLayer
import com.sdk.growthbook.sandbox.GBCachingLayerAdapter
import com.sdk.growthbook.utils.GBUtils.Companion.refreshStickyBuckets
import com.sdk.growthbook.model.diffFeatures
import com.sdk.growthbook.utils.GBFeatureRefreshListener
import com.sdk.growthbook.utils.GBFeatureRefreshSubscription
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import kotlin.time.Duration.Companion.milliseconds

/**
 * Called the first time a user is exposed to an experiment, and the hook through which assignments
 * reach your analytics. Required at build time ([GBSDKBuilder]) because an experiment nobody
 * records is an experiment nobody can read: without it the SDK still assigns variations, but no
 * result can ever be computed.
 *
 * Deduplicated per experiment key, so it fires once per exposure rather than once per evaluation,
 * and it runs on whichever thread evaluated the feature.
 */
typealias GBTrackingCallback = (GBExperiment, GBExperimentResult) -> Unit

/**
 * Called on **every** feature evaluation, whatever the outcome — defaults and unknown features
 * included. Intended for usage analytics and debugging ("which flags does this screen read?"),
 * not for experiment exposure, which is [GBTrackingCallback]'s job.
 *
 * Runs inline on the evaluating thread: keep it cheap, and never log attribute values through it.
 */
typealias GBFeatureUsageCallback = (featureKey: String, gbFeatureResult: GBFeatureResult) -> Unit

/**
 * Called when a user's assigned variation for an experiment changes — mirrors the TypeScript SDK's
 * `subscribe()`.
 *
 * Note there is currently no public way to register one: the SDK keeps the subscription list but
 * exposes no add/remove. The alias is kept because removing it would break source compatibility;
 * do not plan around it until a registration API lands.
 */
typealias GBExperimentRunCallback = (GBExperiment, GBExperimentResult) -> Unit

/**
 * The main export of the libraries is a simple GrowthBook wrapper class
 * that takes a Context object in the constructor.
 * It exposes two main methods: feature and run.
 */
@OptIn(ExperimentalAtomicApi::class)
class GrowthBookSDK internal constructor(
    private val gbContext: GBContext,
    gbOptions: GBOptions,
    private val refreshHandler: GBCacheRefreshHandler?,
    networkDispatcher: NetworkDispatcher,
    features: GBFeatures? = null,
    savedGroups: Map<String, GBValue>? = null,
    cachingEnabled: Boolean,
    // Internal seam only: the public way to set a cache freshness window is
    // GBSDKBuilder.setCacheMaxAge(). Adding these to the public constructor would break binary
    // compatibility, so the public constructor below preserves the pre-7.3.0 signature and
    // delegates here.
    private val cacheMaxAge: Long?,
    // Opt-in background polling interval (ms) exposed via GBSDKBuilder.setRefreshInterval(); null
    // disables polling. Stored so startPolling() can launch the loop on demand. Defaulted so the
    // binary-compatible public constructor below can omit it.
    private val refreshInterval: Long? = null,
    // Inner stale-while-revalidate window (ms) from GBSDKBuilder.setStaleTtl(); forwarded to the
    // view model. Defaulted for the same binary-compatibility reason as above.
    staleTtl: Long? = null,
    // stale-if-error toggle from GBSDKBuilder.setServeStaleOnError(); forwarded to the view model.
    // When true, an expired cache (past cacheMaxAge, with staleTtl set) is served as a last resort
    // if the revalidating network round fails. Defaulted for binary-compatibility, as above.
    serveStaleOnError: Boolean = false,
    // Dispatcher on which the fetched payload is processed (sticky-bucket refresh + feature
    // application + refreshHandler invocation). Defaults to the platform IO dispatcher so that work
    // runs on a defined background context rather than an arbitrary thread. Overridable (e.g. with a
    // test dispatcher) so tests can drive the async pipeline deterministically.
    coroutineContext: CoroutineContext,
    private val featuresChangeHandler: GBFeaturesChangeHandler? = null,
    // Internal seam only: the public way to plug a cache is GBSDKBuilder.setCachingLayer().
    // Adding this to the public constructor would break binary compatibility,
    // so the public constructor below preserves the pre-7.4.0 signature and delegates here.
    cachingLayer: GBCachingLayer?,
    // Internal seam only, same reasoning: set via GBSDKBuilder.setFetchStatsHandler().
    private val fetchStatsHandler: GBFetchStatsHandler? = null,
    // Listeners registered through GBSDKBuilder.addFeatureRefreshListener(), seeded into the
    // registry below before the init block runs. That ordering is the whole point: the first cache
    // load is served synchronously from inside initialize(), so a listener attached afterwards can
    // never see it.
    initialRefreshListeners: List<GBFeatureRefreshListener> = emptyList()
    ) : FeaturesFlowDelegate, IGrowthBookSDK {

    /**
     * Public constructor, kept binary-compatible with pre-7.3.0 releases. To set a cache
     * freshness window, use [com.sdk.growthbook.GBSDKBuilder.setCacheMaxAge] instead.
     */
    constructor(
        gbContext: GBContext,
        gbOptions: GBOptions,
        refreshHandler: GBCacheRefreshHandler?,
        networkDispatcher: NetworkDispatcher,
        features: GBFeatures? = null,
        savedGroups: Map<String, GBValue>? = null,
        cachingEnabled: Boolean,
    ) : this(
        gbContext = gbContext,
        gbOptions = gbOptions,
        refreshHandler = refreshHandler,
        networkDispatcher = networkDispatcher,
        features = features,
        savedGroups = savedGroups,
        cachingEnabled = cachingEnabled,
        cacheMaxAge = null,
        coroutineContext = PlatformDependentIODispatcher,
        cachingLayer = null
    )
    private var remoteSourceFeaturesFetchResult: FeaturesFetchResult =
        FeaturesFetchResult.NoResultYet
    private val gbExperimentHelper: GBExperimentHelper = GBExperimentHelper()
    private var subscriptions: MutableList<GBExperimentRunCallback> = mutableListOf()
    private var assigned: MutableMap<String, Pair<GBExperiment, GBExperimentResult>> =
        mutableMapOf()
    // True once any usable feature payload is present. Initialized from the context so
    // bundled features seeded via setInitialFeatures() before construction count as a
    // payload — otherwise a 304 arriving before the first remote fetch would be treated
    // as a failure, breaking the offline-first fallback.
    private var hasFeaturesPayload: Boolean = gbContext.features.isNotEmpty()
    // Seeded from the builder in the property initializer, i.e. before the init block below issues
    // the first fetch — otherwise the cache load that fetch serves synchronously would be reported
    // to an empty registry.
    private val refreshListeners = AtomicReference(
        initialRefreshListeners.map { GBFeatureRefreshSubscription(it, ::removeFeatureRefreshListener) }
    )
    var pluginRegistry: PluginRegistry? = null

    /**
     * JAVA Consumers preset Features
     * SDK will not call API to fetch Features List
     */
    internal var featuresViewModel: FeaturesViewModel = FeaturesViewModel(
        delegate = this,
        dataSource = FeaturesDataSource(
            networkDispatcher, gbContext, gbOptions, onFetchStats = fetchStatsHandler
        ),
        encryptionKey = gbContext.encryptionKey,
        cachingEnabled = cachingEnabled,
        cacheMaxAge = cacheMaxAge,
        staleTtl = staleTtl,
        serveStaleOnError = serveStaleOnError,
        cachingLayer = cachingLayer?.let { GBCachingLayerAdapter(it) } ?: CachingImpl.getLayer(),
        cacheKey = "${Constants.FEATURE_CACHE}_${gbContext.apiKey}",
        remoteEval = gbContext.remoteEval,
        remoteEvalPayloadProvider = ::buildRemoteEvalParams,
        coroutineContext = coroutineContext,
    )

    init {
        pluginRegistry = PluginRegistry(gbContext.plugins)
        pluginRegistry?.initAll()
        if (features != null) {
            gbContext.features = features
            hasFeaturesPayload = true
        } else {
            if (gbContext.remoteEval) {
                refreshForRemoteEval()
            } else {
                featuresViewModel.fetchFeatures()
            }
        }
        savedGroups?.let { gbContext.savedGroups = it }
        refreshStickyBucketService()
    }

    /**
     * Manually refreshes features from the network.
     *
     * This is an explicit refresh and always bypasses the cache freshness
     * window set via [GBSDKBuilder.setCacheMaxAge]: it hits the network even
     * if the cached features are still within their max age. In remote-eval
     * mode it re-runs the remote evaluation instead.
     */
    fun refreshCache() {
        if (gbContext.remoteEval) {
            refreshForRemoteEval()
        } else {
            featuresViewModel.revalidate()
        }
    }

    /**
     * Get Context - Holding the complete data regarding cached features & attributes etc.
     */
    fun getGBContext(): GBContext {
        return gbContext
    }

    /**
     * Legacy method for enabling automatic SSE-based feature refresh.
     *
     * @deprecated Use [startAutoRefreshFeatures] instead.
     */
    @Deprecated(
        message = "Use startAutoRefreshFeatures() instead.",
        replaceWith = ReplaceWith("startAutoRefreshFeatures()"),
    )
    fun autoRefreshFeatures(): Flow<Resource<GBFeatures?>> {
        return featuresViewModel.autoRefreshFeatures()
    }

    /**
     * Starts automatic SSE-based Features updates.
     *
     * This method establishes a persistent SSE connection and emits updates
     * whenever features change on the server.
     *
     * SSE and background polling are mutually exclusive, and SSE wins: calling this stops any
     * running poller, and a later [startPolling] is a no-op while the connection is up. The
     * returned flow only mirrors what arrives — the payload is applied to this instance whether or
     * not anyone collects it, and refresh listeners / handlers fire either way. Collect it when you
     * want the updates as a stream; otherwise just call it and let the SDK keep itself current.
     *
     * The connection is torn down by [close].
     */
    fun startAutoRefreshFeatures(): Flow<Resource<GBFeatures?>> {
        return featuresViewModel.autoRefreshFeatures()
    }

    /** Fully stops the SSE connection. */
    fun stopAutoRefreshFeatures() {
        featuresViewModel.stopAutoRefresh()
    }

    /**
     * Starts background polling that revalidates features every interval configured via
     * [GBSDKBuilder.setRefreshInterval]. No-op when no interval was configured or when SSE
     * auto-refresh is already active (the two are mutually exclusive). Idempotent — a second call
     * while polling runs does nothing.
     *
     * Tie this to your app's foreground lifecycle on mobile (and call [stopPolling] when
     * backgrounded) so the poller does not keep the radio awake while the app is not visible.
     */
    fun startPolling() {
        val interval = refreshInterval
        if (interval == null) {
            if (gbContext.enableLogging) {
                GB.log("GrowthBookSDK: startPolling ignored — no refreshInterval configured")
            }
            return
        }
        val started = featuresViewModel.startPolling(interval)
        if (!started && gbContext.enableLogging) {
            GB.log("GrowthBookSDK: startPolling ignored — SSE active or polling already running")
        }
    }

    /** Stops background polling started by [startPolling]. Safe to call when not polling. */
    fun stopPolling() {
        featuresViewModel.stopPolling()
    }

    /**
     * Releases resources held by this SDK instance: flushes registered plugins (including the
     * built-in tracking plugin) so any buffered events are sent, stops any active SSE auto-refresh
     * connection or background polling, cancels the background coroutine scope used to process
     * fetched payloads, and drops every registered feature refresh listener so nothing this
     * instance held keeps an observer alive. Call this when the instance is no longer needed
     * (e.g. on logout, or before
     * creating a replacement instance) to avoid leaking coroutines and threads. Safe to call
     * multiple times. The instance must not be used after [close].
     */
    fun close() {
        pluginRegistry?.closeAll()
        featuresViewModel.close()
        clearFeatureRefreshListeners()
    }

    /**
     * Get Cached Features
     */
    fun getFeatures(): GBFeatures {
        return gbContext.features
    }

    /**
     * The setEncryptedFeatures method takes an encrypted string with an encryption key
     * and then decrypts it with the default method of decrypting
     * or with a method of decrypting from the user
     */
    fun setEncryptedFeatures(
        encryptedString: String,
        encryptionKey: String,
        subtleCrypto: Crypto?
    ) {
        val feature = getFeaturesFromEncryptedFeatures(
            encryptedString = encryptedString,
            encryptionKey = encryptionKey,
            subtleCrypto = subtleCrypto
        )
        gbContext.features =
            feature ?: return
    }

    /**
     * Delegate which inform that fetching features failed.
     *
     * The refresh handler is told only about remote failures — a failed cache read is not a
     * refresh result. Feature refresh listeners are told about both, with [isRemote] surfacing as
     * [GBFeatureRefreshSource.Network] or [GBFeatureRefreshSource.Cache]: a listener driving UI
     * needs to know the SDK is evaluating against whatever it had, whichever source let it down.
     */
    override fun featuresFetchFailed(error: GBError, isRemote: Boolean) {
        if (isRemote) {
            remoteSourceFeaturesFetchResult = FeaturesFetchResult.Failed
            invokeRefreshHandler(false, error)
        }

        notifyFeatureRefresh(
            success = false,
            source = if (isRemote) GBFeatureRefreshSource.Network else GBFeatureRefreshSource.Cache,
            features = gbContext.features,
            error = error
        )
    }

    override fun savedGroupsFetchFailed(error: GBError, isRemote: Boolean) {
        if (isRemote) {
            invokeRefreshHandler(false, error)
        }
    }

    /**
     * Applies a successfully fetched payload: every field it carries lands in the context in a
     * single atomic update, then the refresh/features-change handlers fire and, last, the feature
     * refresh listeners.
     *
     * One update rather than one per field because payload application runs on a background
     * dispatcher: a feature() call from the app thread could otherwise land mid-way and evaluate new
     * features against the previous generation's bandit definitions or saved groups.
     *
     * The listener notification is deliberately last and outside every branch below: exactly one
     * event per payload, whatever fields it carried, raised only once this instance is fully
     * updated — a listener may call straight back into the SDK.
     */
    override fun payloadFetchedSuccessfully(
        features: GBFeatures?,
        savedGroups: JsonObject?,
        contextualBandits: Map<String, GBContextualBandit>?,
        isRemote: Boolean,
        staleError: GBError?,
        fromCache: Boolean,
    ) {
        // Compute the diff only for authoritative results (network / SSE / fresh cache), against the
        // features currently applied. The non-authoritative cache pre-load that precedes a network
        // refresh must not notify, otherwise the handler double-fires on a warm start (cache, then
        // network); the subsequent authoritative result reports the real delta.
        val diff = if (isRemote && features != null) {
            featuresChangeHandler?.let { diffFeatures(gbContext.features, features) }
        } else null

        gbContext.applyPayload(
            features = features,
            savedGroups = savedGroups?.mapValues { GBValue.from(it.value) },
            contextualBandits = contextualBandits
        )

        if (features != null) {
            hasFeaturesPayload = true

            if (isRemote) {
                remoteSourceFeaturesFetchResult = FeaturesFetchResult.Success
                invokeRefreshHandler(true, null)
            }
            diff?.takeIf { it.hasChanges }?.let { featuresChangeHandler?.invoke(it) }
        }

        if (savedGroups != null && isRemote) {
            invokeRefreshHandler(true, null)
        }

        // Last, and outside both branches above: one event per payload, whichever fields it
        // carried, and only once everything it did carry is applied — a listener is free to call
        // back into the SDK. A payload without features leaves the definitions untouched, so the
        // event reports the ones still in effect.
        notifyFeatureRefresh(
            success = staleError == null,
            // fromCache, not isRemote: a cache entry inside its freshness window is served as
            // authoritative without any network call, so isRemote alone would label a disk read
            // "Network".
            source = when {
                staleError != null -> GBFeatureRefreshSource.Stale
                fromCache -> GBFeatureRefreshSource.Cache
                else -> GBFeatureRefreshSource.Network
            },
            features = features ?: gbContext.features,
            error = staleError
        )
    }

    /**
     * Binary-compatibility shim. 8.0.0 exported this delegate method with four parameters; the
     * `staleError` / `fromCache` arguments added in 8.1.0 live on the (internal) interface as
     * defaults, which produces no bridge on the class, so a consumer compiled against the old
     * signature would hit a NoSuchMethodError without this.
     */
    fun payloadFetchedSuccessfully(
        features: GBFeatures?,
        savedGroups: JsonObject?,
        contextualBandits: Map<String, GBContextualBandit>?,
        isRemote: Boolean,
    ) = payloadFetchedSuccessfully(
        features = features,
        savedGroups = savedGroups,
        contextualBandits = contextualBandits,
        isRemote = isRemote,
        staleError = null,
        fromCache = !isRemote,
    )

    /**
     * Delegate that fire refreshHandler with success = true when a 304 response occurs.
     * Only treated as success when the SDK instance has a loaded feature payload.
     * Without prior state a 304 cannot guarantee features are available
     *
     * Feature refresh listeners see the same split: [GBFeatureRefreshSource.NotModified] with
     * `success = true` for the ordinary case, and a failed [GBFeatureRefreshSource.Network] event
     * for a 304 that arrived before any payload was loaded.
     */
    override fun featuresNotModified() {
        if (!hasFeaturesPayload) {
            if (gbContext.enableLogging) {
                GB.log(
                    "GrowthBookSDK: Received 304 but no feature payload has been loaded by GrowthBook instance - treating as fetch failure so features are retried."
                )
            }

            remoteSourceFeaturesFetchResult = FeaturesFetchResult.Failed
            val featuresNotModifiedError = GBError(Exception("304 received before any feature payload was loaded"))

            invokeRefreshHandler(
                false,
                featuresNotModifiedError
            )

            // Last, as in payloadFetchedSuccessfully: a consumer wiring up both a refresh handler
            // and a listener sees them in the same order on every path.
            notifyFeatureRefresh(
                success = false,
                source = GBFeatureRefreshSource.Network,
                features = gbContext.features,
                error = featuresNotModifiedError
            )
            return
        }
        remoteSourceFeaturesFetchResult = FeaturesFetchResult.Success

        if (gbContext.enableLogging) {
            GB.log(
                "GrowthBookSDK: Features not modified (304), cached data is still valid. " +
                    "Invoking refreshHandler with success=true"
            )
        }
        invokeRefreshHandler(true, null)

        notifyFeatureRefresh(
            success = true,
            source = GBFeatureRefreshSource.NotModified,
            features = gbContext.features
        )
    }

    /**
     * The wrapper for the feature() method.
     * This method accesses a feature only if
     * features were successfully fetched from remote source.
     * If a call is in progress, it waits for the result. If network
     * call failed, it tries to call again.
     *
     * In remote-eval mode the retry goes through the remote-eval POST (see
     * [FeaturesViewModel.awaitRefresh] / [buildRemoteEvalParams]), so it never momentarily surfaces
     * non-personalized (unevaluated) feature definitions.
     *
     * @returns a [GBFeatureResult] object
     */
    override suspend fun suspendFeature(id: String): GBFeatureResult {
        val backOff = BackoffPolicy(
            initialDelayMs = INITIAL_RETRY_DELAY_MILLIS,
            maxDelayMs = MAX_RETRY_DELAY_MILLIS,
            maxAttempts = MAX_RETRY_ATTEMPTS,
        )
        var attempt = 0

        while (true) {
            when (remoteSourceFeaturesFetchResult) {
                FeaturesFetchResult.Success -> return feature(id)

                FeaturesFetchResult.NoResultYet -> {
                    delay(TIME_FOR_CALL_WAIT_MILLIS.milliseconds)
                    featuresViewModel.awaitRefresh()
                }

                FeaturesFetchResult.Failed -> {
                    if (!backOff.shouldRetry(attempt)) return feature(id)
                    // A superseded round is not a failure (nothing went wrong, its payload was just
                    // discarded by a newer generation): re-join the latest generation without burning
                    // a retry attempt or applying backoff.
                    if (featuresViewModel.awaitRefresh() == FetchResult.Superseded) continue
                    if (remoteSourceFeaturesFetchResult != FeaturesFetchResult.Failed) continue
                    val delaysMs = backOff.delayFor(attempt)
                    if (gbContext.enableLogging) {
                        GB.log("GrowthBookSDK: suspendFeature: retry attempt ${attempt + 1}/$MAX_RETRY_ATTEMPTS, waiting ${delaysMs}ms")
                    }
                    delay(delaysMs.milliseconds)
                    attempt++
                }
            }
        }
    }

    /**
     * The feature method takes a single string argument,
     * which is the unique identifier for the feature and
     * @returns a [GBFeatureResult] object
     *
     * Best-effort, synchronous read of the currently loaded state: it evaluates against whatever
     * features are in the context at call time. A remote payload is applied asynchronously (on the
     * SDK's coroutineContext, IO by default), so a call made immediately after construction — before
     * the first fetch completes — returns default/unknown values. To guarantee the fetched payload
     * (and sticky-bucket assignments) are loaded before evaluating, use [suspendFeature] instead.
     */
    override fun feature(id: String): GBFeatureResult {
        // Single atomic snapshot for every evaluation input (attributes, forced features/variations,
        // attribute overrides, sticky docs), so a concurrent setter can't yield a torn mix.
        val snapshot = gbContext.evalSnapshot()
        val evalContext = createEvaluationContext(snapshot)
        val evaluator = GBFeatureEvaluator(evalContext, snapshot.forcedFeatures)
        val result = evaluator.evaluateFeature(featureKey = id, attributeOverrides = snapshot.attributeOverrides)
        // Newly-generated sticky assignments are merged into the context per-key during evaluation
        // (see EvaluationContext.onStickyAssignmentChanged) — no whole-map write-back needed here.
        return result
    }

    /**
     * The feature method takes a string argument,
     * which is the unique identifier, and the type of the accessed feature.
     * The supported types of accessed features are:
     * [Boolean], [String], [Number], [Short],
     * [Int], [Long], [Float], [Double], [GBJson]
     *
     * @returns a feature value typed with specified type
     */
    @OptIn(ExperimentalObjCRefinement::class)
    @HiddenFromObjC
    @Deprecated("Use featureValue() instead", ReplaceWith("featureValue<V>(id)"))
    inline fun <reified V> feature(id: String): V? {
        return extractFeatureValue(id)
    }

    /**
     * The featureValue method takes a string argument,
     * which is the unique identifier, and the type of the accessed feature.
     *
     * Boolean, string and numeric values are returned unwrapped ([Boolean], [String] and the
     * concrete [Number] subtype the payload decoded to); JSON objects and arrays are returned as
     * [GBJson] and [GBArray].
     *
     * @returns the feature value typed as [V], or null if the feature has no value or its value
     * is not a [V]
     */
    inline fun <reified V> featureValue(id: String): V? {
        return extractFeatureValue(id)
    }

    /**
     * The isOn method takes a single string argument,
     * which is the unique identifier for the feature and returns the feature state on/off
     */
    override fun isOn(featureId: String): Boolean {
        return feature(id = featureId).on
    }

    /**
     * The run method takes an Experiment object and returns an ExperimentResult
     */
    override fun run(experiment: GBExperiment): GBExperimentResult {
        val snapshot = gbContext.evalSnapshot()
        val evalContext = createEvaluationContext(snapshot)
        val evaluator = GBExperimentEvaluator(
            evalContext
        )
        val result = evaluator.evaluateExperiment(
            experiment = experiment,
            attributeOverrides = snapshot.attributeOverrides
        )

        // Newly-generated sticky assignments are merged into the context per-key during evaluation
        // (see EvaluationContext.onStickyAssignmentChanged) — no whole-map write-back needed here.

        fireSubscriptions(experiment, result)
        return result
    }

    /**
     * Replaces the Map of user attributes used to assign variations.
     *
     * Sticky bucket refresh runs in the background (fire-and-forget).
     * If you use Sticky Bucketing and need to evaluate experiments immediately
     * after setting attributes, use [setAttributesSync] instead.
     */
    override fun setAttributes(attributes: Map<String, GBValue>) {
        // Single atomic update so a concurrent feature()/run() never sees the new attributes paired
        // with the previous user's stale sticky docs (the docs are repopulated by the refresh below).
        gbContext.setAttributesClearingStickyDocs(attributes)
        refreshStickyBucketService()
        refreshForRemoteEval()
    }

    /**
     * Shallow-merges [attributes] into the current user attributes (parity with the TypeScript
     * SDK's `updateAttributes`): new keys are added, existing keys are overwritten, and untouched
     * keys are preserved. To fully replace the attribute map use [setAttributes] instead.
     *
     * The merge is one level deep — nested [GBJson]/[GBArray] values are replaced wholesale, not
     * deep-merged. A key mapped to [GBNull] keeps the key with a null value (it is NOT removed);
     * remove a key by rebuilding the map with [setAttributes].
     *
     * Sticky bucket refresh runs in the background (fire-and-forget). If you use Sticky Bucketing
     * and need to evaluate experiments immediately after updating, use [updateAttributesSync].
     */
    fun updateAttributes(attributes: Map<String, GBValue>) {
        // Merge inside the context's atomic CAS loop (not read-then-setAttributes), so two concurrent
        // updateAttributes calls can't lose each other's keys. Side effects mirror setAttributes.
        gbContext.mergeAttributesClearingStickyDocs(attributes)
        refreshStickyBucketService()
        refreshForRemoteEval()
    }

    /**
     * Coroutine version of [setAttributes] that awaits sticky bucket refresh before returning.
     *
     * Note: despite the "Sync" suffix this is a suspend function — it does not block the thread.
     * Use this when you use Sticky Bucketing and need to guarantee that assignments are loaded
     * before evaluating experiments (e.g. after login or user switch).
     *
     * Example:
     * ```kotlin
     * lifecycleScope.launch {
     *     sdk.setAttributesSync(loginAttributes)
     *     val result = sdk.feature("my-experiment") // sticky buckets guaranteed
     * }
     * ```
     */
    override suspend fun setAttributesSync(attributes: Map<String, GBValue>) {
        gbContext.attributes = attributes

        if (gbContext.stickyBucketService != null) {
            refreshStickyBuckets(
                context = gbContext,
                data = null,
                attributeOverrides = gbContext.attributeOverrides
            )
        }

        refreshForRemoteEval()
    }

    /**
     * Coroutine version of [updateAttributes] that awaits sticky bucket refresh before returning.
     * Shallow-merges [attributes] into the current user attributes (see [updateAttributes] for the
     * exact merge and [GBNull] semantics).
     *
     * Note: despite the "Sync" suffix this is a suspend function — it does not block the thread.
     */
    suspend fun updateAttributesSync(attributes: Map<String, GBValue>) {
        // Atomic merge (see updateAttributes); side effects mirror setAttributesSync.
        gbContext.mergeAttributes(attributes)

        if (gbContext.stickyBucketService != null) {
            refreshStickyBuckets(
                context = gbContext,
                data = null,
                attributeOverrides = gbContext.attributeOverrides
            )
        }

        refreshForRemoteEval()
    }

    /**
     * Replaces the Map of attribute overrides used for Sticky Bucketing.
     *
     * Sticky bucket refresh runs in the background (fire-and-forget).
     * If you need to guarantee assignments are loaded before evaluating experiments,
     * use [setAttributeOverridesSync] instead.
     */
    fun setAttributeOverrides(overrides: Map<String, GBValue>) {
        gbContext.attributeOverrides = overrides
        if (gbContext.stickyBucketService != null) {
            gbContext.stickyBucketAssignmentDocs = null
            refreshStickyBucketService()
        }
        refreshForRemoteEval()
    }

    /**
     * Coroutine version of [setAttributeOverrides] that awaits sticky bucket refresh before returning.
     *
     * Note: despite the "Sync" suffix this is a suspend function — it does not block the thread.
     */
    suspend fun setAttributeOverridesSync(overrides: Map<String, GBValue>) {
        gbContext.attributeOverrides = overrides

        if (gbContext.stickyBucketService != null) {
            refreshStickyBuckets(
                context = gbContext,
                data = null,
                attributeOverrides = gbContext.attributeOverrides
            )
        }

        refreshForRemoteEval()
    }

    /**
     * The attribute overrides currently in effect, as set by [setAttributeOverrides]. These shadow
     * the corresponding entries of the context's attributes during evaluation; an empty map means
     * evaluation sees the attributes as they were set.
     *
     * A snapshot, not a view: later overrides do not show up in a map already returned.
     */
    fun getAttributeOverrides(): Map<String, GBValue> {
        return gbContext.attributeOverrides
    }

    /**
     * The feature values currently forced through [setForcedFeatures], keyed by feature id. A
     * forced value wins over every rule, which makes this the first thing to check when a feature
     * evaluates to something the rules cannot explain.
     *
     * A snapshot, not a view, as with [getAttributeOverrides].
     */
    fun getForcedFeatures(): Map<String, GBValue> = gbContext.forcedFeatures

    /**
     * Sets the Map of forced feature values. In remote evaluation mode this triggers a fresh
     * remote evaluation, since forced features are part of the remote-eval request payload
     * (see [GBRemoteEvalParams]).
     */
    fun setForcedFeatures(forcedFeatures: Map<String, GBValue>) {
        gbContext.forcedFeatures = forcedFeatures
        refreshForRemoteEval()
    }

    /**
     * The setForcedVariations method setup the Map of user's (forced) variations
     * to assign a specific variation (used for QA)
     */
    fun setForcedVariations(forcedVariations: Map<String, Number>) {
        gbContext.forcedVariations = forcedVariations
        refreshForRemoteEval()
    }

    /**
     * Called after the full API payload is received, before features are applied to context.
     * Awaits sticky bucket refresh so that context is consistent when payloadFetchedSuccessfully fires.
     */
    override suspend fun onPayloadReady(model: FeaturesDataModel) {
        try {
            refreshStickyBuckets(
                context = gbContext,
                data = model,
                attributeOverrides = gbContext.attributeOverrides
            )
        } catch (e: Exception) {
            if (gbContext.enableLogging) {
                GB.error("GrowthBook: Failed to refresh sticky buckets on payload ready: ${e.message}", e)
            }
        }
    }

    private fun refreshStickyBucketService(dataModel: FeaturesDataModel? = null) {
        gbContext.stickyBucketService?.coroutineScope?.launch {
            try {
                refreshStickyBuckets(
                    context = gbContext,
                    data = dataModel,
                    attributeOverrides = gbContext.attributeOverrides
                )
            } catch (e: Exception) {
                if (gbContext.enableLogging) {
                    GB.error("GrowthBook: Failed to refresh sticky bucket assignments: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Helper method for reified feature and featureValue.
     *
     * Delegates to the shared [extractValue] so this member and the
     * [IGrowthBookSDK.featureValue] extension can never disagree — see the note there.
     */
    @PublishedApi
    internal inline fun <reified V> extractFeatureValue(id: String): V? =
        this.feature(id).extractValue()

    /**
     * Drops every registered feature refresh listener at once, as [close] does. Use it when the
     * host is tearing down a whole screen's worth of subscriptions and holding each
     * [GBFeatureRefreshSubscription] would be busywork; prefer cancelling individual
     * subscriptions when other parts of the app may also be listening.
     */
    fun clearFeatureRefreshListeners() = mutateListeners { emptyList() }

    /**
     * Registers [listener] to be called after every feature refresh attempt — network, cache load,
     * 304 and failure alike — and returns the handle that removes it again.
     *
     * Any number of listeners can be registered, at any point in this instance's life, unlike the
     * single handler passed to [GBSDKBuilder.setRefreshHandler]. Listeners are also told about
     * definitions loaded from the cache, which the refresh handler does not report.
     *
     * ```kotlin
     * val subscription = sdk.addFeatureRefreshListener { event ->
     *     if (event.success) redrawFromFeatures(event.features)
     * }
     * // later, e.g. in onCleared()
     * subscription.cancel()
     * ```
     *
     * Listeners are invoked on the SDK's payload-processing dispatcher (the platform IO dispatcher
     * by default), so marshal back to the UI thread yourself. One that throws is logged and
     * skipped: it stops neither the other listeners nor the refresh.
     *
     * @return a subscription whose [GBFeatureRefreshSubscription.cancel] removes this registration
     *   and no other — registering the same lambda twice yields two independent subscriptions.
     * @see GBSDKBuilder.setRefreshHandler
     */
    fun addFeatureRefreshListener(listener: GBFeatureRefreshListener): GBFeatureRefreshSubscription {
        val sub = GBFeatureRefreshSubscription(listener, ::removeFeatureRefreshListener)
        mutateListeners { list -> list + sub }
        return sub
    }

    /**
     * Removes one registration, matched by identity (`!==`) rather than equality: two
     * subscriptions over the same lambda are distinct, and cancelling one must not take the other
     * with it. Dropping a subscription that is no longer in the list is a no-op, which is what
     * makes [GBFeatureRefreshSubscription.cancel] idempotent.
     */
    private fun removeFeatureRefreshListener(sub: GBFeatureRefreshSubscription) {
        mutateListeners { list -> list.filter { it !== sub } }
    }

    /**
     * Builds one [GBFeatureRefreshEvent] and hands it to every listener registered at this moment.
     *
     * Call it exactly once per refresh outcome, and only after everything that outcome changed has
     * been applied — a listener may call straight back into the SDK, and must not observe a
     * half-updated instance. The event is constructed only when someone is listening, since the
     * common case is no listeners at all.
     *
     * Iterating the snapshot taken up front (rather than the live registry) is deliberate: a
     * listener is free to add or cancel subscriptions, including its own, while being called.
     *
     * @param features the definitions this refresh applied, or the ones still in effect when it
     *   applied none (304, failure, a payload carrying only saved groups).
     */
    private fun notifyFeatureRefresh(
        success: Boolean,
        source: GBFeatureRefreshSource,
        features: GBFeatures,
        error: GBError? = null
    ) {
        val subs = refreshListeners.load()
        if (subs.isEmpty()) return
        val event = GBFeatureRefreshEvent(success, source, features, error)
        for (sub in subs) {
            try {
                sub.listener.invoke(event)
            } catch (cancellation: CancellationException) {
                // Cancellation is the caller's coroutine being torn down, not a misbehaving
                // listener: it must propagate so close()/scope cancellation keep working.
                throw cancellation
            } catch (t: Throwable) {
                // Throwable, not Exception: notification runs inside handleNetworkModel's own
                // catch(Throwable), so an Error escaping a listener (NotImplementedError from an
                // unfinished stub, a failed assertion, a JS-thrown non-Exception) would be
                // re-dispatched as a failed fetch — reporting a refresh that already applied its
                // payload as broken. Matches PluginRegistry.
                if (gbContext.enableLogging) {
                    GB.error("GrowthBook: feature refresh listener threw and was ignored: ${t.message}", t)
                }
            }
        }
    }

    /**
     * Applies [transform] to the listener registry under a compare-and-set loop, mirroring
     * [com.sdk.growthbook.model.GBContext]'s `mutate`. The list is replaced wholesale rather than
     * mutated in place, so a notification iterating an earlier snapshot is unaffected, and
     * concurrent registrations from different threads cannot clobber each other.
     */
    private fun mutateListeners(transform: (List<GBFeatureRefreshSubscription>) ->(List<GBFeatureRefreshSubscription>)) {
        while (true) {
            val current = refreshListeners.load()
            if (refreshListeners.compareAndSet(current, transform(current))) return
        }
    }

    /**
     * Builds the remote-eval request payload from the current context, or null when not in
     * remote-eval mode. Used both to kick off an eager re-evaluation ([refreshForRemoteEval]) and,
     * via a provider seam, so the coalesced retry in [FeaturesViewModel.awaitRefresh] can issue a
     * remote-eval POST with the latest context instead of a bare GET.
     */
    private fun buildRemoteEvalParams(): GBRemoteEvalParams? {
        if (!gbContext.remoteEval) {
            return null
        }
        return GBRemoteEvalParams(
            gbContext.attributes,
            gbContext.forcedFeatures, gbContext.forcedVariations
        )
    }

    /**
     * Method for sending request evaluate features remotely
     */
    private fun refreshForRemoteEval() {
        val payload = buildRemoteEvalParams() ?: return
        featuresViewModel.fetchFeatures(payload = payload)
    }

    /**
     * Invokes the consumer [refreshHandler] defensively. Fetch results are reported from the SDK's
     * background scope (and, when polling, repeatedly), so a handler that throws must never propagate:
     * that would reach the scope's uncaught-exception path (crashing the app on Android) or abort the
     * fetch pipeline mid-way. Mirrors the try/catch already guarding tracking subscriptions in
     * [fireSubscriptions].
     */
    private fun invokeRefreshHandler(isSuccess: Boolean, error: GBError?) {
        val handler = refreshHandler ?: return
        try {
            handler.invoke(isSuccess, error)
        } catch (e: Exception) {
            if (gbContext.enableLogging) {
                GB.error("GrowthBook: refreshHandler threw and was ignored: ${e.message}", e)
            }
        }
    }

    private fun fireSubscriptions(experiment: GBExperiment, experimentResult: GBExperimentResult) {
        val key = experiment.key
        // If assigned variation has changed, fire subscriptions
        val prevAssignedExperiment = this.assigned[key]
        if (prevAssignedExperiment == null
            || prevAssignedExperiment.second.inExperiment != experimentResult.inExperiment
            || prevAssignedExperiment.second.variationId != experimentResult.variationId
        ) {
            this.assigned[key] = experiment to experimentResult
        }
        for (callback in subscriptions) {
            try {
                callback.invoke(experiment, experimentResult)
            } catch (e: Exception) {
                if (gbContext.enableLogging) {
                    GB.error("Error while run subscriptions: ${e.message}", e)
                }
            }
        }
    }

    private enum class FeaturesFetchResult {
        NoResultYet, Success, Failed
    }

    private fun createEvaluationContext(snapshot: EvalSnapshot = gbContext.evalSnapshot()) =
        createEvaluationContext(gbContext, gbExperimentHelper, snapshot, pluginRegistry)

    //@ThreadLocal
    internal companion object {

        // After this period of time a call status is checked again
        private const val TIME_FOR_CALL_WAIT_MILLIS = 1000L
        private const val INITIAL_RETRY_DELAY_MILLIS = 1000L
        private const val MAX_RETRY_DELAY_MILLIS = 60_000L
        private const val MAX_RETRY_ATTEMPTS = 5

        private fun createEvaluationContext(
            gbContext: GBContext,
            gbExperimentHelper: GBExperimentHelper,
            // One atomic read of the whole shared state: features, savedGroups, attributes, forced
            // features/variations, attribute overrides and the sticky-bucket docs come from the SAME
            // snapshot, so the evaluation can never observe a torn mix (e.g. new features with stale
            // sticky docs, or new attributes with old overrides). Callers pass the snapshot they read.
            snapshot: EvalSnapshot,
            pluginRegistry: PluginRegistry?
        ): EvaluationContext {
            return EvaluationContext(
                enabled = gbContext.enabled,
                features = snapshot.features,
                savedGroups = snapshot.savedGroups,
                gbExperimentHelper = gbExperimentHelper,
                loggingEnabled = gbContext.enableLogging,
                onFeatureUsage = gbContext.onFeatureUsage,
                forcedVariations = snapshot.forcedVariations,
                trackingCallback = gbContext.trackingCallback,
                stickyBucketService = gbContext.stickyBucketService,
                userContext = UserContext(
                    qaMode = gbContext.qaMode,
                    attributes = snapshot.attributes,
                    stickyBucketAssignmentDocs = snapshot.stickyBucketAssignmentDocs,
                ),
                // Merge each newly-generated sticky assignment back into the shared context by its
                // single key, atomically — instead of writing the whole docs map back after
                // evaluation (which could clobber a concurrent background refresh).
                onStickyAssignmentChanged = { key, doc ->
                    gbContext.mergeStickyAssignmentDoc(key, doc)
                },
                stackContext = StackContext(null, mutableSetOf()),
                pluginRegistry = pluginRegistry,
                contextualBandits = snapshot.contextualBandits
            )
        }
    }
}
