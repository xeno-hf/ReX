package io.github.xeno_hf.rex.ui

import rx.defs.ChannelDef
import io.github.xeno_hf.rex.Live
import io.github.xeno_hf.rex.PhoneGps

/** UI-only IDs. Signed electrical power, not mechanical shaft power. */
object DerivedChannels {
    const val MGU_POWER = -9100
    const val BMS_POWER = -9101
    private fun definition(name: String, id: Int, unit: String, lo: Double, hi: Double) =
        ChannelDef(name, id, 0, 1.0, 0.0, hi, lo, "Para", unit, "ReX", 0, emptyMap())
    val definitions = listOf(
        definition("MGU Power", MGU_POWER, "W", -500.0, 500.0),
        definition("BMS Power", BMS_POWER, "W", -500.0, 500.0),
        definition("Phone GPS Speed", PhoneGps.SPEED_ID, "km/h", 0.0, 300.0),
    )
    fun def(id: Int) = definitions.firstOrNull { it.absid == id }
    fun isPower(id: Int) = id == MGU_POWER || id == BMS_POWER
    fun product(voltage: Double?, current: Double?): Double? =
        if (voltage == null || current == null || voltage <= 0.0) null
        else (voltage * current).takeIf { it.isFinite() }
    fun value(id: Int, live: Live?): Double? = when (id) {
        MGU_POWER -> product(live?.v(163), live?.v(164))
        BMS_POWER -> product(live?.v(1432), live?.v(1433))
        else -> live?.v(id)
    }
    fun fraction(value: Double?) = ((value ?: 0.0) / 500.0).coerceIn(-1.0, 1.0).toFloat()
}
