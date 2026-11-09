package app.minerguru.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.text.format.DateFormat
import android.util.SizeF
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.IdRes
import app.minerguru.BuildConfig
import app.minerguru.MainActivity
import app.minerguru.R
import app.minerguru.model.L
import app.minerguru.model.MinerFormatting
import app.minerguru.service.MinerMonitor
import java.util.Date

/**
 * The widget look follows the launcher it sits on. Shape (corner radius), font and the light/dark switch come from
 * the system on every device; the colors follow the house style: Material You tones on Pixel and most launchers,
 * a neutral see-through card with the palette's accent on Samsung's One UI.
 */
enum class WidgetSkin {
    MATERIAL, ONE_UI;

    companion object {
        fun current(context: Context): WidgetSkin {
            if (BuildConfig.DEBUG) {
                // Lets a test on the emulator show the One UI look: adb shell setprop debug.minerguru.skin ONE_UI
                debugOverride()?.let { return it }
            }
            return if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) ONE_UI else MATERIAL
        }

        private fun debugOverride(): WidgetSkin? = runCatching {
            val value = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "debug.minerguru.skin") as String
            WidgetSkin.valueOf(value)
        }.getOrNull()
    }
}

/** The color roles a widget uses; One UI swaps the Material You resources for its own. */
private enum class Role(@ColorRes val material: Int, @ColorRes val oneUi: Int) {
    SURFACE(R.color.wg_surface, R.color.wg_oneui_surface),
    INNER(R.color.wg_inner, R.color.wg_oneui_inner),
    TEXT(R.color.wg_text, R.color.wg_oneui_text),
    SUB(R.color.wg_sub, R.color.wg_oneui_sub),
    ACCENT(R.color.wg_accent, R.color.wg_oneui_accent),
    ACCENT_CONTAINER(R.color.wg_accent_container, R.color.wg_oneui_accent_container),
    ON_ACCENT_CONTAINER(R.color.wg_on_accent_container, R.color.wg_oneui_on_accent_container),
    TRACK(R.color.wg_track, R.color.wg_oneui_track);

    fun color(skin: WidgetSkin) = if (skin == WidgetSkin.ONE_UI) oneUi else material
}

private enum class Tint(val method: String) { BACKGROUND("setBackgroundTintList"), IMAGE("setImageTintList"), TEXT("setTextColor"), TRACK("setProgressBackgroundTintList") }

/** Builds the RemoteViews of all three widgets from the snapshot. */
object MinerWidgets {
    /** Older data is marked with its time and the status blocks turn to "checking". */
    private const val SHOW_AGE_AFTER_MS = 15 * 60_000L

    fun update(context: Context, manager: AppWidgetManager, provider: Class<out AppWidgetProvider>, ids: IntArray) {
        MinerMonitor.applyLanguage(context)
        val snapshot = WidgetSnapshot.load(context)
        val skin = WidgetSkin.current(context)
        for (id in ids) {
            val views = when (provider) {
                FleetWidget::class.java -> fleet(context, snapshot, skin, id)
                MinerWidget::class.java -> miner(context, snapshot, skin, MinerWidget.chosenMiner(context, id))
                else -> solo(context, snapshot, skin)
            }
            manager.updateAppWidget(id, views)
        }
        WidgetBridge.refreshIfStale(context, snapshot)
    }

    // region Fleet

    private fun fleet(context: Context, s: WidgetSnapshot?, skin: WidgetSkin, widgetId: Int): RemoteViews {
        if (s == null || s.miners.isEmpty()) return empty(context, skin)
        val open = openIntent(context, "overview", 0)
        return RemoteViews(
            mapOf(
                SizeF(110f, 40f) to fleetSmall(context, s, skin).also { it.setOnClickPendingIntent(android.R.id.background, open) },
                SizeF(110f, 110f) to fleetSquare(context, s, skin).also { it.setOnClickPendingIntent(android.R.id.background, open) },
                SizeF(250f, 110f) to fleetWide(context, s, skin).also { it.setOnClickPendingIntent(android.R.id.background, open) },
                SizeF(250f, 250f) to fleetList(context, s, skin, widgetId).also { it.setOnClickPendingIntent(android.R.id.background, open) }
            )
        )
    }

