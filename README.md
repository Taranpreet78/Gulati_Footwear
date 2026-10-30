# Gulati Footwear Billing (Android)

Offline-first Android Studio project for the Redmi 9 Power.

## Included
- Barcode scanning using ZXing (camera permission required)
- Local Room/SQLite product inventory
- Add/edit product details when a barcode is first scanned
- Cart with quantity, editable selling price, discounts and optional GST
- 80 mm receipt-style PDF with dynamic height
- Invoice history stored locally
- Share PDF through Android Sharesheet (WhatsApp, email, etc.)
- Basic stock decrement after bill creation
- Shop details and UPI ID printed on the receipt

## Build an APK
1. Install Android Studio (current stable) on a Windows computer.
2. Open this folder (`GulatiFootwear`) in Android Studio.
3. Let Gradle sync and download dependencies (internet required for the first build).
4. Connect a phone or use an emulator to test.
5. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
6. The debug APK will be under `app/build/outputs/apk/debug/app-debug.apk`.
7. Transfer it to your Redmi 9 Power and open it to install. You may need to allow installs from that file manager.

This chat environment does not have an Android SDK/Gradle build environment available, so the APK has not been compiled or tested here. This is the complete source project, not an APK. Please test with sample products before using it for real sales.

## Important notes
- Barcode values are identifiers; they do not contain price data. On first scan, enter product name, size, selling price, purchase price and stock. Later scans retrieve the saved record.
- This is a basic retail bill generator, not a certified GST accounting package. GST is optional and can be set per bill; consult your accountant before using tax invoices.
- All product and bill data stays on this phone. Export/backup and restore should be added before relying on it as the only record.
- The UPI ID is printed as text; a dynamic payment QR is not generated in this starter version.
- The invoice PDF width is 80 mm; height is estimated from the number of lines to create a long receipt.
