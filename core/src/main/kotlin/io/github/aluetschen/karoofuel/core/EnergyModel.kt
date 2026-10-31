package io.github.aluetschen.karoofuel.core

import kotlin.math.max
import kotlin.math.min

enum class Sex { MALE, FEMALE }

/** Rider data. Weight, FTP and heart-rate values come from the Karoo profile; age and sex from the app settings. */
data class RiderProfile(
    val weightKg: Double,
    val ftpWatts: Int,
    val maxHr: Int,
    val restingHr: Int,
    val age: Int,
    val sex: Sex,
)

/** When fueling starts. */
enum class StartMode {
    /** After a fixed ride time. */
    TIME,
    /** When a share of the estimated glycogen stores has been used. */
    GLYCOGEN,
}

/** User-adjustable fueling settings. */
data class FuelSettings(
    /** Carbohydrates per gel in grams. */
    val gelCarbsG: Double = 25.0,
    /** Upper limit of carbohydrate intake per hour (gut absorption limit), in grams. */
    val maxCarbsPerHourG: Double = 60.0,
    /** How the start of fueling is determined. */
    val startMode: StartMode = StartMode.TIME,
    /** [StartMode.TIME]: ride time before fueling starts (the first part of the ride runs on glycogen stores). */
    val startAfterMinutes: Double = 30.0,
    /** [StartMode.GLYCOGEN]: share of the glycogen stores used before fueling starts (0..1). */
    val glycogenStartDepletion: Double = 0.15,
    /** Size of the glycogen stores in grams; 0 = estimate from body weight. */
    val glycogenStoreG: Double = 0.0,
    /** Below this share of the stores, a one-time warning is shown (0..1). */
    val glycogenWarningLevel: Double = 0.25,
    /** Share of the burned carbohydrates that should be replaced by gels (0..1). */
    val carbReplacementShare: Double = 0.6,
    /** Gross efficiency of cycling: share of metabolic energy that ends up as power on the pedals. */
    val grossEfficiency: Double = 0.24,
    /** Minimum time between two gel alerts, in minutes. */
    val minMinutesBetweenGels: Double = 10.0,
)

/** Where the current energy estimate comes from. */
enum class EnergySource { POWER, HEART_RATE }

data class EnergyEstimate(val kcalPerHour: Double, val source: EnergySource)

/**
 * Pure calculation formulas, without state.
 */
object EnergyModel {
    const val KCAL_PER_G_CARB = 4.0
    private const val JOULE_PER_KCAL = 4184.0

    /**
     * Energy from power: work on the pedals divided by gross efficiency.
     * Example: 200 W at 24 % -> 200 * 3600 / 4184 / 0.24 ≈ 717 kcal/h.
     */
    fun kcalPerHourFromPower(watts: Double, grossEfficiency: Double): Double =
        watts * 3600.0 / JOULE_PER_KCAL / grossEfficiency

    /**
     * Energy from heart rate, formula by Keytel et al. (2005), J Sports Sci 23(3).
     * Returns kcal/h, never less than resting metabolism.
     */
    fun kcalPerHourFromHeartRate(hr: Double, profile: RiderProfile): Double {
        val kjPerMin = when (profile.sex) {
            Sex.MALE -> -55.0969 + 0.6309 * hr + 0.1988 * profile.weightKg + 0.2017 * profile.age
            Sex.FEMALE -> -20.4022 + 0.4472 * hr - 0.1263 * profile.weightKg + 0.074 * profile.age
        }
        val kcalPerHour = kjPerMin / 4.184 * 60.0
        return max(kcalPerHour, restingKcalPerHour(profile.weightKg))
    }

    /** Rough resting metabolism: about 1 kcal per kg body weight per hour. */
    fun restingKcalPerHour(weightKg: Double): Double = weightKg * 1.0

    /**
     * Current energy consumption. Power is preferred because it measures the actual work;
     * heart rate is the fallback without a power meter (or during dropouts).
     */
    fun estimateEnergy(powerW: Double?, hr: Double?, profile: RiderProfile, settings: FuelSettings): EnergyEstimate? = when {
        powerW != null -> EnergyEstimate(
            max(kcalPerHourFromPower(powerW, settings.grossEfficiency), restingKcalPerHour(profile.weightKg)),
            EnergySource.POWER,
        )
        hr != null && hr > 0 -> EnergyEstimate(kcalPerHourFromHeartRate(hr, profile), EnergySource.HEART_RATE)
        else -> null
    }

    /**
     * Intensity as a share of the maximum (0..~1.5):
     * power relative to FTP, otherwise heart-rate reserve (Karvonen).
     */
    fun intensity(powerW: Double?, hr: Double?, profile: RiderProfile): Double? {
        if (powerW != null && profile.ftpWatts > 0) return powerW / profile.ftpWatts
        if (hr != null && profile.maxHr > profile.restingHr && profile.restingHr > 0) {
            return ((hr - profile.restingHr) / (profile.maxHr - profile.restingHr)).coerceAtLeast(0.0)
        }
        return null
    }

    /**
     * Share of energy that comes from carbohydrates ("crossover concept"):
     * at 30 % intensity about 25 %, at 65 % about 60 %, at threshold (100 %) about 95 %.
     */
    fun carbFraction(intensity: Double?): Double {
        if (intensity == null) return 0.6
        val fraction = 0.25 + (intensity - 0.3) * (0.95 - 0.25) / (1.0 - 0.3)
        return fraction.coerceIn(0.25, 0.95)
    }

    /**
     * Glycogen stores (muscle + liver) of a rested, well-fed rider:
     * about 6 g per kg body weight (e.g. 75 kg -> 450 g). Literature range roughly 5-8 g/kg.
     */
    const val GLYCOGEN_G_PER_KG = 6.0

    fun glycogenStoreG(profile: RiderProfile, settings: FuelSettings): Double =
        if (settings.glycogenStoreG > 0) settings.glycogenStoreG else profile.weightKg * GLYCOGEN_G_PER_KG

    /** Recommended carb intake per hour: share of the carb burn, capped by the absorption limit. */
    fun targetCarbsPerHour(carbsBurnedPerHour: Double, settings: FuelSettings): Double =
        min(carbsBurnedPerHour * settings.carbReplacementShare, settings.maxCarbsPerHourG)
}
