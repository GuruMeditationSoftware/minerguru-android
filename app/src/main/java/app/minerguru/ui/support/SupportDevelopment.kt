package app.minerguru.ui.support

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.minerguru.R
import app.minerguru.model.DonationCoin
import app.minerguru.model.tr
import app.minerguru.ui.theme.MGType
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Quiet line at the top of the overview. A tap opens the addresses. */
@Composable
fun SupportDevelopmentButton(modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = modifier) {
        Icon(Icons.Rounded.FavoriteBorder, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(tr(R.string.support_this_project), style = MaterialTheme.typography.labelMedium)
    }
    if (open) SupportDevelopmentDialog { open = false }
}

@Composable
private fun SupportDevelopmentDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.support_this_project)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(tr(R.string.minerguru_is_free_and_has))
                DonationCoin.entries.forEach { CoinBlock(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.close)) } }
    )
}

@Composable
private fun CoinBlock(coin: DonationCoin) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(coin.coinName, style = MaterialTheme.typography.titleSmall)
        if (!coin.isConfigured) {
            Text(tr(R.string.address_not_set_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        QrCode(coin.paymentUri)
        Text(coin.address, style = MGType.mono(11.sp), textAlign = TextAlign.Center)
        FilledTonalButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(coin.symbol, coin.address))
            copied = true
        }) {
            Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (copied) tr(R.string.copied) else tr(R.string.copy_address))
        }
    }
}

/** Black modules on white, also in dark mode, so every scanner reads it. */
@Composable
private fun QrCode(text: String) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
    }
    Box(Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp)) {
        Canvas(Modifier.size(180.dp)) {
            val cell = size.width / matrix.width
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
