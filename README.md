# Bybit Futures Analyzer 2.1 beta — Smart Trade Plan

**Source project, not a prebuilt APK.** Android application package `com.btc.analyzer`.

Features:
- Bybit live ticker / 1-level book / 5m, 15m, 1h technical analysis (from v2 guardian beta).
- Search exchange-listed linear/inverse futures instruments.
- **New: chart-based trade plan** (select symbol, LONG/SHORT, interval, entry price, fee %): fetches 200 Bybit historical kline records, excludes the last unfinished candle, locates 5-bar confirmed pivot highs/lows, ATR(14), structural stop and first two chart-based targets, fee-adjusted R:R.
- **New: annotated price chart** with entry, support/resistance, TP1, TP2 and stop lines. Manual tap to recalculate; not streaming on this screen.
- **New: optional watch** price threshold crossing TP/SL via guardian foreground service, checking ~30 seconds, in addition to guardian's 3-timeframe trend alerts. Price check is based on last traded price, NOT mark price or order trigger, so exchange execution may differ.

Not implemented: broker account portfolio sync, auto orders, proven directional accuracy, multi-symbol active simultaneous guardians, advanced CVD/OI scanner and statistical backtest. The app cannot guarantee notifications, particularly on Android 15 background foreground-service limits. ALWAYS put a stop-loss order at the exchange; never rely on tablet alerts as loss protection.

**Data freshness:** main screen live ticker uses WebSocket; chart plan uses a snapshot of closed candles; guardian checks via periodic REST, not tick-by-tick. Internet and Bybit API access are necessary.

**How to build APK:** Upload the contents of this ZIP into the ROOT of a new GitHub repository (not an extra subfolder), commit and open Actions → `Build Bybit Futures APK`; after a successful run download `bybit-futures-analyzer-debug-apk` artifact and extract `app-debug.apk`. Alternatively use Codespaces terminal once ZIP is uploaded: `unzip -o bybit-futures-analyzer-v2.1-smart-plan.zip && git add . && git commit -m "Add Smart Trade Plan" && git push`. GitHub Actions will build debug APK. Builds have not been verified in this environment.

**Technical:** Gradle 8.11.1 via GitHub Actions, Java 17, Android SDK 35, OkHttp 4.12.0. No Bybit secret/API keys needed.

References: https://bybit-exchange.github.io/docs/v5/market/kline and https://bybit-exchange.github.io/docs/v5/market/tickers
