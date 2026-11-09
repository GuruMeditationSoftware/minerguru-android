package app.minerguru.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
import app.minerguru.ui.theme.MG

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Building blocks in the style of current Google apps (Material 3 Expressive):
 * tonal surfaces instead of outlines, filled rounded fields, connected toggle buttons and
 * grouped lists whose items sit in their own rounded segments.
 */

/** Card without an outline: a tonal surface with large corners. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    shape: Shape = MaterialTheme.shapes.large,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // Card with a hairline, as on the Mac; color stays with the content.
    val border = BorderStroke(1.dp, MG.hairline())
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = border) { Column(content = content) }
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = border) { Column(content = content) }
    }
}

/** Shape of one item in a grouped list: large outer corners for the group, small corners between items. */
fun segmentShape(index: Int, count: Int, outer: Dp = 20.dp, inner: Dp = 4.dp): Shape {
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** Space between the segments of a grouped list. */
val SegmentGap: Dp = 2.dp

/** Section title above a group, as in the system settings. */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
    )
}

/** Filled text field with rounded corners and no underline, as in current Google apps. */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    minLines: Int = 1
) {
    val container = MaterialTheme.colorScheme.surfaceContainerHighest
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container.copy(alpha = 0.6f),
            errorContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent
        )
    )
}

/**
 * Connected toggle buttons (single choice): the selected one turns round and gets the secondary container,
 * the others keep small inner corners. Replaces the outlined segmented button.
 */
@Composable
fun ConnectedToggleGroup(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SegmentGap)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val outer = 50
            val shape = when {
                selected -> RoundedCornerShape(percent = 50)
                options.size == 1 -> RoundedCornerShape(percent = outer)
                index == 0 -> RoundedCornerShape(topStartPercent = outer, bottomStartPercent = outer, topEndPercent = 15, bottomEndPercent = 15)
                index == options.lastIndex -> RoundedCornerShape(topEndPercent = outer, bottomEndPercent = outer, topStartPercent = 15, bottomStartPercent = 15)
                else -> RoundedCornerShape(percent = 15)
            }
            Surface(
                shape = shape,
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    // Long labels get more room, so "Takt & Lüfter" fits next to "Pool" on narrow screens.
                    .weight(label.length.coerceAtLeast(6) + 2f)
                    .defaultMinSize(minHeight = if (compact) 36.dp else 48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(index) })
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = if (compact) 6.dp else 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selected && !compact) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    val labelStyle = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
                    Text(
                        text = label,
                        style = labelStyle,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = labelStyle.fontSize)
                    )
                }
            }
        }
    }
}

/** Filter chip without an outline: tonal when off, secondary container when on. */
@Composable
fun AppFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        border = null
    )
}

/** Assist chip without an outline. */
@Composable
fun AppAssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    AssistChip(
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(12.dp),
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = null
    )
}

/** Inner padding for content inside an [AppCard]. */
val CardPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
