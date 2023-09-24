package app.aaps.interfaces.iob

import info.nightscout.interfaces.utils.DecimalFormatter
import info.nightscout.interfaces.utils.Round

data class GlucoseStatus(
    val glucose: Double,
    val noise: Double = 0.0,
    val delta: Double = 0.0,
    val shortAvgDelta: Double = 0.0,
    val longAvgDelta: Double = 0.0,
    val date: Long = 0L,
    // mod 7: append 2 variables for 5% range
    val dura_ISF_minutes: Double = 0.0,
    val dura_ISF_average: Double = 0.0,
    // mod 8: append 3 variables for deltas based on regression analysis
    val slope05: Double = 0.0, // wait for longer history
    val slope15: Double = 0.0, // wait for longer history
    val slope40: Double = 0.0, // wait for longer history
    // mod 14f: append results from best fitting parabola
    val dura_p: Double = 0.0,
    val delta_pl: Double = 0.0,
    val delta_pn: Double = 0.0,
    val r_squ: Double = 0.0,
    val bg_acceleration: Double = 0.0,
    val a_0: Double = 0.0,
    val a_1: Double = 0.0,
    val a_2: Double = 0.0,
) {

    fun log(decimalFormatter: DecimalFormatter): String = "Glucose: " + decimalFormatter.to0Decimal(glucose) + " mg/dl " +
        "Noise: " + decimalFormatter.to0Decimal(noise) + " " +
        "Delta: " + decimalFormatter.to0Decimal(delta) + " mg/dl " +
        "Short avg. delta: " + " " + decimalFormatter.to2Decimal(shortAvgDelta) + " mg/dl " +
        "Long avg. delta: " + decimalFormatter.to2Decimal(longAvgDelta) + " mg/dl " +
        "Range length: " + decimalFormatter.to0Decimal(dura_ISF_minutes) + " min " +
        "Range average: " + decimalFormatter.to2Decimal(dura_ISF_average) + " mg/dl; " +
        "5 min fit delta: " + decimalFormatter.to2Decimal(slope05) + " mg/dl; " +
        "15 min fit delta: " + decimalFormatter.to2Decimal(slope15) + " mg/dl; " +
        "40 min fit delta: " + decimalFormatter.to2Decimal(slope40) + " mg/dl; " +
        "parabola length: " + decimalFormatter.to2Decimal(dura_p) + " min; " +
        "parabola last delta: " + decimalFormatter.to2Decimal(delta_pl) + " mg/dl; " +
        "parabola next delta: " + decimalFormatter.to2Decimal(delta_pn) + " mg/dl; " +
        "bg_acceleration: " + decimalFormatter.to2Decimal(bg_acceleration) + " mg/dl/(25m^2); " +
        "fit correlation: " + r_squ + "; debug:"
}

fun GlucoseStatus.asRounded() = copy(
    glucose = Round.roundTo(glucose, 0.1),
    noise = Round.roundTo(noise, 0.01),
    delta = Round.roundTo(delta, 0.01),
    shortAvgDelta = Round.roundTo(shortAvgDelta, 0.01),
    longAvgDelta = Round.roundTo(longAvgDelta, 0.01),
    dura_ISF_minutes = Round.roundTo(dura_ISF_minutes, 0.1),
    dura_ISF_average = Round.roundTo(dura_ISF_average, 0.1),
    slope05  = Round.roundTo(slope05, 0.01),
    slope15 = Round.roundTo(slope15, 0.01),
    slope40 = Round.roundTo(slope40, 0.01),
    dura_p = Round.roundTo(dura_p, 0.1),
    delta_pl = Round.roundTo(delta_pl, 0.01),
    delta_pn = Round.roundTo(delta_pn, 0.01),
    bg_acceleration = Round.roundTo(bg_acceleration, 0.01),
    r_squ = Round.roundTo(r_squ, 0.0001),
    a_0 = Round.roundTo(a_0, 0.1),
    a_1 = Round.roundTo(a_1, 0.01),
    a_2 = Round.roundTo(a_2, 0.01)
)