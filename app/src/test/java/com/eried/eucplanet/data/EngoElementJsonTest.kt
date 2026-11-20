package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.DEFAULT_ENGO_ELEMENTS
import com.eried.eucplanet.data.store.SettingsJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** A glasses graph's length is a share of the dashboard's stats length, and it survives a save. */
class EngoElementJsonTest {

    private fun withGraph(div: Int) = AppSettings().let { d ->
        d.copy(engoHud = d.engoHud.copy(elements = DEFAULT_ENGO_ELEMENTS.mapIndexed { i, e ->
            if (i == 2) e.copy(style = "GRAPH", windowDiv = div) else e
        }))
    }

    /** The saved settings with element 3's stored JSON replaced by [edit]. */
    private fun loadWith(edit: (JSONObject) -> Unit): AppSettings {
        val j = SettingsJson.toJson(withGraph(1))
        edit(j.getJSONObject("engoHud").getJSONArray("elements").getJSONObject(2))
        return SettingsJson.fromJson(JSONObject(j.toString()))
    }

    @Test fun `every share survives a save`() {
        for (div in listOf(1, 2, 4, 8, 16, 32)) {
            val back = SettingsJson.fromJson(JSONObject(SettingsJson.toJson(withGraph(div)).toString()))
            assertEquals(div, back.engoHud.elements[2].windowDiv)
        }
    }

    @Test fun `an old fixed window in seconds starts at the whole dashboard length`() {
        val s = loadWith { it.remove("windowDiv"); it.put("window", 15) }
        assertEquals(1, s.engoHud.elements[2].windowDiv)
    }

    @Test fun `a share the picker does not offer falls back to the whole length`() {
        val s = loadWith { it.put("windowDiv", 3) }
        assertEquals(1, s.engoHud.elements[2].windowDiv)
    }
}
