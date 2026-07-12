package com.winlator.star

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.winlator.star.inputcontrols.Binding
import com.winlator.star.inputcontrols.ControlElement
import com.winlator.star.inputcontrols.ControlsProfile
import com.winlator.star.inputcontrols.CustomIconManager
import kotlin.math.roundToInt

private val EditorMaxSliderWidth = 300.dp
private val EditorPadding = 12.dp
private val EditorSpacing = 8.dp
private val EditorBackground = Color(0xFF1A1A2E)
private val EditorSurface = Color(0xFF2A2A3E)
private val EditorAccent = Color(0xFF4FC3F7)
private val EditorText = Color.White
private val EditorSubText = Color.White.copy(alpha = 0.7f)
private val EditorTextValue = Color.White.copy(alpha = 0.9f)
private val EditorShape = RoundedCornerShape(EditorPadding)
private val SmallShape = RoundedCornerShape(10.dp)

internal data class PickerIcon(
    val id: Int,
    val bitmap: Bitmap?,
)

@Composable
fun ControlsEditorSettingsPane(
    element: ControlElement,
    profile: ControlsProfile,
    onInvalidate: () -> Unit,
    customIconManager: CustomIconManager,
    customIconReloadKey: Int,
    activity: ControlsEditorActivity,
) {
    val typeOptions = remember { ControlElement.Type.names().toList() }
    val shapeOptions = remember { ControlElement.Shape.names().toList() }
    val rangeOptions = remember { ControlElement.Range.names().toList() }
    val bindingOptions = remember { Binding.values().map { it.toString() } }
    val holdKeyOptions = remember {
        buildList {
            add(activity.getString(R.string.none))
            for (binding in Binding.values()) {
                if (binding == Binding.NONE) continue
                if (binding.isKeyboard() || (binding.isMouse() && !binding.isMouseMove())) {
                    add(binding.toString())
                }
            }
        }
    }

    val builtInIcons = remember(activity) { loadBuiltInIcons(activity) }
    val customIcons = remember(activity, customIconReloadKey) { loadCustomIcons(customIconManager) }

    var typeIndex by remember { mutableStateOf(element.getType().ordinal) }
    var shapeIndex by remember { mutableStateOf(element.getShape().ordinal) }
    var rangeIndex by remember { mutableStateOf(element.getRange().ordinal) }
    var orientationIndex by remember { mutableStateOf(element.getOrientation().toInt().coerceIn(0, 1)) }
    var scaleValue by remember { mutableStateOf((element.getScale() * 100f).roundToInt().coerceIn(50, 150)) }
    var detectionWidth by remember { mutableStateOf(element.getAreaWidth().coerceAtLeast(200)) }
    var detectionHeight by remember { mutableStateOf(element.getAreaHeight().coerceAtLeast(200)) }
    var stickRadius by remember { mutableStateOf(element.getStickRadius().coerceAtLeast(60)) }
    var mouseAreaWidth by remember { mutableStateOf(element.getAreaWidth().coerceAtLeast(200)) }
    var mouseAreaHeight by remember { mutableStateOf(element.getAreaHeight().coerceAtLeast(200)) }
    var mouseSensitivity by remember { mutableStateOf((element.getMouseSensitivity() * 10f).roundToInt().coerceIn(1, 50)) }
    var gridRows by remember { mutableStateOf(element.getGridRows().coerceAtLeast(1)) }
    var gridCols by remember { mutableStateOf(element.getGridCols().coerceAtLeast(1)) }
    var gridCellShapeIndex by remember { mutableStateOf(element.getGridCellShape().ordinal) }
    var holdKeyIndex by remember { mutableStateOf(holdKeyOptions.indexOf(element.getHoldKey().toString()).coerceAtLeast(0)) }
    var toggleSwitch by remember { mutableStateOf(element.isToggleSwitch()) }
    var customText by remember { mutableStateOf(element.getText()) }
    var selectedIconId by remember { mutableStateOf(element.getIconId().toInt() and 0xFF) }
    var bindingCount by remember { mutableStateOf(element.getBindingCount().coerceAtLeast(1)) }

    fun saveAndInvalidate() {
        profile.save()
        onInvalidate()
    }

    fun syncFromElement() {
        typeIndex = element.getType().ordinal
        shapeIndex = element.getShape().ordinal
        rangeIndex = element.getRange().ordinal
        orientationIndex = element.getOrientation().toInt().coerceIn(0, 1)
        scaleValue = (element.getScale() * 100f).roundToInt().coerceIn(50, 150)
        detectionWidth = element.getAreaWidth().coerceAtLeast(200)
        detectionHeight = element.getAreaHeight().coerceAtLeast(200)
        stickRadius = element.getStickRadius().coerceAtLeast(60)
        mouseAreaWidth = element.getAreaWidth().coerceAtLeast(200)
        mouseAreaHeight = element.getAreaHeight().coerceAtLeast(200)
        mouseSensitivity = (element.getMouseSensitivity() * 10f).roundToInt().coerceIn(1, 50)
        gridRows = element.getGridRows().coerceAtLeast(1)
        gridCols = element.getGridCols().coerceAtLeast(1)
        gridCellShapeIndex = element.getGridCellShape().ordinal
        holdKeyIndex = holdKeyOptions.indexOf(element.getHoldKey().toString()).coerceAtLeast(0)
        toggleSwitch = element.isToggleSwitch()
        customText = element.getText()
        selectedIconId = element.getIconId().toInt() and 0xFF
        bindingCount = element.getBindingCount().coerceAtLeast(1)
    }

    val selectedType = ControlElement.Type.values()[typeIndex]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(EditorBackground)
            .padding(EditorPadding),
        verticalArrangement = Arrangement.spacedBy(EditorSpacing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { activity.closeSidebar() }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(android.R.string.cancel),
                    tint = EditorText,
                )
            }
        }

        SettingsSection(title = stringResource(R.string.type), visible = true) {
            SettingSpinner(
                label = stringResource(R.string.type),
                options = typeOptions,
                selectedIndex = typeIndex,
                onSelected = { index ->
                    element.setType(ControlElement.Type.values()[index])
                    syncFromElement()
                    saveAndInvalidate()
                },
            )
        }

        SettingsSection(title = stringResource(R.string.shape), visible = selectedType == ControlElement.Type.BUTTON) {
            SettingSpinner(
                label = stringResource(R.string.shape),
                options = shapeOptions,
                selectedIndex = shapeIndex,
                onSelected = { index ->
                    element.setShape(ControlElement.Shape.values()[index])
                    shapeIndex = index
                    saveAndInvalidate()
                },
            )
        }

        if (selectedType == ControlElement.Type.RANGE_BUTTON) {
            SettingSpinner(
                label = stringResource(R.string.range),
                options = rangeOptions,
                selectedIndex = rangeIndex,
                onSelected = { index ->
                    element.setRange(ControlElement.Range.values()[index])
                    rangeIndex = index
                    saveAndInvalidate()
                },
            )

            SettingRadioGroup(
                label = stringResource(R.string.orientation),
                options = listOf(stringResource(R.string.horizontal), stringResource(R.string.vertical)),
                selectedIndex = orientationIndex,
                onSelected = { index ->
                    element.setOrientation(index.toByte())
                    orientationIndex = index
                    saveAndInvalidate()
                },
            )

            NumberPickerRow(
                label = stringResource(R.string.columns),
                value = bindingCount,
                minValue = 3,
                maxValue = 8,
                onValueChange = { value ->
                    element.setBindingCount(value)
                    bindingCount = value
                    saveAndInvalidate()
                },
            )
        }

        LabeledSlider(
            label = stringResource(R.string.scale),
            value = scaleValue,
            rangeStart = 50,
            rangeEnd = 150,
            suffix = "%",
            onValueChange = { value ->
                val rounded = ((value / 5f).roundToInt() * 5).coerceIn(50, 150)
                element.setScale(rounded / 100f)
                scaleValue = rounded
                saveAndInvalidate()
            },
        )

        SettingsSection(title = stringResource(R.string.detection_area), visible = selectedType == ControlElement.Type.DYNAMIC_STICK) {
            LabeledSlider(
                label = stringResource(R.string.area_width),
                value = detectionWidth,
                rangeStart = 200,
                rangeEnd = 2000,
                suffix = "px",
                onValueChange = { value ->
                    element.setAreaWidth(value)
                    detectionWidth = value
                    saveAndInvalidate()
                },
            )
            LabeledSlider(
                label = stringResource(R.string.area_height),
                value = detectionHeight,
                rangeStart = 200,
                rangeEnd = 2000,
                suffix = "px",
                onValueChange = { value ->
                    element.setAreaHeight(value)
                    detectionHeight = value
                    saveAndInvalidate()
                },
            )
            LabeledSlider(
                label = stringResource(R.string.stick_radius),
                value = stickRadius,
                rangeStart = 60,
                rangeEnd = 400,
                suffix = "px",
                onValueChange = { value ->
                    element.setStickRadius(value)
                    stickRadius = value
                    saveAndInvalidate()
                },
            )
        }

        SettingsSection(title = stringResource(R.string.detection_area), visible = selectedType == ControlElement.Type.MOUSE_AREA) {
            LabeledSlider(
                label = stringResource(R.string.area_width),
                value = mouseAreaWidth,
                rangeStart = 200,
                rangeEnd = 2000,
                suffix = "px",
                onValueChange = { value ->
                    element.setAreaWidth(value)
                    mouseAreaWidth = value
                    saveAndInvalidate()
                },
            )
            LabeledSlider(
                label = stringResource(R.string.area_height),
                value = mouseAreaHeight,
                rangeStart = 200,
                rangeEnd = 2000,
                suffix = "px",
                onValueChange = { value ->
                    element.setAreaHeight(value)
                    mouseAreaHeight = value
                    saveAndInvalidate()
                },
            )
            LabeledSlider(
                label = stringResource(R.string.mouse_sensitivity),
                value = mouseSensitivity,
                rangeStart = 1,
                rangeEnd = 50,
                suffix = "x",
                format = { value -> String.format("%.1fx", value / 10f) },
                onValueChange = { value ->
                    element.setMouseSensitivity(value / 10f)
                    mouseSensitivity = value
                    saveAndInvalidate()
                },
            )
        }

        SettingsSection(
            title = stringResource(R.string.hold_key),
            visible = selectedType == ControlElement.Type.TRACKPAD || selectedType == ControlElement.Type.MOUSE_AREA || selectedType == ControlElement.Type.STICK || selectedType == ControlElement.Type.DYNAMIC_STICK,
        ) {
            SettingSpinner(
                label = stringResource(R.string.hold_key),
                options = holdKeyOptions,
                selectedIndex = holdKeyIndex,
                onSelected = { index ->
                    holdKeyIndex = index
                    val binding = if (index == 0) Binding.NONE else holdKeyBindingFromLabel(holdKeyOptions[index])
                    element.setHoldKey(binding)
                    saveAndInvalidate()
                },
            )
        }

        SettingsSection(title = stringResource(R.string.button_grid), visible = selectedType == ControlElement.Type.BUTTON_GRID) {
            NumberPickerRow(
                label = stringResource(R.string.grid_rows),
                value = gridRows,
                minValue = 1,
                maxValue = 8,
                onValueChange = { value ->
                    gridRows = value
                    element.setGridRows(value)
                    element.setBindingCount(value * gridCols)
                    bindingCount = element.getBindingCount().coerceAtLeast(1)
                    saveAndInvalidate()
                },
            )
            NumberPickerRow(
                label = stringResource(R.string.grid_cols),
                value = gridCols,
                minValue = 1,
                maxValue = 16,
                onValueChange = { value ->
                    gridCols = value
                    element.setGridCols(value)
                    element.setBindingCount(gridRows * value)
                    bindingCount = element.getBindingCount().coerceAtLeast(1)
                    saveAndInvalidate()
                },
            )
            SettingSpinner(
                label = stringResource(R.string.cell_shape),
                options = shapeOptions,
                selectedIndex = gridCellShapeIndex,
                onSelected = { index ->
                    element.setGridCellShape(ControlElement.Shape.values()[index])
                    gridCellShapeIndex = index
                    saveAndInvalidate()
                },
            )
            QuickFillBar(
                onQwerty = {
                    activity.prepareGridForFill(element)
                    activity.fillGridQWERTY(element)
                    bindingCount = element.getBindingCount().coerceAtLeast(1)
                    saveAndInvalidate()
                },
                onFunctionKeys = {
                    activity.prepareGridForFill(element)
                    activity.fillGridFKeys(element)
                    bindingCount = element.getBindingCount().coerceAtLeast(1)
                    saveAndInvalidate()
                },
                onNumPad = {
                    activity.prepareGridForFill(element)
                    activity.fillGridNumPad(element)
                    bindingCount = element.getBindingCount().coerceAtLeast(1)
                    saveAndInvalidate()
                },
                onClear = {
                    activity.prepareGridForFill(element)
                    val total = gridRows * gridCols
                    for (index in 0 until total) {
                        activity.clearGridCell(element, index)
                    }
                    saveAndInvalidate()
                },
            )
        }

        if (selectedType != ControlElement.Type.MOUSE_AREA) {
            SettingsSection(title = stringResource(R.string.bindings), visible = true) {
                if (selectedType == ControlElement.Type.BUTTON) {
                    BindingSpinnerRow(
                        label = stringResource(R.string.binding),
                        value = element.getBindingAt(0),
                        options = bindingOptions,
                        onValueChanged = { binding ->
                            element.setBindingAt(0, binding)
                            saveAndInvalidate()
                        },
                    )
                } else if (selectedType == ControlElement.Type.D_PAD || selectedType == ControlElement.Type.STICK || selectedType == ControlElement.Type.TRACKPAD || selectedType == ControlElement.Type.DYNAMIC_STICK) {
                    BindingSpinnerRow(stringResource(R.string.binding_up), element.getBindingAt(0), bindingOptions, onValueChanged = { binding ->
                        element.setBindingAt(0, binding)
                        saveAndInvalidate()
                    })
                    BindingSpinnerRow(stringResource(R.string.binding_right), element.getBindingAt(1), bindingOptions, onValueChanged = { binding ->
                        element.setBindingAt(1, binding)
                        saveAndInvalidate()
                    })
                    BindingSpinnerRow(stringResource(R.string.binding_down), element.getBindingAt(2), bindingOptions, onValueChanged = { binding ->
                        element.setBindingAt(2, binding)
                        saveAndInvalidate()
                    })
                    BindingSpinnerRow(stringResource(R.string.binding_left), element.getBindingAt(3), bindingOptions, onValueChanged = { binding ->
                        element.setBindingAt(3, binding)
                        saveAndInvalidate()
                    })
                } else if (selectedType == ControlElement.Type.BUTTON_GRID) {
                    ComboEditor(
                        element = element,
                        profile = profile,
                        onInvalidate = onInvalidate,
                        activity = activity,
                    )

                    val total = gridRows * gridCols
                    for (index in 0 until total) {
                        val row = index / gridCols + 1
                        val col = index % gridCols + 1
                        BindingSpinnerRow(
                            label = stringResource(R.string.binding_grid_cell_label, row, col),
                            value = element.getBindingAt(index),
                            options = bindingOptions,
                            onValueChanged = { binding ->
                                element.setBindingAt(index, binding)
                                saveAndInvalidate()
                            },
                            comboSummary = comboSummaryFor(element, index),
                        )
                    }
                }
            }
        }

        SettingSwitch(
            label = stringResource(R.string.toggle_switch),
            checked = toggleSwitch,
            visible = selectedType == ControlElement.Type.BUTTON,
            onCheckedChange = { checked ->
                toggleSwitch = checked
                element.setToggleSwitch(checked)
                saveAndInvalidate()
            },
        )

        SettingsSection(title = stringResource(R.string.custom_text), visible = selectedType == ControlElement.Type.BUTTON) {
            Column(verticalArrangement = Arrangement.spacedBy(EditorSpacing)) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { value ->
                        val text = value.take(8)
                        customText = text
                        element.setText(text)
                        saveAndInvalidate()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.custom_text), color = EditorSubText) },
                    placeholder = { Text(stringResource(R.string.none), color = EditorSubText) },
                    colors = outlinedTextFieldColors(),
                )

                Text(
                    text = stringResource(R.string.icon),
                    color = EditorSubText,
                    fontWeight = FontWeight.Bold,
                )
                IconPicker(
                    icons = builtInIcons,
                    selectedId = selectedIconId,
                    onSelected = { id ->
                        selectedIconId = id
                        element.setIconId(id)
                        saveAndInvalidate()
                    },
                )

                Text(
                    text = stringResource(R.string.custom_icons),
                    color = EditorSubText,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(EditorSpacing), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { activity.promptPickCustomIcon() },
                        shape = SmallShape,
                    ) {
                        Text(text = stringResource(R.string.add), color = EditorAccent)
                    }
                }
                IconPicker(
                    icons = customIcons,
                    selectedId = selectedIconId,
                    onSelected = { id ->
                        selectedIconId = id
                        element.setIconId(id)
                        saveAndInvalidate()
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Int,
    rangeStart: Int,
    rangeEnd: Int,
    suffix: String,
    modifier: Modifier = Modifier,
    format: (Int) -> String = { current -> "$current$suffix" },
    onValueChange: (Int) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = EditorSubText,
            )
            Text(
                text = format(value),
                color = EditorTextValue,
                fontWeight = FontWeight.Medium,
            )
        }

        androidx.compose.material3.Slider(
            modifier = Modifier.widthIn(max = EditorMaxSliderWidth),
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(rangeStart, rangeEnd)) },
            valueRange = rangeStart.toFloat()..rangeEnd.toFloat(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSpinner(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedText = options.getOrNull(selectedIndex).orEmpty()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, color = EditorSubText)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                value = selectedText,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = outlinedTextFieldColors(),
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(EditorSurface, EditorShape),
            ) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                color = if (index == selectedIndex) EditorAccent else EditorText,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelected(index)
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SettingRadioGroup(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, color = EditorSubText)
        Row(horizontalArrangement = Arrangement.spacedBy(EditorSpacing), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { index, option ->
                Row(
                    modifier = Modifier.selectable(
                        selected = index == selectedIndex,
                        onClick = { onSelected(index) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = index == selectedIndex,
                        onClick = { onSelected(index) },
                    )
                    Text(text = option, color = EditorTextValue)
                }
            }
        }
    }
}

