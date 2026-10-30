package com.gulatifootwear.billing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

private const val SHOP_NAME = "Gulati Footwear"
private const val SHOP_ADDRESS = "Opposite street no. 8, Kot Mangal Singh, Ludhiana"
private const val SHOP_PHONE = "7986156703"
private const val SHOP_UPI = "gulatitaranpreet78@oksbi"

data class CartLine(val product: Product, var qty: Int = 1, var price: Double = product.sellingPrice)

class MainActivity : AppCompatActivity() {
    private lateinit var dao: ShopDao
    private lateinit var body: LinearLayout
    private val cart = mutableListOf<CartLine>()
    private var gstEnabled = false
    private var gstPercent = 5.0
    private var discount = 0.0
    private var paymentMode = "Cash"
    private var customerName = ""
    private var customerPhone = ""
    private val money get() = { n: Double -> "₹" + String.format(Locale("en", "IN"), "%.2f", n) }

    private val scanner = registerForActivityResult(ScanContractCompat()) { barcode ->
        if (!barcode.isNullOrBlank()) handleBarcode(barcode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dao = ShopDatabase.get(this).dao()
        showHome()
    }

    private fun baseScreen(title: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 248, 245))
        }
        val header = TextView(this).apply {
            text = title
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(18, 22, 18, 22)
            setBackgroundColor(Color.rgb(36, 75, 59))
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(header)
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 24)
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun text(value: String, size: Float = 15f): TextView =
        TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(35, 45, 39)); setPadding(2, 7, 2, 7) }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }.also { body.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 5 }) }

    private fun field(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = type
            textSize = 16f
            setSingleLine(true)
        }

    private fun showHome() {
        baseScreen("Gulati Footwear")
        body.addView(text("Offline billing • Inventory • PDF receipts", 14f))
        button("＋ New bill / Scan barcode") { newBill() }
        button("▣ Product inventory") { inventoryScreen() }
        button("▤ Bill history") { historyScreen() }
        button("Shop details") {
            AlertDialog.Builder(this).setTitle("Shop details")
                .setMessage("$SHOP_NAME\n$SHOP_ADDRESS\nPhone: $SHOP_PHONE\nUPI: $SHOP_UPI\n\nReceipt width: 80 mm\nGST: optional")
                .setPositiveButton("OK", null).show()
        }
        body.addView(text("Tip: scan each product once, save its details, and future scans will fetch its saved price.", 14f))
    }

    private fun newBill() {
        cart.clear()
        gstEnabled = false
        gstPercent = 5.0
        discount = 0.0
        customerName = ""
        customerPhone = ""
        paymentMode = "Cash"
        renderBill()
    }

    private fun renderBill() {
        baseScreen("New bill")
        button("▦ Scan barcode") { startScan() }
        button("＋ Add product manually") { manualBarcodeDialog() }
        body.addView(text("Customer (optional)"))
        val nameField = field("Customer name", customerName)
        body.addView(nameField)
        val phoneField = field("Customer phone", customerPhone, InputType.TYPE_CLASS_PHONE)
        body.addView(phoneField)
        body.addView(text("Items"))
        if (cart.isEmpty()) body.addView(text("No items yet. Scan a barcode or add a product manually."))
        cart.forEachIndexed { index, line ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 8, 10, 8); setBackgroundColor(Color.WHITE) }
            row.addView(text("${line.product.name}  ${if (line.product.size.isNotBlank()) "• Size ${line.product.size}" else ""}", 16f))
            row.addView(text("${line.product.barcode}  |  ${money(line.price)} each"))
            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val minus = Button(this).apply { text = "−"; setOnClickListener { if (line.qty > 1) line.qty-- else cart.removeAt(index); renderBill() } }
            val qty = TextView(this).apply { text = "  Qty ${line.qty}  "; textSize = 16f }
            val plus = Button(this).apply { text = "+"; setOnClickListener { line.qty++; renderBill() } }
            val edit = Button(this).apply { text = "Price"; setOnClickListener { editPrice(index) } }
            val remove = Button(this).apply { text = "Remove"; setOnClickListener { cart.removeAt(index); renderBill() } }
            controls.addView(minus); controls.addView(qty); controls.addView(plus); controls.addView(edit); controls.addView(remove)
            row.addView(controls)
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
        }
        body.addView(text("Subtotal: ${money(subtotal())}", 18f))
        val discountField = field("Discount amount (₹)", if (discount > 0) discount.toString() else "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        body.addView(discountField)
        val gstCheck = CheckBox(this).apply { text = "Apply GST"; isChecked = gstEnabled; setTextColor(Color.DKGRAY) }
        body.addView(gstCheck)
        val gstField = field("GST %", gstPercent.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        body.addView(gstField)
        val payment = Spinner(this)
        payment.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Cash", "UPI", "Card", "Other"))
        payment.setSelection(listOf("Cash", "UPI", "Card", "Other").indexOf(paymentMode).coerceAtLeast(0))
        body.addView(text("Payment method"))
        body.addView(payment)
        button("Update total") {
            customerName = nameField.text.toString().trim()
            customerPhone = phoneField.text.toString().trim()
            discount = discountField.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
            gstEnabled = gstCheck.isChecked
            gstPercent = gstField.text.toString().toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 5.0
            paymentMode = payment.selectedItem?.toString() ?: "Cash"
            renderBill()
        }
        body.addView(text("Grand total: ${money(total())}", 22f))
        button("✓ Generate PDF and save bill") {
            customerName = nameField.text.toString().trim()
            customerPhone = phoneField.text.toString().trim()
            discount = discountField.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: discount
            gstEnabled = gstCheck.isChecked
            gstPercent = gstField.text.toString().toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: gstPercent
            paymentMode = payment.selectedItem?.toString() ?: paymentMode
            if (cart.isEmpty()) toast("Add at least one item")
            else createBill()
        }
        button("← Home") { showHome() }
    }

    private fun subtotal() = cart.sumOf { it.qty * it.price }
    private fun taxable() = (subtotal() - discount).coerceAtLeast(0.0)
    private fun total() = taxable() * (1.0 + if (gstEnabled) gstPercent / 100.0 else 0.0)

    private fun startScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCamera.launch(Manifest.permission.CAMERA)
        } else {
            scanner.launch(IntentIntegrator.REQUEST_CODE)
        }
    }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanner.launch(IntentIntegrator.REQUEST_CODE) else toast("Camera permission is needed to scan barcodes")
    }

    private fun handleBarcode(barcode: String) {
        lifecycleScope.launch {
            val product = withContext(Dispatchers.IO) { dao.findByBarcode(barcode) }
            if (product != null) {
                val existing = cart.find { it.product.id == product.id }
                if (existing != null) existing.qty++ else cart.add(CartLine(product))
                renderBill()
            } else {
                addProductDialog(barcode, true)
            }
        }
    }

    private fun manualBarcodeDialog() {
        val input = field("Enter barcode / SKU")
        AlertDialog.Builder(this).setTitle("Add item").setView(input)
            .setPositiveButton("Continue") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isBlank()) toast("Enter a barcode") else handleBarcode(code)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun addProductDialog(barcode: String, addToCart: Boolean) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 4, 20, 0) }
        val name = field("Product name")
        val brand = field("Brand (optional)")
        val size = field("Size (e.g. 8, 9, 42)")
        val buy = field("Purchase price ₹", "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val sell = field("Selling price ₹", "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val stock = field("Opening stock quantity", "1", InputType.TYPE_CLASS_NUMBER)
        listOf(name, brand, size, buy, sell, stock).forEach { panel.addView(it) }
        AlertDialog.Builder(this).setTitle("New product • $barcode").setView(panel)
            .setPositiveButton("Save") { _, _ ->
                val productName = name.text.toString().trim()
                val selling = sell.text.toString().toDoubleOrNull()
                if (productName.isBlank() || selling == null || selling < 0) {
                    toast("Product name and valid selling price are required")
                } else {
                    val product = Product(
                        barcode = barcode, name = productName, brand = brand.text.toString().trim(),
                        size = size.text.toString().trim(),
                        purchasePrice = buy.text.toString().toDoubleOrNull() ?: 0.0,
                        sellingPrice = selling,
                        stock = stock.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    )
                    lifecycleScope.launch {
                        val id = withContext(Dispatchers.IO) { dao.saveProduct(product) }
                        val saved = product.copy(id = id)
                        if (addToCart) cart.add(CartLine(saved))
                        if (addToCart) renderBill() else inventoryScreen()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun editPrice(index: Int) {
        val line = cart[index]
        val input = field("Selling price ₹", line.price.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        AlertDialog.Builder(this).setTitle("Edit item price").setView(input)
            .setPositiveButton("Save") { _, _ ->
                input.text.toString().toDoubleOrNull()?.takeIf { it >= 0 }?.let { line.price = it }
                renderBill()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun inventoryScreen() {
        baseScreen("Product inventory")
        button("▦ Scan barcode to add product") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestCamera.launch(Manifest.permission.CAMERA)
            else scanner.launch(IntentIntegrator.REQUEST_CODE)
        }
        button("＋ Enter barcode manually") { manualBarcodeDialog() }
        lifecycleScope.launch {
            val products = withContext(Dispatchers.IO) { dao.allProducts() }
            if (products.isEmpty()) body.addView(text("No products saved yet."))
            products.forEach { p ->
                body.addView(text("${p.name}${if (p.size.isNotBlank()) " • Size ${p.size}" else ""}\nBarcode: ${p.barcode}\nPrice: ${money(p.sellingPrice)} • Stock: ${p.stock}", 15f))
                val edit = Button(this@MainActivity).apply {
                    text = "Edit product"
                    isAllCaps = false
                    setOnClickListener { editProductDialog(p) }
                }
                body.addView(edit)
                body.addView(View(this@MainActivity).apply { setBackgroundColor(Color.LTGRAY) }, LinearLayout.LayoutParams(-1, 1))
            }
        }
        button("← Home") { showHome() }
    }

    private fun editProductDialog(p: Product) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 4, 20, 0) }
        val name = field("Product name", p.name)
        val brand = field("Brand", p.brand)
        val size = field("Size", p.size)
        val buy = field("Purchase price ₹", p.purchasePrice.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val sell = field("Selling price ₹", p.sellingPrice.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val stock = field("Stock quantity", p.stock.toString(), InputType.TYPE_CLASS_NUMBER)
        listOf(name, brand, size, buy, sell, stock).forEach { panel.addView(it) }
        AlertDialog.Builder(this).setTitle("Edit ${p.barcode}").setView(panel)
            .setPositiveButton("Save") { _, _ ->
                val updated = p.copy(name = name.text.toString().trim(), brand = brand.text.toString().trim(),
                    size = size.text.toString().trim(), purchasePrice = buy.text.toString().toDoubleOrNull() ?: 0.0,
                    sellingPrice = sell.text.toString().toDoubleOrNull() ?: p.sellingPrice,
                    stock = stock.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: p.stock)
                lifecycleScope.launch { withContext(Dispatchers.IO) { dao.saveProduct(updated) }; inventoryScreen() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun createBill() {
        val stamp = System.currentTimeMillis()
        val invoiceNo = "GF-" + SimpleDateFormat("yyMMdd-HHmmss", Locale.US).format(Date(stamp))
        lifecycleScope.launch {
            val pdf = withContext(Dispatchers.IO) { generatePdf(invoiceNo, stamp) }
            val invoice = Invoice(
                createdAt = stamp, invoiceNo = invoiceNo, customerName = customerName, customerPhone = customerPhone,
                subtotal = subtotal(), discount = discount, gstPercent = if (gstEnabled) gstPercent else 0.0,
                total = total(), paymentMode = paymentMode, pdfPath = pdf.absolutePath
            )
            withContext(Dispatchers.IO) {
                dao.saveInvoice(invoice)
                cart.forEach { dao.reduceStock(it.product.id, it.qty) }
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Bill saved")
                .setMessage("Invoice $invoiceNo\nTotal ${money(total())}\nPDF created.")
                .setPositiveButton("Share PDF") { _, _ -> sharePdf(pdf, invoiceNo) }
                .setNeutralButton("New bill") { _, _ -> newBill() }
                .setNegativeButton("Home") { _, _ -> showHome() }
                .setCancelable(false).show()
        }
    }

    private fun generatePdf(invoiceNo: String, stamp: Long): File {
        val lines = mutableListOf<String>()
        lines += SHOP_NAME
        lines += SHOP_ADDRESS
        lines += "Phone: $SHOP_PHONE"
        lines += "UPI: $SHOP_UPI"
        lines += "--------------------------------"
        lines += "Invoice: $invoiceNo"
        lines += "Date: " + SimpleDateFormat("dd-MM-yyyy HH:mm", Locale("en", "IN")).format(Date(stamp))
        if (customerName.isNotBlank()) lines += "Customer: $customerName"
        if (customerPhone.isNotBlank()) lines += "Phone: $customerPhone"
        lines += "--------------------------------"
        cart.forEach { line ->
            lines += line.product.name + (if (line.product.size.isNotBlank()) " (Size ${line.product.size})" else "")
            lines += "${line.qty} x ${money(line.price)} = ${money(line.qty * line.price)}"
        }
        lines += "--------------------------------"
        lines += "Subtotal: ${money(subtotal())}"
        lines += "Discount: ${money(discount)}"
        if (gstEnabled) lines += "GST (${gstPercent}%): ${money(taxable() * gstPercent / 100.0)}"
        lines += "TOTAL: ${money(total())}"
        lines += "Payment: $paymentMode"
        lines += "--------------------------------"
        lines += "Thank you for shopping with us!"
        lines += "Please visit again."
        val pageWidth = (80.0 / 25.4 * 72.0).toInt()
        val pageHeight = maxOf(300, (lines.size * 17 + 42))
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 8.5f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL) }
        var y = 22f
        lines.forEachIndexed { i, line ->
            if (i == 0) paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            else paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            val maxWidth = pageWidth - 18f
            val wrapped = wrapText(line, paint, maxWidth)
            wrapped.forEach { part -> canvas.drawText(part, 9f, y, paint); y += 15f }
        }
        doc.finishPage(page)
        val dir = File(cacheDir, "invoices").apply { mkdirs() }
        val file = File(dir, "$invoiceNo.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun wrapText(s: String, paint: Paint, maxWidth: Float): List<String> {
        if (paint.measureText(s) <= maxWidth) return listOf(s)
        val words = s.split(" ")
        val out = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val next = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(next) > maxWidth && current.isNotEmpty()) {
                out += current
                current = word
            } else current = next
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private fun sharePdf(file: File, invoiceNo: String) {
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Gulati Footwear bill $invoiceNo")
                putExtra(Intent.EXTRA_TEXT, "Thank you for shopping at Gulati Footwear.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share bill PDF"))
        } catch (e: Exception) {
            toast("Could not share PDF: ${e.message}")
        }
    }

    private fun historyScreen() {
        baseScreen("Bill history")
        lifecycleScope.launch {
            val invoices = withContext(Dispatchers.IO) { dao.allInvoices() }
            if (invoices.isEmpty()) body.addView(text("No bills created yet."))
            invoices.forEach { inv ->
                body.addView(text("${inv.invoiceNo}\n${SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date(inv.createdAt))}\n${inv.customerName.ifBlank { "Walk-in customer" }} • ${money(inv.total)} • ${inv.paymentMode}", 15f))
                val share = Button(this@MainActivity).apply {
                    text = "Share / open PDF"
                    isAllCaps = false
                    setOnClickListener {
                        val f = File(inv.pdfPath)
                        if (f.exists()) sharePdf(f, inv.invoiceNo) else toast("PDF file is no longer available; create a new bill.")
                    }
                }
                body.addView(share)
                body.addView(View(this@MainActivity).apply { setBackgroundColor(Color.LTGRAY) }, LinearLayout.LayoutParams(-1, 1))
            }
        }
        button("← Home") { showHome() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null && !result.contents.isNullOrBlank()) handleBarcode(result.contents)
    }
}

/**
 * Thin wrapper to expose ZXing's standard scan activity through Activity Result API.
 * Launching the intent directly avoids keeping a scanner view embedded in the bill screen.
 */
class ScanContractCompat : androidx.activity.result.contract.ActivityResultContract<Int, String?>() {
    override fun createIntent(context: android.content.Context, input: Int): Intent =
        IntentIntegrator(context as AppCompatActivity).setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES)
            .setPrompt("Scan footwear barcode").setBeepEnabled(true).setOrientationLocked(false).createScanIntent()

    override fun parseResult(resultCode: Int, intent: Intent?): String? {
        if (resultCode != android.app.Activity.RESULT_OK || intent == null) return null
        return IntentIntegrator.parseActivityResult(IntentIntegrator.REQUEST_CODE, resultCode, intent)?.contents
    }
}