    private fun fleetSmall(context: Context, s: WidgetSnapshot, skin: WidgetSkin) = RemoteViews(context.packageName, R.layout.widget_fleet_s).apply {
        hashrate(this, s.totalHashRateGHs, stale(s))
        setImageViewResource(R.id.wg_status, statusIcon(fleetLook(s)))
        setTextViewText(R.id.wg_status_text, if (stale(s)) asOf(context, s) else problemOr(s, onlineText(s)))
        paint(this, skin, MARK + listOf(R.id.wg_value to Role.TEXT, R.id.wg_unit to Role.SUB, R.id.wg_status_text to Role.SUB))
    }

    private fun fleetSquare(context: Context, s: WidgetSnapshot, skin: WidgetSkin) = RemoteViews(context.packageName, R.layout.widget_fleet_m).apply {
        hashrate(this, s.totalHashRateGHs, stale(s))
        setImageViewResource(R.id.wg_status, statusIcon(fleetLook(s)))
        val watts = if (s.totalPowerW > 0) " · " + MinerFormatting.format0(s.totalPowerW) + " W" else ""
        setTextViewText(R.id.wg_status_text, if (stale(s)) asOf(context, s) else problemOr(s, onlineText(s) + watts))
        spark(this, s, 640, 100)
        paint(this, skin, MARK + listOf(R.id.wg_value to Role.TEXT, R.id.wg_unit to Role.SUB, R.id.wg_status_text to Role.SUB, R.id.wg_spark to Role.ACCENT))
    }

    private fun fleetWide(context: Context, s: WidgetSnapshot, skin: WidgetSkin) = RemoteViews(context.packageName, R.layout.widget_fleet_l).apply {
        setTextViewText(R.id.wg_title, L("Flotte", "Fleet"))
        hashrate(this, s.totalHashRateGHs, stale(s))
        setImageViewResource(R.id.wg_status, statusIcon(fleetLook(s)))
        setTextViewText(R.id.wg_status_text, if (stale(s)) asOf(context, s) else problemOr(s, onlineText(s)))
        spark(this, s, 600, 150)
        chips(this, s)
        paint(this, skin, MARK + PILL + CHIPS + listOf(
            R.id.wg_title to Role.TEXT, R.id.wg_value to Role.TEXT, R.id.wg_unit to Role.SUB, R.id.wg_spark to Role.ACCENT
        ))
    }

    private fun fleetList(context: Context, s: WidgetSnapshot, skin: WidgetSkin, widgetId: Int) = RemoteViews(context.packageName, R.layout.widget_fleet_xl).apply {
        setTextViewText(R.id.wg_title, L("Flotte", "Fleet"))
        val watts = if (s.totalPowerW > 0) " · " + MinerFormatting.format0(s.totalPowerW) + " W" else ""
        setTextViewText(R.id.wg_value, if (stale(s)) asOf(context, s) else MinerFormatting.formatHashrate(s.totalHashRateGHs) + watts)
        setImageViewResource(R.id.wg_status, statusIcon(fleetLook(s)))
        setTextViewText(R.id.wg_status_text, "${s.online.size}/${s.miners.size}")
        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        s.miners.forEachIndexed { i, m ->
            val row = RemoteViews(context.packageName, R.layout.widget_fleet_row).apply {
                setImageViewResource(R.id.wg_status, statusIcon(if (stale(s)) Look.CHECKING else look(s, m)))
                setTextViewText(R.id.wg_name, m.name)
                setTextViewText(R.id.wg_rate, if (m.state == WidgetSnapshot.Miner.State.OFFLINE) "offline" else MinerFormatting.formatHashrate(m.hashRateGHs))
                setTextViewText(R.id.wg_temp, m.tempC?.takeIf { m.state == WidgetSnapshot.Miner.State.ONLINE }?.let { MinerFormatting.format0(it) + " °C" } ?: "")
                setOnClickFillInIntent(R.id.wg_row, Intent().putExtra(MainActivity.EXTRA_ROUTE, "miner:${m.id}"))
                paint(this, skin, listOf(R.id.wg_name to Role.TEXT, R.id.wg_rate to Role.TEXT, R.id.wg_temp to Role.SUB))
            }
            items.addItem(i.toLong(), row)
        }
        setRemoteAdapter(R.id.wg_list, items.build())
        setPendingIntentTemplate(R.id.wg_list, PendingIntent.getActivity(
            context, 1000 + widgetId, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        ))
        paint(this, skin, MARK + PILL + listOf(R.id.wg_title to Role.TEXT, R.id.wg_value to Role.SUB, R.id.wg_list_bg to Role.INNER))
    }

