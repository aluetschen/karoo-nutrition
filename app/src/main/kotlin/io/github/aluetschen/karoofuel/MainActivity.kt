package io.github.aluetschen.karoofuel

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import io.github.aluetschen.karoofuel.core.EnergyModel
import io.github.aluetschen.karoofuel.core.Sex
import io.github.aluetschen.karoofuel.core.StartMode
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.UserProfile
import kotlin.math.roundToInt

/** Settings screen, opened from the app list on the Karoo. */
class MainActivity : Activity() {
    private val karooSystem by lazy { KarooSystemService(this) }
    private var profileConsumer: String? = null
    private lateinit var prefs: FuelPrefs

    private lateinit var age: EditText
    private lateinit var male: RadioButton
    private lateinit var female: RadioButton
    private lateinit var gelCarbs: EditText
    private lateinit var maxCarbs: EditText
    private lateinit var startMode: RadioGroup
    private lateinit var startAfter: EditText
    private lateinit var glycogenDepletion: EditText
    private lateinit var glycogenStore: EditText
    private lateinit var glycogenStoreHint: TextView
    private lateinit var replaceShare: EditText
    private lateinit var profileInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = FuelPrefs(this)

        age = findViewById(R.id.age)
        male = findViewById(R.id.sex_male)
        female = findViewById(R.id.sex_female)
        gelCarbs = findViewById(R.id.gel_carbs)
        maxCarbs = findViewById(R.id.max_carbs)
        startMode = findViewById(R.id.start_mode)
        startAfter = findViewById(R.id.start_after)
        glycogenDepletion = findViewById(R.id.glycogen_depletion)
        glycogenStore = findViewById(R.id.glycogen_store)
        glycogenStoreHint = findViewById(R.id.glycogen_store_hint)
        replaceShare = findViewById(R.id.replace_share)
        profileInfo = findViewById(R.id.profile_info)

        val s = prefs.settings()
        age.setText(prefs.age().toString())
        if (prefs.sex() == Sex.FEMALE) female.isChecked = true else male.isChecked = true
        gelCarbs.setText(s.gelCarbsG.roundToInt().toString())
        maxCarbs.setText(s.maxCarbsPerHourG.roundToInt().toString())
        startMode.check(if (s.startMode == StartMode.GLYCOGEN) R.id.start_mode_glycogen else R.id.start_mode_time)
        startAfter.setText(s.startAfterMinutes.roundToInt().toString())
        glycogenDepletion.setText((s.glycogenStartDepletion * 100).roundToInt().toString())
        glycogenStore.setText(s.glycogenStoreG.roundToInt().toString())
        replaceShare.setText((s.carbReplacementShare * 100).roundToInt().toString())

        startMode.setOnCheckedChangeListener { _, _ -> updateModeVisibility() }
        updateModeVisibility()
        findViewById<Button>(R.id.save).setOnClickListener { save() }
    }

    /** Show only the fields of the selected start mode. */
    private fun updateModeVisibility() {
        val glycogen = startMode.checkedRadioButtonId == R.id.start_mode_glycogen
        findViewById<View>(R.id.group_time).visibility = if (glycogen) View.GONE else View.VISIBLE
        findViewById<View>(R.id.group_glycogen).visibility = if (glycogen) View.VISIBLE else View.GONE
    }

    override fun onStart() {
        super.onStart()
        karooSystem.connect { connected ->
            if (connected && profileConsumer == null) {
                profileConsumer = karooSystem.addConsumer { p: UserProfile ->
                    runOnUiThread {
                        profileInfo.text = getString(R.string.profile_info, p.weight.roundToInt(), p.ftp, p.restingHr, p.maxHr)
                        val autoStore = (p.weight * EnergyModel.GLYCOGEN_G_PER_KG).roundToInt()
                        glycogenStoreHint.text = getString(R.string.glycogen_store_hint, autoStore)
                    }
                }
            }
        }
    }

    override fun onStop() {
        profileConsumer?.let { karooSystem.removeConsumer(it) }
        profileConsumer = null
        karooSystem.disconnect()
        super.onStop()
    }

    private fun save() {
        fun EditText.number(min: Double, max: Double): Double? = text.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it in min..max }

        val ageValue = age.number(10.0, 100.0)
        val gel = gelCarbs.number(5.0, 100.0)
        val maxPerHour = maxCarbs.number(10.0, 150.0)
        val start = startAfter.number(0.0, 180.0)
        val depletion = glycogenDepletion.number(0.0, 80.0)
        val store = glycogenStore.number(0.0, 1500.0)
        val share = replaceShare.number(10.0, 100.0)
        if (ageValue == null || gel == null || maxPerHour == null || start == null || depletion == null || store == null || share == null) {
            Toast.makeText(this, R.string.invalid_input, Toast.LENGTH_LONG).show()
            return
        }
        prefs.saveSettings(
            age = ageValue.roundToInt(),
            sex = if (female.isChecked) Sex.FEMALE else Sex.MALE,
            gelCarbsG = gel,
            maxCarbsPerHourG = maxPerHour,
            startMode = if (startMode.checkedRadioButtonId == R.id.start_mode_glycogen) StartMode.GLYCOGEN else StartMode.TIME,
            startAfterMinutes = start,
            glycogenStartDepletion = depletion / 100.0,
            glycogenStoreG = store,
            replaceShare = share / 100.0,
        )
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }
}