@Composable
fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, color = EditorSubText)
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
fun NumberPickerRow(
    label: String,
    value: Int,
    minValue: Int,
    maxValue: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, color = EditorSubText)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EditorSpacing)) {
            OutlinedTextField(
                value = value.toString(),
                onValueChange = { input ->
                    val filtered = input.filter { it.isDigit() }
                    if (filtered.isEmpty()) return@OutlinedTextField
                    onValueChange(filtered.toInt().coerceIn(minValue, maxValue))
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = outlinedTextFieldColors(),
            )
            TextButton(onClick = { onValueChange((value - 1).coerceAtLeast(minValue)) }, shape = SmallShape) {
                Text(text = "−", color = EditorAccent)
            }
            TextButton(onClick = { onValueChange((value + 1).coerceAtMost(maxValue)) }, shape = SmallShape) {
                Text(text = "+", color = EditorAccent)
            }
        }
    }
}

@Composable
fun QuickFillBar(
    onQwerty: () -> Unit,
    onFunctionKeys: () -> Unit,
    onNumPad: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EditorSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FillChip(text = stringResource(R.string.fill_qwerty), onClick = onQwerty)
        FillChip(text = stringResource(R.string.fill_function_keys), onClick = onFunctionKeys)
        FillChip(text = stringResource(R.string.fill_numpad), onClick = onNumPad)
        FillChip(text = stringResource(R.string.clear), onClick = onClear)
    }
}

