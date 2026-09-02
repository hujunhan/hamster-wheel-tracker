package com.hujunhan.hamsterwheeltracker.vision

import android.content.Context

class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): CalibrationConfig = CalibrationConfig(
        centerXNorm = prefs.getFloat(KEY_CENTER_X, 0.5f),
        centerYNorm = prefs.getFloat(KEY_CENTER_Y, 0.5f),
        wheelRadiusNorm = prefs.getFloat(KEY_WHEEL_RADIUS, 0.38f),
        markerPathRadiusRatio = prefs.getFloat(KEY_MARKER_PATH, 0.75f),
        radiusToleranceRatio = prefs.getFloat(KEY_RADIUS_TOLERANCE, 0.12f),
        hsvLowerH = prefs.getInt(KEY_H_LOWER, 40),
        hsvUpperH = prefs.getInt(KEY_H_UPPER, 80),
        hsvLowerS = prefs.getInt(KEY_S_LOWER, 80),
        hsvLowerV = prefs.getInt(KEY_V_LOWER, 50),
        effectiveDiameterMm = prefs.getFloat(KEY_DIAMETER_MM, 228.6f),
    )

    fun save(config: CalibrationConfig) {
        prefs.edit()
            .putFloat(KEY_CENTER_X, config.centerXNorm)
            .putFloat(KEY_CENTER_Y, config.centerYNorm)
            .putFloat(KEY_WHEEL_RADIUS, config.wheelRadiusNorm)
            .putFloat(KEY_MARKER_PATH, config.markerPathRadiusRatio)
            .putFloat(KEY_RADIUS_TOLERANCE, config.radiusToleranceRatio)
            .putInt(KEY_H_LOWER, config.hsvLowerH)
            .putInt(KEY_H_UPPER, config.hsvUpperH)
            .putInt(KEY_S_LOWER, config.hsvLowerS)
            .putInt(KEY_V_LOWER, config.hsvLowerV)
            .putFloat(KEY_DIAMETER_MM, config.effectiveDiameterMm)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "wheel_calibration"
        private const val KEY_CENTER_X = "center_x"
        private const val KEY_CENTER_Y = "center_y"
        private const val KEY_WHEEL_RADIUS = "wheel_radius"
        private const val KEY_MARKER_PATH = "marker_path_radius"
        private const val KEY_RADIUS_TOLERANCE = "radius_tolerance"
        private const val KEY_H_LOWER = "h_lower"
        private const val KEY_H_UPPER = "h_upper"
        private const val KEY_S_LOWER = "s_lower"
        private const val KEY_V_LOWER = "v_lower"
        private const val KEY_DIAMETER_MM = "effective_diameter_mm"
    }
}