    private fun chips(views: RemoteViews, s: WidgetSnapshot) = views.apply {
        setTextViewText(R.id.wg_c1l, L("Leistung", "Power"))
        setTextViewText(R.id.wg_c1v, if (s.totalPowerW > 0) MinerFormatting.format0(s.totalPowerW) + " W" else "–")
        setTextViewText(R.id.wg_c2l, L("Effizienz", "Efficiency"))
        setTextViewText(R.id.wg_c2v, s.efficiencyJPerTH?.let { MinerFormatting.format1(it) + " J/TH" } ?: "–")
        setTextViewText(R.id.wg_c3l, "Best Diff")
        setTextViewText(R.id.wg_c3v, s.bestDiff ?: "–")
    }

    // endregion

    // region Miner and solo

    private fun miner(context: Context, s: WidgetSnapshot?, skin: WidgetSkin, chosen: String?): RemoteViews {
        val m = s?.miners?.let { list -> list.firstOrNull { it.id == chosen } ?: list.maxByOrNull { it.hashRateGHs } } ?: return empty(context, skin)
        return RemoteViews(context.packageName, R.layout.widget_miner).apply {
            val stale = stale(s)
            val look = if (stale) Look.CHECKING else look(s, m)
            setImageViewResource(R.id.wg_status, statusIcon(look))
            setTextViewText(R.id.wg_name, m.name)
            if (m.state == WidgetSnapshot.Miner.State.OFFLINE && !stale) {
                setTextViewText(R.id.wg_value, "offline")
                setTextViewText(R.id.wg_unit, "")
                setTextViewText(R.id.wg_temp, m.host)
                setViewVisibility(R.id.wg_bar, android.view.View.GONE)
            } else {
                hashrate(this, m.hashRateGHs, stale)
                setTextViewText(R.id.wg_temp, if (stale) asOf(context, s) else m.tempC?.let { MinerFormatting.format0(it) + " °C" } ?: "–")
                val temp = m.tempC
                if (temp != null && !stale) {
                    // Scale from 30 °C to 10 °C past the warning threshold, like the Mac widget.
                    val low = 30.0
                    val high = s.tempWarningC + 10
                    setProgressBar(R.id.wg_bar, 100, (((temp - low) / (high - low)).coerceIn(0.04, 1.0) * 100).toInt(), false)
                    setColorStateList(R.id.wg_bar, "setProgressTintList", if (look == Look.HOT) R.color.wg_warning else R.color.wg_good)
                } else {
                    setViewVisibility(R.id.wg_bar, android.view.View.GONE)
                }
            }
            setOnClickPendingIntent(android.R.id.background, openIntent(context, "miner:${m.id}", m.id.hashCode()))
            paint(this, skin, listOf(
                R.id.wg_icon_bg to Role.INNER, R.id.wg_name to Role.TEXT, R.id.wg_value to Role.TEXT,
                R.id.wg_unit to Role.SUB, R.id.wg_temp to Role.SUB
            ))
            paintWith(this, skin, R.id.wg_bar, Role.TRACK, Tint.TRACK)
        }
    }

