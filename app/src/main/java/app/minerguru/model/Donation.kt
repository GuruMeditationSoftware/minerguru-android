package app.minerguru.model

/** Wallet addresses for voluntary donations (also shown on the website). */
enum class DonationCoin(val symbol: String, val coinName: String, val address: String) {
    BTC("BTC", "Bitcoin", "bc1qc6xpuwkys8jsjt439tfw2eu3fctkx7ad0hkehe"),
    BCH("BCH", "Bitcoin Cash", "bitcoincash:qruvqq2x9vyd9gykre058r4lda4gulpres33nr9aml");

    /** `false` as long as the build carries a placeholder instead of a real address. */
    val isConfigured: Boolean get() = "ADRESSE-EINTRAGEN" !in address

    /** BIP21-style payment URI, so wallet apps open it directly when the QR code is scanned. */
    val paymentUri: String get() = if (this == BTC) "bitcoin:$address" else address
}
