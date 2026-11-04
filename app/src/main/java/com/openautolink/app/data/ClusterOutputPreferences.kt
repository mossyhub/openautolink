package com.openautolink.app.data

import com.openautolink.app.cluster.ClusterDisplayMode
import com.openautolink.app.cluster.ClusterOutputSelection

/** Durable intent, not runtime authority. Null means the previous mode is invalid/unavailable. */
data class ClusterOutputPreferences(
    val selection: ClusterOutputSelection,
    val lastEnabledClusterMode: ClusterDisplayMode?,
) {
    init { require(lastEnabledClusterMode != ClusterDisplayMode.OFF) }
}