    private fun solo(context: Context, s: WidgetSnapshot?, skin: WidgetSkin): RemoteViews {
        if (s == null || s.solo.isEmpty()) return empty(context, skin, L("Keine Solo-Chance", "No solo chance"), L("Kein Miner online", "No miner online"))
        val open = openIntent(context, "overview", 0)
        if (s.solo.size == 1) return soloOne(context, s, s.solo[0], skin).also { it.setOnClickPendingIntent(android.R.id.background, open) }
        // Two coins fit at 2×1; taller widgets show more, Bitcoin and the largest first.
        return RemoteViews(mapOf(
            SizeF(110f, 40f) to soloRows(context, s, skin, 2).also { it.setOnClickPendingIntent(android.R.id.background, open) },
            SizeF(110f, 100f) to soloRows(context, s, skin, 5).also { it.setOnClickPendingIntent(android.R.id.background, open) }
        ))
    }

    private fun soloOne(context: Context, s: WidgetSnapshot, solo: WidgetSnapshot.Solo, skin: WidgetSkin) =
        RemoteViews(context.packageName, R.layout.widget_solo).apply {
            setTextViewText(R.id.wg_label, L("Solo-Chance", "Solo chance") + " · " + solo.coin)
            setTextViewText(R.id.wg_value, solo.odds)
            setTextViewText(R.id.wg_status_text, if (stale(s)) asOf(context, s) else solo.period)
            setColorStateList(R.id.wg_coin, Tint.IMAGE.method, coinColor(solo.key))
            paint(this, skin, listOf(R.id.wg_label to Role.SUB, R.id.wg_value to Role.TEXT, R.id.wg_status_text to Role.SUB))
        }

    private fun soloRows(context: Context, s: WidgetSnapshot, skin: WidgetSkin, max: Int) =
        RemoteViews(context.packageName, R.layout.widget_solo_multi).apply {
            val period = if (stale(s)) asOf(context, s) else s.solo.first().period
            setTextViewText(R.id.wg_label, L("Solo-Chance", "Solo chance") + " · " + period)
            paint(this, skin, listOf(R.id.wg_label to Role.SUB))
            removeAllViews(R.id.wg_rows)
            for (solo in s.solo.take(max)) {
                addView(R.id.wg_rows, RemoteViews(context.packageName, R.layout.widget_solo_row).apply {
                    setTextViewText(R.id.wg_label, solo.coin)
                    setTextViewText(R.id.wg_value, solo.odds)
                    setColorStateList(R.id.wg_coin, Tint.IMAGE.method, coinColor(solo.key))
                    paint(this, skin, listOf(R.id.wg_label to Role.SUB, R.id.wg_value to Role.TEXT))
                })
            }
        }

    @ColorRes
    private fun coinColor(key: String) = when (key) {
        "BTC" -> R.color.wg_coin_btc
        "BCH" -> R.color.wg_coin_bch
        "DGB" -> R.color.wg_coin_dgb
        "XEC" -> R.color.wg_coin_xec
        "BSV" -> R.color.wg_coin_bsv
        "PPC" -> R.color.wg_coin_ppc
        "NMC" -> R.color.wg_coin_nmc
        "FB" -> R.color.wg_coin_fb
        else -> R.color.wg_coin_unknown
    }

    private fun empty(
        context: Context, skin: WidgetSkin,
        title: String = L("Keine Miner", "No miners"),
        hint: String = L("In MinerGuru hinzufügen", "Add them in MinerGuru")
    ) = RemoteViews(context.packageName, R.layout.widget_empty).apply {
        setTextViewText(R.id.wg_title, title)
        setTextViewText(R.id.wg_status_text, hint)
        setOnClickPendingIntent(android.R.id.background, openIntent(context, "overview", 0))
        paint(this, skin, MARK + listOf(R.id.wg_title to Role.TEXT, R.id.wg_status_text to Role.SUB))
    }

