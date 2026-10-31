package io.github.aluetschen.karoofuel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuelTrackerTest {
    private val rider = RiderProfile(weightKg = 75.0, ftpWatts = 250, maxHr = 185, restingHr = 50, age = 40, sex = Sex.MALE)
    private val settings = FuelSettings()

    @Test
    fun `power converts to roughly 3_6 kcal per watt-hour`() {
        val kcal = EnergyModel.kcalPerHourFromPower(200.0, 0.24)
        assertEquals(717.0, kcal, 1.0)
    }

    @Test
    fun `keytel formula gives plausible values`() {
        // 150 bpm, 75 kg, 40 years, male -> about 15 kcal/min
        val kcalPerHour = EnergyModel.kcalPerHourFromHeartRate(150.0, rider)
        assertTrue(kcalPerHour in 850.0..950.0, "was $kcalPerHour")
        // very low heart rate -> not below resting metabolism
        assertEquals(75.0, EnergyModel.kcalPerHourFromHeartRate(40.0, rider), 0.01)
    }

    @Test
    fun `carb fraction rises with intensity`() {
        assertEquals(0.25, EnergyModel.carbFraction(0.2), 1e-9)
        assertEquals(0.60, EnergyModel.carbFraction(0.65), 1e-9)
        assertEquals(0.95, EnergyModel.carbFraction(1.2), 1e-9)
    }

    @Test
    fun `power wins over heart rate`() {
        val e = EnergyModel.estimateEnergy(200.0, 170.0, rider, settings)
        assertEquals(EnergySource.POWER, e?.source)
        val hrOnly = EnergyModel.estimateEnergy(null, 150.0, rider, settings)
        assertEquals(EnergySource.HEART_RATE, hrOnly?.source)
        assertNull(EnergyModel.estimateEnergy(null, null, rider, settings))
    }

    @Test
    fun `no gel during the start phase`() {
        val tracker = FuelTracker()
        repeat(29 * 60) { assertTrue(!tracker.tick(1.0, 200.0, null, rider, settings).gelDue) }
        assertEquals(0.0, tracker.state.fuelDueG)
        assertEquals(29 * 60 * 717.0 / 3600.0, tracker.state.kcalTotal, 2.0)
    }

    @Test
    fun `two hour ride at 200 W gives about 60 g per hour after the start phase`() {
        val tracker = FuelTracker()
        val gelTimes = mutableListOf<Double>()
        repeat(2 * 3600) {
            if (tracker.tick(1.0, 200.0, 140.0, rider, settings).gelDue) {
                gelTimes += tracker.state.rideSeconds / 60.0
                tracker.registerGel(settings)
            }
        }
        // 200 W / 250 W FTP = 80 % -> carb fraction 0.75 -> ~134 g/h burned -> 60 % = ~81 g/h, capped at 60 g/h
        // -> one gel (25 g) every 25 min, starting after 30 min: 55, 80, 105 min
        assertEquals(listOf(55, 80, 105), gelTimes.map { it.toInt() })
        assertEquals(3, tracker.state.gelsTaken)
        assertEquals(75.0, tracker.state.carbsEatenG, 1e-9)
    }

    @Test
    fun `forecast counts down to the next gel`() {
        val tracker = FuelTracker()
        repeat(40 * 60) { tracker.tick(1.0, 200.0, null, rider, settings) }
        val minutes = assertNotNull(tracker.minutesToNextGel(rider, settings))
        // 10 min into fueling at 60 g/h -> 10 g due, 15 g missing -> 15 min
        assertEquals(15.0, minutes, 0.5)
    }

    @Test
    fun `undoing a gel reminds again after the minimum spacing`() {
        val tracker = FuelTracker()
        var alerts = 0
        repeat(70 * 60) {
            if (tracker.tick(1.0, 200.0, null, rider, settings).gelDue) {
                alerts++
                tracker.registerGel(settings)
                if (alerts == 1) tracker.undoLastGel(settings)
            }
        }
        // first alert at 55 min (undone), reminder at 65 min
        assertEquals(2, alerts)
        assertEquals(1, tracker.state.gelsTaken)
    }

    @Test
    fun `without sensors no energy is counted`() {
        val tracker = FuelTracker()
        repeat(600) { tracker.tick(1.0, null, null, rider, settings) }
        assertEquals(0.0, tracker.state.kcalTotal)
        assertEquals(600.0, tracker.state.rideSeconds)
        assertNull(tracker.minutesToNextGel(rider, settings))
    }

    private val glycogenSettings = FuelSettings(startMode = StartMode.GLYCOGEN)

    @Test
    fun `glycogen store is estimated from body weight`() {
        assertEquals(450.0, EnergyModel.glycogenStoreG(rider, glycogenSettings), 1e-9)
        assertEquals(500.0, EnergyModel.glycogenStoreG(rider, glycogenSettings.copy(glycogenStoreG = 500.0)), 1e-9)
    }

    @Test
    fun `glycogen mode starts fueling earlier when riding hard`() {
        // 200 W: ~134 g carbs/h -> 15 % of 450 g (67.5 g) used after ~30 min
        assertEquals(30, fuelingStartMinute(200.0))
        // 120 W: ~46 g carbs/h -> only after ~87 min
        assertEquals(87, fuelingStartMinute(120.0))
    }

    private fun fuelingStartMinute(watts: Double): Int {
        val tracker = FuelTracker()
        repeat(4 * 3600) {
            tracker.tick(1.0, watts, null, rider, glycogenSettings)
            if (tracker.isFuelingActive(rider, glycogenSettings)) return (tracker.state.rideSeconds / 60).toInt()
        }
        error("fueling never started")
    }

    @Test
    fun `glycogen mode forecast includes the wait for the start`() {
        val tracker = FuelTracker()
        repeat(15 * 60) { tracker.tick(1.0, 200.0, null, rider, glycogenSettings) }
        // ~15 min until fueling starts + 25 min until the first gel at 60 g/h
        assertEquals(40.0, assertNotNull(tracker.minutesToNextGel(rider, glycogenSettings)), 1.5)
    }

    @Test
    fun `low glycogen warning appears once`() {
        val tracker = FuelTracker()
        val warnings = mutableListOf<Int>()
        repeat(5 * 3600) {
            val result = tracker.tick(1.0, 200.0, null, rider, settings)
            if (result.gelDue) tracker.registerGel(settings)
            if (result.glycogenLow) warnings += (tracker.state.rideSeconds / 60).toInt()
        }
        assertEquals(1, warnings.size)
        // net loss ~134 g/h burned minus 60 g/h eaten -> below 25 % of 450 g after roughly 4 h
        assertTrue(warnings.single() in 210..270, "was ${warnings.single()}")
        assertTrue(tracker.glycogenFraction(rider, settings) < 0.25)
    }
}