@Composable
internal fun IconPicker(
    icons: List<PickerIcon>,
    selectedId: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EditorSpacing),
    ) {
        items(icons, key = { it.id }) { icon ->
            val selected = icon.id == selectedId
            val border = if (selected) BorderStroke(2.dp, EditorAccent) else BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
            val imageBitmap = remember(icon.bitmap) { icon.bitmap?.asImageBitmap() }
            Surface(
                shape = EditorShape,
                color = if (selected) EditorSurface else Color.Transparent,
                border = border,
                modifier = Modifier
                    .size(44.dp)
                    .clickable { onSelected(icon.id) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (imageBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = imageBitmap,
                            contentDescription = "Icon ${icon.id}",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ComboEditor(
    element: ControlElement,
    profile: ControlsProfile,
    onInvalidate: () -> Unit,
    activity: ControlsEditorActivity,
    modifier: Modifier = Modifier,
) {
    var openIndex by remember(element) { mutableStateOf(-1) }
    val total = element.getGridRows().coerceAtLeast(1) * element.getGridCols().coerceAtLeast(1)

    SettingsSection(title = "Combo Bindings", visible = true, modifier = modifier) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(EditorSpacing)) {
            items(total) { index ->
                val cols = element.getGridCols().coerceAtLeast(1)
                val row = index / cols + 1
                val col = index % cols + 1
                val label = stringResource(R.string.binding_grid_cell_label, row, col)
                val summary = comboSummaryFor(element, index)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = label, color = EditorSubText)
                        Text(text = summary.ifEmpty { stringResource(R.string.none) }, color = EditorTextValue)
                    }
                    TextButton(onClick = { openIndex = index }, shape = SmallShape) {
                        Text(text = stringResource(R.string.edit), color = EditorAccent)
                    }
                }
            }
        }
    }

    if (openIndex >= 0) {
        val index = openIndex
        val existingCombo = element.getCombo(index)
        var ctrlChecked by remember(index) { mutableStateOf(existingCombo?.contains(Binding.KEY_CTRL_L) == true) }
        var shiftChecked by remember(index) { mutableStateOf(existingCombo?.contains(Binding.KEY_SHIFT_L) == true) }
        var altChecked by remember(index) { mutableStateOf(existingCombo?.contains(Binding.KEY_ALT_L) == true) }
        val keyboardValues = remember { Binding.keyboardBindingValues().toList() }
        val keyboardLabels = remember { keyboardValues.map { it.toString() } }
        val currentMain = remember(index) {
            val existingMain = existingCombo?.firstOrNull {
                it.isKeyboard() && it != Binding.KEY_CTRL_L && it != Binding.KEY_SHIFT_L && it != Binding.KEY_ALT_L
            } ?: element.getBindingAt(index)
            keyboardValues.indexOf(existingMain).coerceAtLeast(0)
        }
        var mainIndex by remember(index) { mutableStateOf(currentMain) }
        val comboTitle = remember(element, index, element.getType(), element.getGridCols()) {
            comboLabelFor(element, index)
        }

        AlertDialog(
            onDismissRequest = { openIndex = -1 },
            title = { Text(text = stringResource(R.string.key_combo_title, comboTitle.replace("%", "%%")), color = EditorText) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(EditorSpacing)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ctrlChecked, onCheckedChange = { ctrlChecked = it })
                        Text(text = stringResource(R.string.ctrl), color = EditorTextValue)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = shiftChecked, onCheckedChange = { shiftChecked = it })
                        Text(text = stringResource(R.string.shift), color = EditorTextValue)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = altChecked, onCheckedChange = { altChecked = it })
                        Text(text = stringResource(R.string.alt), color = EditorTextValue)
                    }
                    SettingSpinner(
                        label = stringResource(R.string.main_key),
                        options = keyboardLabels,
                        selectedIndex = mainIndex,
                        onSelected = { mainIndex = it },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val combo = buildList {
                        if (ctrlChecked) add(Binding.KEY_CTRL_L)
                        if (shiftChecked) add(Binding.KEY_SHIFT_L)
                        if (altChecked) add(Binding.KEY_ALT_L)
                        keyboardValues.getOrNull(mainIndex)?.let { main ->
                            if (main != Binding.NONE) add(main)
                        }
                    }
                    val mainBinding = keyboardValues.getOrNull(mainIndex) ?: Binding.NONE
                    if (combo.isNotEmpty()) {
                        element.setCombo(index, combo.toTypedArray())
                        if (mainBinding != Binding.NONE) element.setBindingAt(index, mainBinding)
                    } else {
                        element.setCombo(index, null)
                    }
                    profile.save()
                    onInvalidate()
                    openIndex = -1
                }) {
                    Text(text = stringResource(R.string.save), color = EditorAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { openIndex = -1 }) {
                    Text(text = stringResource(android.R.string.cancel), color = EditorTextValue)
                }
            },
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(EditorSpacing),
        ) {
            Text(
                text = title,
                color = EditorText,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}

@Composable
private fun BindingSpinnerRow(
    label: String,
    value: Binding,
    options: List<String>,
    onValueChanged: (Binding) -> Unit,
    comboSummary: String? = null,
) {
    val selectedIndex = options.indexOf(value.toString()).coerceAtLeast(0)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (comboSummary != null && comboSummary.isNotEmpty()) {
            Text(text = comboSummary, color = EditorSubText)
        }
        SettingSpinner(
            label = label,
            options = options,
            selectedIndex = selectedIndex,
            onSelected = { index -> onValueChanged(Binding.fromString(options.getOrNull(index))) },
        )
    }
}

