package io.github.hyperisland.xposed.template.core.customization

import io.github.hyperisland.xposed.template.core.contracts.IslandTemplate
import org.json.JSONObject

object IslandCustomizationFieldKeys {
    const val leftExpr = "island_left_expr"
    const val rightExpr = "island_right_expr"
    const val aodTitle = "aodTitle"
    const val aodPic = "aodPic"
}

object IslandCustomizationFieldRegistry {
    const val SLOT_ISLAND_LEFT = "island_left"
    const val SLOT_ISLAND_RIGHT = "island_right"

    private data class IslandFieldSpec(
        val slot: String,
        val key: String,
        val label: String,
        val defaultProvider: (IslandTemplate?) -> String,
    ) {
        fun toSchemaField(template: IslandTemplate?): Map<String, Any?> = mapOf(
            "key" to key,
            "label" to label,
            "type" to "text_expr",
            "defaultValue" to defaultProvider(template),
            "required" to true,
        )
    }

    private val specs = listOf(
        IslandFieldSpec(
            slot = SLOT_ISLAND_LEFT,
            key = IslandCustomizationFieldKeys.leftExpr,
            label = IslandCustomizationFieldKeys.leftExpr,
            defaultProvider = { it?.defaultIslandLeftExpr ?: "${'$'}{left_title}" },
        ),
        IslandFieldSpec(
            slot = SLOT_ISLAND_RIGHT,
            key = IslandCustomizationFieldKeys.rightExpr,
            label = IslandCustomizationFieldKeys.rightExpr,
            defaultProvider = { it?.defaultIslandRightExpr ?: "${'$'}{right_title}" },
        ),
    )

    fun slots(): List<String> = specs.map { it.slot }

    fun schemaFields(template: IslandTemplate?): List<Map<String, Any?>> =
        specs.map { it.toSchemaField(template) }

    fun resolveText(
        config: JSONObject,
        template: IslandTemplate?,
        vars: Map<String, String>,
        defaultLeft: String,
        defaultRight: String,
    ): Pair<String, String> {
        val leftSpec = specs.first { it.key == IslandCustomizationFieldKeys.leftExpr }
        val rightSpec = specs.first { it.key == IslandCustomizationFieldKeys.rightExpr }
        val leftExpr = config.optString(leftSpec.key, leftSpec.defaultProvider(template)).trim()
        val rightExpr = config.optString(rightSpec.key, rightSpec.defaultProvider(template)).trim()
        val left = if (leftExpr.isBlank()) "" else ExpressionResolver.resolve(leftExpr, vars).ifEmpty { defaultLeft }
        val right = if (rightExpr.isBlank()) "" else ExpressionResolver.resolve(rightExpr, vars).ifEmpty { defaultRight }
        return left to right
    }

    fun aodSchemaFields(template: IslandTemplate?): List<Map<String, Any?>> = listOf(
        mapOf(
            "key" to IslandCustomizationFieldKeys.aodTitle,
            "label" to IslandCustomizationFieldKeys.aodTitle,
            "type" to "text_expr",
            "defaultValue" to "${'$'}{subtitle_or_title}",
            "required" to true,
        ),
        mapOf(
            "key" to IslandCustomizationFieldKeys.aodPic,
            "label" to IslandCustomizationFieldKeys.aodPic,
            "type" to "select",
            "defaultValue" to "auto",
            "required" to true,
            "options" to listOf(
                mapOf("value" to "auto", "label" to "Auto"),
                mapOf("value" to "notif_small", "label" to "Small notification icon"),
                mapOf("value" to "notif_large", "label" to "Large notification icon"),
                mapOf("value" to "app_icon", "label" to "App icon"),
            ),
        ),
    )
}
