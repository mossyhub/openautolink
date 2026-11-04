package com.openautolink.app.cluster

import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate

/** Builds the invisible relay template while preserving host-required structure. */
internal object ClusterRelayTemplateFactory {
    /** No guidance, loading Trip, or navigation claim; action strip is host-required. */
    fun buildMap(): NavigationTemplate = NavigationTemplate.Builder()
        .setActionStrip(ActionStrip.Builder().addAction(Action.APP_ICON).build())
        .build()

    fun build(): NavigationTemplate =
        NavigationTemplate.Builder()
            .setNavigationInfo(MessageInfo.Builder("\u200B").build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.APP_ICON)
                    .build(),
            )
            .build()
}