@Composable
private fun FillChip(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = SmallShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = EditorAccent,
        ),
    ) {
        Text(text = text, color = EditorAccent)
    }
}

private fun comboSummaryFor(element: ControlElement, index: Int): String {
    val combo = element.getCombo(index) ?: return ""
    return combo.joinToString(" + ") { it.toString() }
}

private fun comboLabelFor(element: ControlElement, index: Int): String {
    return if (element.getType() == ControlElement.Type.BUTTON_GRID) {
        val cols = element.getGridCols().coerceAtLeast(1)
        val row = index / cols + 1
        val col = index % cols + 1
        "$row,$col"
    } else {
        (index + 1).toString()
    }
}

private fun holdKeyBindingFromLabel(label: String): Binding {
    for (binding in Binding.values()) {
        if (binding.toString() == label) return binding
    }
    return Binding.NONE
}

private fun loadBuiltInIcons(activity: ControlsEditorActivity): List<PickerIcon> {
    val icons = mutableListOf<PickerIcon>()
    try {
        val filenames = activity.assets.list("inputcontrols/icons/") ?: emptyArray()
        for (filename in filenames) {
            val id = filename.substringBefore('.').toIntOrNull() ?: continue
            val bitmap = activity.assets.open("inputcontrols/icons/$filename").use { BitmapFactory.decodeStream(it) }
            icons.add(PickerIcon(id = id, bitmap = bitmap))
        }
    } catch (_: Exception) {
    }
    return icons.sortedBy { it.id }
}

private fun loadCustomIcons(customIconManager: CustomIconManager): List<PickerIcon> {
    return customIconManager.customIconIds.mapNotNull { id ->
        val bitmap = customIconManager.loadIcon(id)
        PickerIcon(id = id.toInt(), bitmap = bitmap)
    }
}

@Composable
private fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = EditorText,
    unfocusedTextColor = EditorText,
    focusedBorderColor = EditorAccent,
    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
    cursorColor = EditorAccent,
    focusedLabelColor = EditorSubText,
    unfocusedLabelColor = EditorSubText,
    focusedContainerColor = EditorBackground,
    unfocusedContainerColor = EditorBackground,
)

private val CustomIconManager.customIconIds: List<Short>
    get() = getCustomIconIds()
