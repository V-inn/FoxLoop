package io.github.v_inn.foxloop.console

import io.github.v_inn.foxloop.console.PowerReadout.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerReadoutTest {
    @Test
    fun unknownIsNotShownAtAll() {
        assertNull(PowerReadout.power(ConsoleProtocol.THROTTLED_UNKNOWN))
        assertNull(PowerReadout.temperature(ConsoleProtocol.TEMP_UNKNOWN))
    }

    @Test
    fun clean() {
        assertEquals(PowerReadout.Reading("power ok", Level.Fine), PowerReadout.power(0))
    }

    @Test
    fun underVoltageNowOutranksEverything() {
        assertEquals(Level.Alert, PowerReadout.power(0x50005)!!.level)
        assertEquals("under-voltage now", PowerReadout.power(0x50005)!!.text)
    }

    @Test
    fun underVoltageSinceBootIsAWarning() {
        assertEquals(PowerReadout.Reading("under-voltage since boot", Level.Warn), PowerReadout.power(0x10000))
    }

    @Test
    fun throttlingWithoutUnderVoltage() {
        assertEquals(Level.Alert, PowerReadout.power(0x4)!!.level)
        assertEquals(PowerReadout.Reading("throttled since boot", Level.Warn), PowerReadout.power(0x40000))
    }

    @Test
    fun temperatureInDegrees() {
        assertEquals("48.3 °C", PowerReadout.temperature(48_312))
    }
}
