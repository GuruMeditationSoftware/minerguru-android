package app.minerguru.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import app.minerguru.model.DisplayFormat
import app.minerguru.model.SoloChanceTimeframe

/** Fleet: 2×1 up to a list of all miners at 4×3, chosen by the widget's size. */
class FleetWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        MinerWidgets.update(context, manager, FleetWidget::class.java, ids)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        MinerWidgets.update(context, manager, FleetWidget::class.java, intArrayOf(id))
}

/** One miner, chosen when the widget is placed (or the strongest one). */
class MinerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        MinerWidgets.update(context, manager, MinerWidget::class.java, ids)

    override fun onDeleted(context: Context, ids: IntArray) {
        val edit = prefs(context).edit()
        ids.forEach { edit.remove(key(it)) }
        edit.apply()
    }

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("minerguru.widgets", Context.MODE_PRIVATE)
        private fun key(id: Int) = "miner.$id"

        fun chosenMiner(context: Context, widgetId: Int): String? = prefs(context).getString(key(widgetId), null)

        fun choose(context: Context, widgetId: Int, minerId: String) {
            prefs(context).edit().putString(key(widgetId), minerId).apply()
        }
    }
}

/** Solo odds of each coin the fleet mines: one large, or a row per coin. */
class SoloWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        MinerWidgets.update(context, manager, SoloWidget::class.java, ids)
}

/** Made-up but plausible figures for the widget picker. */
object WidgetPreview {
    fun snapshot(): WidgetSnapshot {
        val now = System.currentTimeMillis()
        val day = listOf(16620.0, 16700.0, 16810.0, 16740.0, 16580.0, 16660.0, 16790.0, 16880.0, 16830.0, 16710.0, 16690.0, 16770.0,
            16900.0, 16850.0, 16720.0, 16640.0, 16700.0, 16780.0, 16860.0, 16800.0, 16740.0, 16690.0, 16760.0, 16750.0)
        fun m(name: String, model: String, ghs: Double, temp: Double, watts: Double) = WidgetSnapshot.Miner(
            id = name, name = name, model = model, host = "192.168.1.20", state = WidgetSnapshot.Miner.State.ONLINE,
            hashRateGHs = ghs, tempC = temp, powerW = watts
        )
        return WidgetSnapshot(
            time = now,
            miners = listOf(
                m("Bitaxe Hex", "Hex 701", 8370.0, 56.0, 98.0),
                m("NerdQAxe++", "4× BM1370", 4610.0, 55.0, 72.0),
                m("Bitaxe GT", "GT 801", 2120.0, 56.0, 33.0),
                m("Bitaxe Gamma", "Gamma 601", 1030.0, 56.0, 15.0),
                m("Bitaxe Supra", "Supra 401", 615.0, 54.0, 7.0)
            ),
            tempWarningC = 68.0,
            fleetHourlyGHs = day,
            bestDiff = "886M",
            solo = listOf(
                WidgetSnapshot.Solo("BTC", "BTC", "1 : " + DisplayFormat.integer(829_876), SoloChanceTimeframe.PER_DAY.label, ""),
                WidgetSnapshot.Solo("BCH", "BCH", "1 : " + DisplayFormat.integer(4_412), SoloChanceTimeframe.PER_DAY.label, "")
            )
        )
    }
}
