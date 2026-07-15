package com.winlator.star.inputcontrols

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputControlsFormatTest {
    @Test
    fun androidKeyboardBinding_isAppendedAfterGamepadBindings() {
        assertEquals(Binding.values().last(), Binding.SHOW_ANDROID_KEYBOARD)
        assertTrue(Binding.SHOW_ANDROID_KEYBOARD.ordinal > Binding.GAMEPAD_DPAD_LEFT.ordinal)
        assertEquals("ANDROID KEYBOARD", Binding.SHOW_ANDROID_KEYBOARD.toString())
    }

    @Test
    fun remapIconIds_changesOnlyMappedCustomIcons() {
        val first = JSONObject().put("iconId", 100).put("forkField", "keep")
        val second = JSONObject().put("iconId", 5)
        val root = JSONObject()
            .put("vendorExtension", true)
            .put("elements", JSONArray().put(first).put(second))

        InputControlsManager.remapIconIds(root, mapOf(100 to 128))

        assertEquals(128, first.getInt("iconId"))
        assertEquals("keep", first.getString("forkField"))
        assertEquals(5, second.getInt("iconId"))
        assertTrue(root.getBoolean("vendorExtension"))
        assertFalse(root.has("customIcons"))
    }

    @Test
    fun serialization_clearsKnownOptionalFieldsButKeepsForkFields() {
        val source = JSONObject()
            .put("groupId", "old-group")
            .put("combos", JSONArray().put(JSONArray().put(0)))
            .put("holdKey", "KEY_W")
            .put("gridCellShape", "CIRCLE")
            .put("forkField", "keep")

        val copy = ControlElement.copyForSerialization(source)

        assertFalse(copy.has("groupId"))
        assertFalse(copy.has("combos"))
        assertFalse(copy.has("holdKey"))
        assertFalse(copy.has("gridCellShape"))
        assertEquals("keep", copy.getString("forkField"))
    }

    @Test
    fun gamepadReset_neutralizesEveryField() {
        val state = GamepadState().apply {
            thumbLX = 1f
            thumbLY = -1f
            thumbRX = 0.5f
            thumbRY = -0.5f
            triggerL = 1f
            triggerR = 1f
            setPressed(2, true)
            dpad[0] = true
        }

        state.reset()

        assertEquals(0f, state.thumbLX)
        assertEquals(0f, state.thumbLY)
        assertEquals(0f, state.thumbRX)
        assertEquals(0f, state.thumbRY)
        assertEquals(0f, state.triggerL)
        assertEquals(0f, state.triggerR)
        assertEquals(0, state.buttons.toInt())
        assertTrue(state.dpad.none { it })
    }
}