    // endregion

    // region Pieces

    private enum class Look { ONLINE, HOT, CHECKING, OFFLINE }

    private fun look(s: WidgetSnapshot, m: WidgetSnapshot.Miner) = when (m.state) {
        WidgetSnapshot.Miner.State.ONLINE -> if (s.isHot(m)) Look.HOT else Look.ONLINE
        WidgetSnapshot.Miner.State.OFFLINE -> Look.OFFLINE
        WidgetSnapshot.Miner.State.CHECKING -> Look.CHECKING
    }

    /** The worst state in the fleet: offline before hot before online. */
    private fun fleetLook(s: WidgetSnapshot) = when {
        stale(s) -> Look.CHECKING
        s.offline.isNotEmpty() -> Look.OFFLINE
        s.hot.isNotEmpty() -> Look.HOT
        s.online.isNotEmpty() -> Look.ONLINE
        else -> Look.CHECKING
    }

    private fun statusIcon(look: Look) = when (look) {
        Look.ONLINE -> R.drawable.wg_status_online
        Look.HOT -> R.drawable.wg_status_hot
        Look.CHECKING -> R.drawable.wg_status_checking
        Look.OFFLINE -> R.drawable.wg_status_offline
    }

    private fun stale(s: WidgetSnapshot) = System.currentTimeMillis() - s.time > SHOW_AGE_AFTER_MS

    private fun asOf(context: Context, s: WidgetSnapshot) = L("Stand", "As of") + " " + DateFormat.getTimeFormat(context).format(Date(s.time))

    private fun onlineText(s: WidgetSnapshot) = "${s.online.size}/${s.miners.size} online"

    /** A problem comes first: the hot miner, then the offline one; otherwise [calm]. */
    private fun problemOr(s: WidgetSnapshot, calm: String): String {
        s.hot.firstOrNull()?.let { return "${it.name} · ${MinerFormatting.format0(it.tempC ?: 0.0)} °C" }
        s.offline.firstOrNull()?.let { return "${it.name} offline" }
        return calm
    }

    private fun hashrate(views: RemoteViews, ghs: Double, stale: Boolean) {
        val (value, unit) = MinerFormatting.splitHashrate(ghs)
        views.setTextViewText(R.id.wg_value, value)
        views.setTextViewText(R.id.wg_unit, unit)
        views.setFloat(R.id.wg_value, "setAlpha", if (stale) 0.45f else 1f)
    }

