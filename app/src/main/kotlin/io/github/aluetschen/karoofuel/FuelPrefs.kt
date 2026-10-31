package io.github.aluetschen.karoofuel

import android.content.Context
import io.github.aluetschen.karoofuel.core.EnergySource
import io.github.aluetschen.karoofuel.core.FuelSettings
import io.github.aluetschen.karoofuel.core.FuelState
import io.github.aluetschen.karoofuel.core.Sex

/** Stores settings and the current ride state on the device. */
class FuelPrefs(context: Context) {
    private val settingsPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val statePrefs = context.getSharedPreferences("ride_state", Context.MODE_PRIVATE)

    // ---- Settings ----

    fun settings(): FuelSettings {
        val d = FuelSettings()
        return FuelSettings(
            gelCarbsG = settingsPrefs.getFloat(KEY_GEL_CARBS, d.gelCarbsG.toFloat()).toDouble(),
            maxCarbsPerHourG = settingsPrefs.getFloat(KEY_MAX_CARBS, d.maxCarbsPerHourG.toFloat()).toDouble(),
            startAfterMinutes = settingsPrefs.getFloat(KEY_START_AFTER, d.startAfterMinutes.toFloat()).toDouble(),
            carbReplacementShare = settingsPrefs.getFloat(KEY_REPLACE_SHARE, d.carbReplacementShare.toFloat()).toDouble(),
            grossEfficiency = d.grossEfficiency,
            minMinutesBetweenGels = d.minMinutesBetweenGels,
        )
    }

    fun age(): Int = settingsPrefs.getInt(KEY_AGE, 40)

    fun sex(): Sex = if (settingsPrefs.getString(KEY_SEX, Sex.MALE.name) == Sex.FEMALE.name) Sex.FEMALE else Sex.MALE

    fun saveSettings(age: Int, sex: Sex, gelCarbsG: Double, maxCarbsPerHourG: Double, startAfterMinutes: Double, replaceShare: Double) {
        settingsPrefs.edit()
            .putInt(KEY_AGE, age)
            .putString(KEY_SEX, sex.name)
            .putFloat(KEY_GEL_CARBS, gelCarbsG.toFloat())
            .putFloat(KEY_MAX_CARBS, maxCarbsPerHourG.toFloat())
            .putFloat(KEY_START_AFTER, startAfterMinutes.toFloat())
            .putFloat(KEY_REPLACE_SHARE, replaceShare.toFloat())
            .apply()
    }

    // ---- Ride state ----

    fun saveState(s: FuelState) {
        statePrefs.edit()
            .putBoolean("present", true)
            .putFloat("rideSeconds", s.rideSeconds.toFloat())
            .putFloat("kcalTotal", s.kcalTotal.toFloat())
            .putFloat("carbsBurnedG", s.carbsBurnedG.toFloat())
            .putFloat("carbsEatenG", s.carbsEatenG.toFloat())
            .putFloat("fuelDueG", s.fuelDueG.toFloat())
            .putInt("gelsTaken", s.gelsTaken)
            .putFloat("lastGelAtSeconds", s.lastGelAtSeconds?.toFloat() ?: -1f)
            .putFloat("avgTarget", s.avgTargetCarbsPerHour?.toFloat() ?: -1f)
            .putString("source", s.source?.name)
            .apply()
    }

    fun loadState(): FuelState? {
        if (!statePrefs.getBoolean("present", false)) return null
        return FuelState(
            rideSeconds = statePrefs.getFloat("rideSeconds", 0f).toDouble(),
            kcalTotal = statePrefs.getFloat("kcalTotal", 0f).toDouble(),
            carbsBurnedG = statePrefs.getFloat("carbsBurnedG", 0f).toDouble(),
            carbsEatenG = statePrefs.getFloat("carbsEatenG", 0f).toDouble(),
            fuelDueG = statePrefs.getFloat("fuelDueG", 0f).toDouble(),
            gelsTaken = statePrefs.getInt("gelsTaken", 0),
            lastGelAtSeconds = statePrefs.getFloat("lastGelAtSeconds", -1f).takeIf { it >= 0 }?.toDouble(),
            avgTargetCarbsPerHour = statePrefs.getFloat("avgTarget", -1f).takeIf { it >= 0 }?.toDouble(),
            source = statePrefs.getString("source", null)?.let { name -> EnergySource.entries.firstOrNull { it.name == name } },
        )
    }

    fun clearState() {
        statePrefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_AGE = "age"
        private const val KEY_SEX = "sex"
        private const val KEY_GEL_CARBS = "gel_carbs"
        private const val KEY_MAX_CARBS = "max_carbs_per_hour"
        private const val KEY_START_AFTER = "start_after_minutes"
        private const val KEY_REPLACE_SHARE = "replace_share"
    }
}
