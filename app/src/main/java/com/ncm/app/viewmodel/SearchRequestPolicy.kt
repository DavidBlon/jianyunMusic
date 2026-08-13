package com.ncm.app.viewmodel

internal data class SearchRequestPlan(
    val searchLocal: Boolean,
    val searchOnline: Boolean
)

/** Live typing stays local; remote plugins are queried only for an explicit submission. */
internal fun searchRequestPlan(committed: Boolean, sourceReady: Boolean): SearchRequestPlan =
    SearchRequestPlan(
        searchLocal = true,
        searchOnline = committed && sourceReady
    )
