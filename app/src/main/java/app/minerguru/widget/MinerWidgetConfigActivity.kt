package app.minerguru.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.minerguru.R
import app.minerguru.model.tr
import app.minerguru.service.MinerMonitor
import app.minerguru.service.MinerStore
import app.minerguru.ui.theme.MinerGuruTheme

/** Asks which miner a newly placed miner widget shows; also opens from the widget's settings later. */
class MinerWidgetConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves the widget unplaced.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()

        MinerMonitor.applyLanguage(this)
        val miners = MinerStore.readPersistedMiners(this)
        val chosen = MinerWidget.chosenMiner(this, widgetId)

        setContent {
            MinerGuruTheme {
                Scaffold(topBar = { TopAppBar(title = { Text(tr(R.string.choose_a_miner)) }) }) { padding ->
                    if (miners.isEmpty()) {
                        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tr(R.string.no_miners_yet), style = MaterialTheme.typography.titleMedium)
                            Text(tr(R.string.add_them_in_minerguru_until))
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
                            items(miners, key = { it.id }) { m ->
                                ListItem(
                                    headlineContent = { Text(m.displayName) },
                                    supportingContent = { Text(m.hardwareTitle + " · " + m.ipAddress) },
                                    trailingContent = if (m.id == chosen) ({ Text(tr(R.string.chosen), color = MaterialTheme.colorScheme.primary) }) else null,
                                    modifier = Modifier.clickable { choose(widgetId, m.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun choose(widgetId: Int, minerId: String) {
        MinerWidget.choose(this, widgetId, minerId)
        MinerWidgets.update(this, AppWidgetManager.getInstance(this), MinerWidget::class.java, intArrayOf(widgetId))
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
