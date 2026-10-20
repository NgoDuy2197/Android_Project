package com.dsoft.autotap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    @Test
    fun stepRoundTrip_allTypes() {
        val steps = listOf(
            Step.tap(100, 200),
            Step.longPress(5, 6, 900),
            Step.swipe(1, 2, 3, 4, 350),
            Step.wait(1500),
            Step.tapText("Đồng ý", 2),
            Step.input("xin chào {{name}}"),
            Step(Step.BACK),
            Step(Step.HOME),
            Step(Step.RECENTS),
            Step(Step.TAP_TEXT, text = "OK", hasRegion = true, regionLeft = 10, regionTop = 20, regionRight = 300, regionBottom = 400),
        )
        for (s in steps) {
            val back = Step.fromJson(s.toJson())
            assertEquals("round-trip failed for ${s.type}", s, back)
        }
    }

    @Test
    fun scenarioRoundTrip() {
        val s = Scenario(
            id = "s1",
            name = "Điểm danh",
            steps = mutableListOf(Step.tap(10, 20), Step.wait(500), Step.tapText("Gửi")),
            repeatCount = 5,
            defaultDelayMs = 250,
        )
        val decoded = ScenarioJson.decode(ScenarioJson.encode(s))
        assertEquals(s.id, decoded.id)
        assertEquals(s.name, decoded.name)
        assertEquals(s.repeatCount, decoded.repeatCount)
        assertEquals(s.defaultDelayMs, decoded.defaultDelayMs)
        assertEquals(s.steps, decoded.steps)
    }

    @Test
    fun listRoundTrip() {
        val list = listOf(
            Scenario("a", "A", mutableListOf(Step.tap(1, 1))),
            Scenario("b", "B", mutableListOf(Step.wait(10), Step.input("x"))),
        )
        val decoded = ScenarioJson.decodeList(ScenarioJson.encodeList(list))
        assertEquals(2, decoded.size)
        assertEquals("A", decoded[0].name)
        assertEquals(list[1].steps, decoded[1].steps)
    }

    @Test
    fun decodeList_emptyAndBlank() {
        assertTrue(ScenarioJson.decodeList("").isEmpty())
        assertTrue(ScenarioJson.decodeList("{}").isEmpty())
        assertTrue(ScenarioJson.decodeList("{\"scenarios\":[]}").isEmpty())
    }

    @Test
    fun paramSubstitute_basic() {
        val out = Params.substitute("Xin chào {{name}}, mã {{code}}", mapOf("name" to "An", "code" to "42"))
        assertEquals("Xin chào An, mã 42", out)
    }

    @Test
    fun paramSubstitute_unknownLeftUntouched() {
        val out = Params.substitute("gửi {{missing}}", mapOf("name" to "An"))
        assertEquals("gửi {{missing}}", out)
    }

    @Test
    fun paramSubstitute_whitespaceAndNoParams() {
        assertEquals("hi", Params.substitute("hi", emptyMap()))
        assertEquals("An", Params.substitute("{{ name }}", mapOf("name" to "An")))
    }

    @Test
    fun paramApply_onlyTextField() {
        val step = Step.tapText("nút {{btn}}")
        val applied = Params.apply(step, mapOf("btn" to "OK"))
        assertEquals("nút OK", applied.text)
        // A step without text is returned unchanged.
        val tap = Step.tap(1, 2)
        assertTrue(tap === Params.apply(tap, mapOf("btn" to "OK")))
    }

    @Test
    fun describe_isReadable() {
        assertTrue(Step.tap(3, 4).describe().contains("3"))
        assertTrue(Step.wait(100).describe().contains("100"))
        assertFalse(Step(Step.BACK).describe().isEmpty())
    }
}
