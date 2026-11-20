package com.gulatifootwear.billing

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class RestorePayload(
    val products: List<Product>,
    val invoices: List<Invoice>,
    val invoiceItems: List<InvoiceItem>,
    val expenses: List<ShopExpense>,
    val fromDate: String,
    val toDate: String
)

object BackupRestore {
    private const val MAX_JSON_BYTES = 5 * 1024 * 1024

    fun readZip(input: InputStream): RestorePayload {
        var jsonBytes: ByteArray? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                if (entry.name != "backup.json" || jsonBytes != null) {
                    throw IllegalArgumentException("The ZIP must contain only one backup.json file.")
                }
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_JSON_BYTES) throw IllegalArgumentException("Backup file is too large.")
                    out.write(buffer, 0, count)
                }
                jsonBytes = out.toByteArray()
                zip.closeEntry()
            }
        }
        val bytes = jsonBytes ?: throw IllegalArgumentException("backup.json was not found in this ZIP.")
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.optString("app") == "Gulati Footwear") { "This ZIP is not a Gulati Footwear backup." }

        val productsJson = root.optJSONArray("products") ?: throw IllegalArgumentException("Products section is missing.")
        val invoicesJson = root.optJSONArray("invoices") ?: throw IllegalArgumentException("Invoices section is missing.")
        val itemsJson = root.optJSONArray("invoiceItems") ?: throw IllegalArgumentException("Bill items section is missing.")
        val expensesJson = root.optJSONArray("expenses") ?: throw IllegalArgumentException("Expenses section is missing.")

        require(productsJson.length() <= 10000 && invoicesJson.length() <= 100000 &&
            itemsJson.length() <= 500000 && expensesJson.length() <= 100000) { "Backup contains too many records." }

        fun obj(array: org.json.JSONArray, index: Int) =
            array.optJSONObject(index) ?: throw IllegalArgumentException("Invalid record at row ${index + 1}.")

        fun id(o: JSONObject, key: String): Long =
            o.getLong(key).also { require(it > 0) { "Invalid $key in backup." } }

        fun number(o: JSONObject, key: String): Double =
            o.getDouble(key).also { require(it.isFinite()) { "Invalid $key in backup." } }

        val products = (0 until productsJson.length()).map { i ->
            val o = obj(productsJson, i)
            Product(
                id = id(o, "id"),
                barcode = o.getString("barcode").trim().also { require(it.isNotEmpty()) { "A product barcode is blank." } },
                name = o.getString("name"),
                brand = o.optString("brand", ""),
                size = o.optString("size", ""),
                purchasePrice = number(o, "purchasePrice"),
                sellingPrice = number(o, "sellingPrice"),
                stock = o.getInt("stock"),
                imagePath = null
            )
        }
        val invoices = (0 until invoicesJson.length()).map { i ->
            val o = obj(invoicesJson, i)
            Invoice(
                id = id(o, "id"),
                createdAt = id(o, "createdAt"),
                invoiceNo = o.getString("invoiceNo"),
                customerName = o.optString("customerName", ""),
                customerPhone = o.optString("customerPhone", ""),
                subtotal = number(o, "subtotal"),
                discount = number(o, "discount"),
                gstPercent = number(o, "gstPercent"),
                total = number(o, "total"),
                paymentMode = o.optString("paymentMode", "Cash"),
                pdfPath = ""
            )
        }
        val items = (0 until itemsJson.length()).map { i ->
            val o = obj(itemsJson, i)
            InvoiceItem(
                id = id(o, "id"),
                invoiceId = id(o, "invoiceId"),
                createdAt = id(o, "createdAt"),
                productId = o.getLong("productId"),
                barcode = o.optString("barcode", ""),
                productName = o.optString("productName", ""),
                size = o.optString("size", ""),
                quantity = o.getInt("quantity").also { require(it > 0) { "Invalid bill item quantity." } },
                sellingPrice = number(o, "sellingPrice"),
                purchasePrice = number(o, "purchasePrice")
            )
        }
        val expenses = (0 until expensesJson.length()).map { i ->
            val o = obj(expensesJson, i)
            ShopExpense(
                id = id(o, "id"),
                createdAt = id(o, "createdAt"),
                title = o.getString("title"),
                amount = number(o, "amount"),
                note = o.optString("note", "")
            )
        }

        require(products.map { it.id }.toSet().size == products.size) { "Duplicate product IDs in backup." }
        require(products.map { it.barcode }.toSet().size == products.size) { "Duplicate product barcodes in backup." }
        require(invoices.map { it.id }.toSet().size == invoices.size) { "Duplicate invoice IDs in backup." }
        require(invoices.map { it.invoiceNo }.toSet().size == invoices.size) { "Duplicate invoice numbers in backup." }
        require(items.map { it.id }.toSet().size == items.size) { "Duplicate bill-item IDs in backup." }
        require(expenses.map { it.id }.toSet().size == expenses.size) { "Duplicate expense IDs in backup." }
        val invoiceIds = invoices.map { it.id }.toSet()
        require(items.all { it.invoiceId in invoiceIds }) { "A bill item refers to a missing invoice." }
        // A bill item's product may have been deleted later; keep the historical line unchanged.

        return RestorePayload(products, invoices, items, expenses,
            root.optString("from", ""), root.optString("to", ""))
    }
}