    /** The 24-hour line, drawn white and tinted with the accent; [w]×[h] matches the view's aspect. */
    private fun spark(views: RemoteViews, s: WidgetSnapshot, w: Int, h: Int) {
        // The hourly history ends with the figure shown next to it, so the line also shows after the first hour.
        val now = s.totalHashRateGHs.takeIf { s.online.isNotEmpty() }
        val values = s.fleetHourlyGHs.takeLast(if (now != null) 23 else 24) + listOfNotNull(now)
        if (values.size < 2) {
            views.setViewVisibility(R.id.wg_spark, android.view.View.INVISIBLE)
            return
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        var min = values.min()
        var max = values.max()
        val pad = maxOf((max - min) * 0.18, max * 0.01, 1.0)
        min -= pad; max += pad
        val stroke = h * 0.07f
        val inset = stroke
        val pts = values.mapIndexed { i, v ->
            val x = inset + i * (w - 2 * inset) / (values.size - 1)
            val y = inset + ((max - v) / (max - min) * (h - 2 * inset)).toFloat()
            x to y
        }
        val line = Path().apply {
            moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size) {
                val cx = (pts[i - 1].first + pts[i].first) / 2
                cubicTo(cx, pts[i - 1].second, cx, pts[i].second, pts[i].first, pts[i].second)
            }
        }
        val area = Path(line).apply { lineTo(pts.last().first, h.toFloat()); lineTo(pts[0].first, h.toFloat()); close() }
        canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255) })
        canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        })
        views.setImageViewBitmap(R.id.wg_spark, bitmap)
    }

    private val MARK = listOf(R.id.wg_mark_bg to Role.ACCENT_CONTAINER, R.id.wg_mark_frame to Role.ON_ACCENT_CONTAINER, R.id.wg_mark_chip to Role.ACCENT)
    private val PILL = listOf(R.id.wg_pill to Role.INNER, R.id.wg_status_text to Role.SUB)
    private val CHIPS = listOf(
        R.id.wg_c1 to Role.INNER, R.id.wg_c2 to Role.INNER, R.id.wg_c3 to Role.INNER,
        R.id.wg_c1l to Role.SUB, R.id.wg_c2l to Role.SUB, R.id.wg_c3l to Role.SUB,
        R.id.wg_c1v to Role.TEXT, R.id.wg_c2v to Role.TEXT, R.id.wg_c3v to Role.TEXT
    )

    /** Sets the skin's color resources; the launcher resolves them, so light and dark follow the system. */
    private fun paint(views: RemoteViews, skin: WidgetSkin, roles: List<Pair<Int, Role>>) {
        paintWith(views, skin, android.R.id.background, Role.SURFACE, Tint.BACKGROUND)
        for ((id, role) in roles) {
            val how = when (id) {
                R.id.wg_mark_frame, R.id.wg_mark_chip, R.id.wg_spark -> Tint.IMAGE
                R.id.wg_mark_bg, R.id.wg_pill, R.id.wg_list_bg, R.id.wg_icon_bg, R.id.wg_c1, R.id.wg_c2, R.id.wg_c3 -> Tint.BACKGROUND
                else -> Tint.TEXT
            }
            paintWith(views, skin, id, role, how)
        }
    }

    private fun paintWith(views: RemoteViews, skin: WidgetSkin, @IdRes id: Int, role: Role, how: Tint) {
        // Material is what the layouts carry already; only One UI needs other colors.
        if (skin == WidgetSkin.MATERIAL && id != R.id.wg_bar) return
        views.setColorStateList(id, how.method, role.color(skin))
    }

    private fun openIntent(context: Context, route: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    // endregion

    /** Sample data for the widget picker's previews (Android 15 and later draw them from these views). */
    fun publishPreviews(context: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        val manager = AppWidgetManager.getInstance(context)
        val prefs = context.getSharedPreferences("minerguru", Context.MODE_PRIVATE)
        val skin = WidgetSkin.current(context)
        MinerMonitor.applyLanguage(context)
        // A new install, skin or language makes new pictures; the system allows only a few calls an hour.
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val key = "${BuildConfig.VERSION_CODE}-$installed-$skin-${if (app.minerguru.model.Lang.english) "en" else "de"}"
        if (prefs.getString(PREVIEW_KEY, null) == key) return
        val s = WidgetPreview.snapshot()
        val ok = runCatching {
            manager.setWidgetPreview(ComponentName(context, FleetWidget::class.java), AppWidgetProviderInfo_HOME, fleetWide(context, s, skin))
            manager.setWidgetPreview(ComponentName(context, MinerWidget::class.java), AppWidgetProviderInfo_HOME, miner(context, s, skin, null))
            manager.setWidgetPreview(ComponentName(context, SoloWidget::class.java), AppWidgetProviderInfo_HOME, solo(context, s, skin))
        }.getOrDefault(false)
        if (ok) prefs.edit().putString(PREVIEW_KEY, key).apply()
    }

    private const val PREVIEW_KEY = "widgetPreviews"
    private const val AppWidgetProviderInfo_HOME = android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
}
