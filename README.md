# Gulati Footwear Android App — Version 2.0

Offline-first Android point-of-sale app for Gulati Footwear.

## Version 2.0 additions
- Refreshed green/lime UI, animated screen entry and touch feedback.
- Left navigation drawer.
- Product search during billing (name, barcode, brand and size).
- Excel `.xlsx` import from the phone's file picker.
- Sales and profit dashboard for today, current month and current year.
- Shop expense entry and estimated net profit calculation.
- Purchase price snapshots on new invoice line items.
- Room database migration from schema version 1 to 2, preserving existing products and bills.

Existing functions retained:
- Barcode scanner and manual barcode entry.
- Product inventory and editing.
- Multi-item bills, discount and optional GST.
- 80 mm PDF invoice and sharing.
- Bill history.

## Excel product import format
Use `Product_Import_Template.xlsx` included in this project. The importer reads the **first worksheet** and expects these columns (header matching is case-insensitive):

| Column | Required | Description |
|---|---|---|
| Barcode | Yes | Unique barcode / SKU; format as Text in Excel to preserve leading zeros |
| Product Name | Yes | Product display name |
| Brand | No | Brand |
| Size | No | Shoe size |
| Purchase Price | No | Your cost price |
| Selling Price | Yes | Retail selling price |
| Stock | No | Opening/current stock quantity |

The importer updates a product if its barcode already exists; otherwise it creates a new product. Invalid rows are skipped and a summary is shown. Only `.xlsx` files are supported (not legacy `.xls` files).

## Profit and loss calculation
- Billing/sales: invoice totals for selected period.
- Cost of goods sold: quantity multiplied by purchase price saved on each new sale.
- Gross profit: item margin less bill discounts.
- Estimated net profit: gross profit less recorded shop expenses.

**Important:** The app's original Version 1 invoices did not store purchase-price snapshots for each sold item. The Version 2.0 migration preserves those invoices, but accurate profit reporting starts with bills created in Version 2.0. GST is not counted as profit. Expenses must be entered to be included in net profit.

## Build APK using Codemagic
1. Push the project files to your GitHub repository.
2. Connect the repository in Codemagic.
3. Select workflow `gulati-footwear-android` from `codemagic.yaml`.
4. Start a build on the `main` branch.
5. Download `app-debug.apk` from build artifacts.

The build configuration uses Gradle 8.9 and Java 17. A successful cloud build is still required to verify the APK; this source ZIP is not itself an APK.

## App identity
- Application ID: `com.gulatifootwear.billing`
- Version name: `2.0`
- Minimum Android: Android 6.0 (API 23)
- Shop: Gulati Footwear, Ludhiana
