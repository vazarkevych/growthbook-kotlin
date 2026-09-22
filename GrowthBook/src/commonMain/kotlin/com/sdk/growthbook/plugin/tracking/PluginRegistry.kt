package com.sdk.growthbook.plugin.tracking

import com.sdk.growthbook.logger.GB
import com.sdk.growthbook.model.GBExperiment
import com.sdk.growthbook.model.GBExperimentResult
import com.sdk.growthbook.model.GBFeatureResult
import com.sdk.growthbook.model.GBValue

/**
 * Holds a set of [GrowthBookPlugin]s registered with a GrowthBook instance and dispatches
 * lifecycle/event callbacks to each one.
 *
 * Dispatch is best-effort: exceptions from one plugin never propagate to the evaluator or other
 * plugins. [initAll] captures init failures so a failing plugin becomes a no-op rather than
 * aborting registration.
 */
class PluginRegistry(plugins: List<GrowthBookPlugin>?) {
    private val plugins: List<GrowthBookPlugin> =
        if (plugins.isNullOrEmpty()) emptyList() else plugins
    val isEmpty: Boolean get() = plugins.isEmpty()

    fun initAll() {
        if (plugins.isEmpty()) return

        for (plugin in plugins) {
            try {
                plugin.init()
            } catch (t: Throwable) {
                GB.warning(
                    "Plugin ${plugin::class.simpleName} init failed " +
                        "as no-op: $t"
                )
            }
        }
    }

    fun fireExperimentViewed(
        experiment: GBExperiment,
        result: GBExperimentResult,
        attributes: Map<String, GBValue>? = null
    ) {
        if (plugins.isEmpty()) return

        for (plugin in plugins) {
            try {
                plugin.onExperimentViewed(experiment, result, attributes)
            } catch (t: Throwable) {
                GB.warning(
                    "Plugin ${plugin::class.simpleName} " +
                        "onExperimentViewed failed: $t"
                )
            }
        }
    }

    fun fireFeatureEvaluated(
        featureKey: String,
        result: GBFeatureResult,
        attributes: Map<String, GBValue>? = null
    ) {
        if (plugins.isEmpty()) return

        for (plugin in plugins) {
            try {
                plugin.onFeatureEvaluated(featureKey, result, attributes)
            } catch (t: Throwable) {
                GB.warning(
                    "Plugin ${plugin::class.simpleName} " +
                        "onFeatureEvaluated failed: $t"
                )
            }
        }
    }

    /**
     * Dispatches an explicit [com.sdk.growthbook.GrowthBookSDK.logEvent] call to the registered
     * plugins that opted into [CustomEventReceiver]. Plugins that did not are skipped, so this is
     * a no-op for the ones that only care about evaluations.
     */
    fun fireCustomEvent(
        eventName: String,
        properties: Map<String, GBValue>,
        attributes: Map<String, GBValue>? = null
    ) {
        if (plugins.isEmpty()) return

        for (plugin in plugins) {
            if (plugin !is CustomEventReceiver) continue
            try {
                plugin.onEvent(eventName, properties, attributes)
            } catch (t: Throwable) {
                GB.warning(
                    "Plugin ${plugin::class.simpleName} " +
                        "onEvent failed: $t"
                )
            }
        }
    }

    /**
     * Tells the plugins that opted into [AttributesChangeReceiver] that the instance now serves a
     * different user. Plugins that did not are skipped, so this costs nothing for the ones that
     * hold no per-user state.
     */
    fun fireAttributesChanged(attributes: Map<String, GBValue>) {
        if (plugins.isEmpty()) return

        for (plugin in plugins) {
            if (plugin !is AttributesChangeReceiver) continue
            try {
                plugin.onAttributesChanged(attributes)
            } catch (t: Throwable) {
                GB.warning(
                    "Plugin ${plugin::class.simpleName} " +
                        "onAttributesChanged failed: $t"
                )
            }
        }
    }

    fun closeAll() {
        if (plugins.isEmpty()) return

        for (plugin in plugins) {
            try {
                plugin.close()
            } catch (t: Throwable) {
                GB.warning(
                    "Plugin ${plugin::class.simpleName} " +
                        "close failed: $t"
                )
            }
        }
    }
}
