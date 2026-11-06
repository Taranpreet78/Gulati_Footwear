package com.gulatifootwear.billing

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.max

private const val SHOP_NAME = "Gulati Footwear"
private const val SHOP_ADDRESS = "Opposite street no. 8, Kot Mangal Singh, Ludhiana"
private const val SHOP_PHONE = "7986156703"
private const val SHOP_UPI = "gulatitaranpreet78@oksbi"
private val GREEN = Color.rgb(24, 65, 51)
private val LIME = Color.rgb(198, 230, 113)
private val BG = Color.rgb(244, 247, 242)
private val INK = Color.rgb(30, 43, 36)

data class CartLine(val product: Product, var qty: Int = 1, var price: Double = product.sellingPrice)

class MainActivity : AppCompatActivity() {
    private lateinit var dao: ShopDao
    private lateinit var body: LinearLayout
    private var drawerLayout: DrawerLayout? = null
    private val cart = mutableListOf<CartLine>()
    private var gstEnabled = false
    private var gstPercent = 5.0
    private var discount = 0.0
    private var paymentMode = "Cash"
    private var customerName = ""
    private var customerPhone = ""
    private val money: (Double) -> String = { "₹" + String.format(Locale("en", "IN"), "%,.2f", it) }

    private val scanner = registerForActivityResult(ScanContractCompat()) { barcode ->
        if (!barcode.isNullOrBlank()) handleBarcode(barcode)
    }
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanner.launch(IntentIntegrator.REQUEST_CODE) else toast("Camera permission is needed to scan barcodes")
    }
    private val excelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importExcel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dao = ShopDatabase.get(this).dao()
        showHome()
    }

    private fun rounded(color: Int, radius: Float = 18f): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius; setStroke(1, Color.rgb(229, 235, 226)) }

    private fun baseScreen(title: String) {
        val drawer = DrawerLayout(this)
        drawerLayout = drawer
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 12, 12); setBackgroundColor(GREEN)
        }
        val titleView = TextView(this).apply {
            text = title; textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        val menu = TextView(this).apply {
            text = "☰"; textSize = 26f; setTextColor(LIME); gravity = Gravity.CENTER
            setPadding(14, 5, 10, 5); setOnClickListener { drawer.openDrawer(Gravity.LEFT) }
        }
        header.addView(menu)
        root.addView(header)
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 28) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        drawer.addView(root, DrawerLayout.LayoutParams(-1, -1))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(18, 24, 18, 18) }
        nav.addView(TextView(this).apply {
            text = "GULATI\nFOOTWEAR"; textSize = 23f; setTextColor(GREEN)
            typeface = Typeface.DEFAULT_BOLD; setPadding(8, 10, 8, 24)
        })
        val navItems = listOf(
            "⌂   Dashboard" to { showHome() },
            "＋   New bill" to { newBill() },
            "▦   Product inventory" to { inventoryScreen() },
            "⇧   Import Excel products" to { pickExcel() },
            "▤   Bill history" to { historyScreen() },
            "▥   Profit & loss" to { analyticsScreen("Today") },
            "＋   Add shop expense" to { addExpenseDialog() },
            "ⓘ   Shop details" to { shopDetails() }
        )
        navItems.forEach { item ->
            nav.addView(TextView(this).apply {
                text = item.first; textSize = 16f; setTextColor(INK); setPadding(12, 15, 8, 15)
                background = rounded(BG, 12f)
                setOnClickListener { drawer.closeDrawer(Gravity.LEFT); item.second() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 7 })
        }
        drawer.addView(nav, DrawerLayout.LayoutParams((resources.displayMetrics.widthPixels * .82f).toInt(), -1).apply { gravity = Gravity.LEFT })
        setContentView(drawer)
        body.alpha = 0f; body.translationY = 12f
        body.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun label(value: String, size: Float = 14f): TextView =
        TextView(this).apply { text = value; textSize = size; setTextColor(INK); setPadding(2, 7, 2, 7) }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(15, 12, 15, 12); background = rounded(Color.WHITE)
    }.also { body.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 11 }) }

    private fun button(label: String, action: () -> Unit, primary: Boolean = true) {
        val b = TextView(this).apply {
            text = label; textSize = 15f; gravity = Gravity.CENTER; setPadding(14, 14, 14, 14)
            setTextColor(if (primary) Color.WHITE else GREEN)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = rounded(if (primary) GREEN else Color.WHITE, 14f)
            isClickable = true; isFocusable = true
            setOnClickListener {
                animate().scaleX(.97f).scaleY(.97f).setDuration(75).withEndAction {
                    scaleX = 1f; scaleY = 1f; action()
                }.start()
            }
        }
        body.addView(b, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 9 })
    }

    private fun field(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(this).apply {
            this.hint = hint; setText(value); inputType = type; textSize = 16f; setSingleLine(true)
            setPadding(12, 8, 12, 8); background = rounded(Color.WHITE, 12f)
        }

    private fun statCard(title: String, value: String, subtitle: String = "") {
        val c = card()
        c.addView(label(title, 13f))
        c.addView(TextView(this).apply {
            text = value; textSize = 25f; setTextColor(GREEN); typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        if (subtitle.isNotBlank()) c.addView(label(subtitle, 12f))
    }

    private fun showHome() {
        baseScreen("Gulati Footwear")
        body.addView(label("Your shop, your sales — all in one place.", 14f))
        val today = dayRange()
        lifecycleScope.launch {
            val bills = withContext(Dispatchers.IO) { dao.invoicesBetween(today.first, today.second) }
            val sales = bills.sumOf { it.total }
            val items = withContext(Dispatchers.IO) { dao.salesItems(today.first, today.second) }
            statCard("TODAY'S BILLING", money(sales), "${bills.size} bills • ${items.sumOf { it.quantity }} pairs sold")
        }
        button("＋  Create new bill", { newBill() })
        button("▦  Product inventory", { inventoryScreen() }, false)
        button("⇧  Import products from Excel", { pickExcel() }, false)
        button("▤  Bill history", { historyScreen() }, false)
        button("▥  Profit & loss dashboard", { analyticsScreen("Today") }, false)
        button("＋  Add shop expense", { addExpenseDialog() }, false)
        val info = card()
        info.addView(label("SHOP DETAILS", 12f))
        info.addView(label("$SHOP_ADDRESS\nPhone: $SHOP_PHONE\nUPI: $SHOP_UPI", 13f))
        info.addView(label("Offline mode • Barcode scanner • 80 mm PDF receipts", 12f))
    }

    private fun shopDetails() {
        AlertDialog.Builder(this).setTitle(SHOP_NAME)
            .setMessage("$SHOP_ADDRESS\nPhone: $SHOP_PHONE\nUPI: $SHOP_UPI\n\nReceipt width: 80 mm\nGST: optional")
            .setPositiveButton("OK", null).show()
    }

    private fun newBill() {
        cart.clear(); gstEnabled = false; gstPercent = 5.0; discount = 0.0
        customerName = ""; customerPhone = ""; paymentMode = "Cash"
        renderBill()
    }

    private fun renderBill() {
        baseScreen("Create bill")
        button("▦  Scan barcode", { startScan() })
        button("＋  Enter barcode / SKU", { manualBarcodeDialog() }, false)
        body.addView(label("SEARCH PRODUCTS", 12f))
        val search = field("Search by product name, barcode, brand or size")
        body.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(results)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim().orEmpty()
                results.removeAllViews()
                if (q.length >= 1) lifecycleScope.launch {
                    val found = withContext(Dispatchers.IO) { dao.searchProducts(q) }
                    if (search.text.toString().trim() != q) return@launch
                    found.take(12).forEach { p ->
                        val item = TextView(this@MainActivity).apply {
                            text = "${p.name}  ${p.size}\n${p.barcode} • ${money(p.sellingPrice)} • Stock ${p.stock}"
                            textSize = 14f; setTextColor(INK); setPadding(12, 11, 12, 11); background = rounded(Color.WHITE, 12f)
                            setOnClickListener { addProductToCart(p); search.setText("") }
                        }
                        results.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 5 })
                    }
                    if (found.isEmpty()) results.addView(label("No matching product found. Use barcode scan to add it."))
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        val customer = card()
        customer.addView(label("CUSTOMER DETAILS", 12f))
        val nameField = field("Customer name (optional)", customerName)
        val phoneField = field("Customer phone (optional)", customerPhone, InputType.TYPE_CLASS_PHONE)
        customer.addView(nameField); customer.addView(phoneField)
        body.addView(label("BILL ITEMS", 12f))
        if (cart.isEmpty()) body.addView(label("Your cart is empty. Search or scan a product to begin."))
        cart.forEachIndexed { index, line ->
            val row = card()
            row.addView(label("${line.product.name}  ${line.product.size}", 16f))
            row.addView(label("${line.product.barcode} • ${money(line.price)} each • Cost ${money(line.product.purchasePrice)}", 12f))
            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            fun smallButton(caption: String, action: () -> Unit) = TextView(this).apply {
                text = caption; gravity = Gravity.CENTER; textSize = 17f; setTextColor(GREEN)
                setPadding(12, 8, 12, 8); background = rounded(BG, 10f); setOnClickListener { action() }
            }
            controls.addView(smallButton("−") { if (line.qty > 1) line.qty-- else cart.removeAt(index); renderBill() })
            controls.addView(TextView(this).apply { text = "  ${line.qty}  "; textSize = 16f; setTextColor(INK) })
            controls.addView(smallButton("+") { line.qty++; renderBill() })
            controls.addView(smallButton("Price") { editPrice(index) })
            controls.addView(smallButton("×") { cart.removeAt(index); renderBill() })
            row.addView(controls)
        }
        val totals = card()
        totals.addView(label("Subtotal     ${money(subtotal())}", 16f))
        val discountField = field("Discount amount ₹", if (discount > 0) discount.toString() else "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        totals.addView(discountField)
        val gstCheck = CheckBox(this).apply { text = "Apply GST"; isChecked = gstEnabled; setTextColor(INK) }
        totals.addView(gstCheck)
        val gstField = field("GST %", gstPercent.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        totals.addView(gstField)
        val payment = Spinner(this)
        payment.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Cash", "UPI", "Card", "Other"))
        payment.setSelection(listOf("Cash", "UPI", "Card", "Other").indexOf(paymentMode).coerceAtLeast(0))
        totals.addView(label("Payment method")); totals.addView(payment)
        totals.addView(TextView(this).apply {
            text = "GRAND TOTAL   ${money(total())}"; textSize = 22f; setTextColor(GREEN)
            typeface = Typeface.create("sans-serif", Typeface.BOLD); setPadding(0, 12, 0, 5)
        })
        button("Update total", {
            customerName = nameField.text.toString().trim(); customerPhone = phoneField.text.toString().trim()
            discount = discountField.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
            gstEnabled = gstCheck.isChecked; gstPercent = gstField.text.toString().toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 5.0
            paymentMode = payment.selectedItem?.toString() ?: "Cash"; renderBill()
        }, false)
        button("✓  Generate PDF & save bill", {
            customerName = nameField.text.toString().trim(); customerPhone = phoneField.text.toString().trim()
            discount = discountField.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
            gstEnabled = gstCheck.isChecked; gstPercent = gstField.text.toString().toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 5.0
            paymentMode = payment.selectedItem?.toString() ?: "Cash"
            if (cart.isEmpty()) toast("Add at least one item") else createBill()
        })
    }

    private fun addProductToCart(product: Product) {
        val existing = cart.find { it.product.id == product.id }
        if (existing != null) existing.qty++ else cart.add(CartLine(product))
        renderBill()
    }

    private fun subtotal() = cart.sumOf { it.qty * it.price }
    private fun taxable() = (subtotal() - discount).coerceAtLeast(0.0)
    private fun total() = taxable() * (1.0 + if (gstEnabled) gstPercent / 100.0 else 0.0)

    private fun startScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestCamera.launch(Manifest.permission.CAMERA)
        else scanner.launch(IntentIntegrator.REQUEST_CODE)
    }

    private fun handleBarcode(barcode: String) {
        lifecycleScope.launch {
            val product = withContext(Dispatchers.IO) { dao.findByBarcode(barcode) }
            if (product != null) addProductToCart(product) else addProductDialog(barcode, true)
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
        val name = field("Product name"); val brand = field("Brand (optional)"); val size = field("Size")
        val buy = field("Purchase price ₹", "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val sell = field("Selling price ₹", "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val stock = field("Opening stock quantity", "1", InputType.TYPE_CLASS_NUMBER)
        listOf(name, brand, size, buy, sell, stock).forEach { panel.addView(it) }
        AlertDialog.Builder(this).setTitle("New product • $barcode").setView(panel)
            .setPositiveButton("Save") { _, _ ->
                val productName = name.text.toString().trim(); val selling = sell.text.toString().toDoubleOrNull()
                if (productName.isBlank() || selling == null || selling < 0) toast("Product name and valid selling price are required")
                else lifecycleScope.launch {
                    val product = Product(barcode = barcode, name = productName, brand = brand.text.toString().trim(),
                        size = size.text.toString().trim(), purchasePrice = buy.text.toString().toDoubleOrNull() ?: 0.0,
                        sellingPrice = selling, stock = stock.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0)
                    val id = withContext(Dispatchers.IO) { dao.saveProduct(product) }
                    if (addToCart) { cart.add(CartLine(product.copy(id = id))); renderBill() } else inventoryScreen()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun editPrice(index: Int) {
        val line = cart[index]; val input = field("Selling price ₹", line.price.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        AlertDialog.Builder(this).setTitle("Edit item price").setView(input)
            .setPositiveButton("Save") { _, _ -> input.text.toString().toDoubleOrNull()?.takeIf { it >= 0 }?.let { line.price = it }; renderBill() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun inventoryScreen() {
        baseScreen("Product inventory")
        button("▦  Scan barcode to add product", { startScan() })
        button("⇧  Import Excel (.xlsx)", { pickExcel() }, false)
        val search = field("Search inventory")
        body.addView(search)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        fun load(q: String = "") {
            lifecycleScope.launch {
                val products = withContext(Dispatchers.IO) { if (q.isBlank()) dao.allProducts() else dao.searchProducts(q) }
                list.removeAllViews()
                products.forEach { p ->
                    val c = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(13, 11, 13, 11); background = rounded(Color.WHITE) }
                    c.addView(label("${p.name} ${p.size}", 16f))
                    c.addView(label("${p.barcode} • ${money(p.sellingPrice)} • Cost ${money(p.purchasePrice)} • Stock ${p.stock}", 12f))
                    c.addView(TextView(this@MainActivity).apply { text = "Edit product"; setTextColor(GREEN); setPadding(0, 8, 0, 4); setOnClickListener { editProductDialog(p) } })
                    list.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
                }
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { load(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        load()
    }

    private fun editProductDialog(p: Product) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 4, 20, 0) }
        val name = field("Product name", p.name); val brand = field("Brand", p.brand); val size = field("Size", p.size)
        val buy = field("Purchase price ₹", p.purchasePrice.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val sell = field("Selling price ₹", p.sellingPrice.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val stock = field("Stock quantity", p.stock.toString(), InputType.TYPE_CLASS_NUMBER)
        listOf(name, brand, size, buy, sell, stock).forEach { panel.addView(it) }
        AlertDialog.Builder(this).setTitle("Edit ${p.barcode}").setView(panel)
            .setPositiveButton("Save") { _, _ ->
                val updated = p.copy(name = name.text.toString().trim(), brand = brand.text.toString().trim(), size = size.text.toString().trim(),
                    purchasePrice = buy.text.toString().toDoubleOrNull() ?: 0.0, sellingPrice = sell.text.toString().toDoubleOrNull() ?: p.sellingPrice,
                    stock = stock.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: p.stock)
                lifecycleScope.launch { withContext(Dispatchers.IO) { dao.saveProduct(updated) }; inventoryScreen() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun pickExcel() { excelPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) }

    private fun importExcel(uri: Uri) {
        lifecycleScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) { readXlsx(uri) }
                if (rows.size < 2) { toast("Excel file has no product rows"); return@launch }
                val headers = rows.first().map { it.trim().lowercase(Locale.ROOT) }
                fun col(vararg names: String) = headers.indexOfFirst { h -> names.any { h == it || h.contains(it) } }
                val barcode = col("barcode", "sku", "item code")
                val name = col("product name", "name", "item")
                val brand = col("brand", "company")
                val size = col("size")
                val cost = col("purchase price", "cost price", "buy price", "cost")
                val sell = col("selling price", "sale price", "price", "mrp")
                val stock = col("stock", "quantity", "qty")
                if (barcode < 0 || name < 0 || sell < 0) {
                    toast("Required Excel columns: Barcode, Product Name, Selling Price"); return@launch
                }
                var added = 0; var updated = 0; var skipped = 0
                rows.drop(1).forEach { r ->
                    fun value(i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""
                    val code = value(barcode); val title = value(name); val price = value(sell).replace(",", "").toDoubleOrNull()
                    if (code.isBlank() || title.isBlank() || price == null) { skipped++; return@forEach }
                    val existing = withContext(Dispatchers.IO) { dao.findByBarcode(code) }
                    val p = Product(id = existing?.id ?: 0, barcode = code, name = title,
                        brand = value(brand), size = value(size), purchasePrice = value(cost).replace(",", "").toDoubleOrNull() ?: existing?.purchasePrice ?: 0.0,
                        sellingPrice = price, stock = value(stock).toIntOrNull() ?: existing?.stock ?: 0)
                    withContext(Dispatchers.IO) { dao.saveProduct(p) }
                    if (existing == null) added++ else updated++
                }
                AlertDialog.Builder(this@MainActivity).setTitle("Excel import completed")
                    .setMessage("New products: $added\nUpdated products: $updated\nSkipped rows: $skipped")
                    .setPositiveButton("View inventory") { _, _ -> inventoryScreen() }.setNegativeButton("Home") { _, _ -> showHome() }.show()
            } catch (e: Exception) { toast("Excel import failed: ${e.message ?: "Invalid .xlsx file"}") }
        }
    }

    private fun readXlsx(uri: Uri): List<List<String>> {
        val input = contentResolver.openInputStream(uri) ?: error("Cannot open selected file")
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && (entry.name == "xl/worksheets/sheet1.xml" || entry.name == "xl/sharedStrings.xml")) {
                    entries[entry.name] = zip.readBytes()
                }
            }
        }
        val shared = mutableListOf<String>()
        fun parse(xml: ByteArray): List<List<String>> {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(xml.inputStream(), "UTF-8")
            val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); var cellValue = ""
            var inValue = false; var cellType = ""; var cellRef = ""
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "row" -> row = mutableListOf()
                        "c" -> { cellType = parser.getAttributeValue(null, "t") ?: ""; cellRef = parser.getAttributeValue(null, "r") ?: ""; cellValue = "" }
                        "v", "t" -> inValue = true
                    }
                    XmlPullParser.TEXT -> if (inValue) cellValue += parser.text
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "v", "t" -> inValue = false
                        "c" -> {
                            val letters = cellRef.takeWhile { it.isLetter() }
                            var col = 0
                            letters.forEach { col = col * 26 + (it.uppercaseChar() - 'A' + 1) }
                            while (row.size < col) row.add("")
                            val value = if (cellType == "s") cellValue.toIntOrNull()?.let { shared.getOrNull(it) } ?: "" else cellValue
                            if (col > 0) row[col - 1] = value
                        }
                        "row" -> if (row.isNotEmpty()) rows.add(row.toList())
                    }
                }
                parser.next()
            }
            return rows
        }
        entries["xl/sharedStrings.xml"]?.let { xml ->
            val parser = XmlPullParserFactory.newInstance().newPullParser(); parser.setInput(xml.inputStream(), "UTF-8")
            var inside = false; var value = ""
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> if (parser.name == "si") { inside = true; value = "" }
                    XmlPullParser.TEXT -> if (inside) value += parser.text
                    XmlPullParser.END_TAG -> if (parser.name == "si") { shared.add(value); inside = false }
                }; parser.next()
            }
        }
        val sheet = entries["xl/worksheets/sheet1.xml"] ?: error("Cannot find first worksheet in Excel file")
        return parse(sheet)
    }

    private fun dayRange(): Pair<Long, Long> {
        val c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val from = c.timeInMillis; c.add(Calendar.DAY_OF_MONTH, 1); return from to (c.timeInMillis - 1)
    }
    private fun monthRange(): Pair<Long, Long> {
        val c = Calendar.getInstance(); c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val from = c.timeInMillis; c.add(Calendar.MONTH, 1); return from to (c.timeInMillis - 1)
    }
    private fun yearRange(): Pair<Long, Long> {
        val c = Calendar.getInstance(); c.set(Calendar.MONTH, Calendar.JANUARY); c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val from = c.timeInMillis; c.add(Calendar.YEAR, 1); return from to (c.timeInMillis - 1)
    }

    private fun analyticsScreen(period: String) {
        baseScreen("Profit & loss")
        val selector = Spinner(this)
        val periods = listOf("Today", "This month", "This year")
        selector.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, periods)
        selector.setSelection(periods.indexOf(period).coerceAtLeast(0))
        body.addView(label("REPORT PERIOD")); body.addView(selector)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(panel)
        fun load(selected: String) {
            lifecycleScope.launch {
                val range = when (selected) { "This month" -> monthRange(); "This year" -> yearRange(); else -> dayRange() }
                val invoices = withContext(Dispatchers.IO) { dao.invoicesBetween(range.first, range.second) }
                val items = withContext(Dispatchers.IO) { dao.salesItems(range.first, range.second) }
                val expenses = withContext(Dispatchers.IO) { dao.expenses(range.first, range.second) }
                val sales = invoices.sumOf { it.total }
                val cost = items.sumOf { it.quantity * it.purchasePrice }
                val gross = items.sumOf { it.quantity * (it.sellingPrice - it.purchasePrice) } - invoices.sumOf { it.discount }
                val expenseTotal = expenses.sumOf { it.amount }
                panel.removeAllViews()
                fun metric(title: String, value: String, hint: String = "") {
                    val c = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(15, 12, 15, 12); background = rounded(Color.WHITE) }
                    c.addView(label(title, 12f)); c.addView(TextView(this@MainActivity).apply { text = value; textSize = 23f; setTextColor(GREEN); typeface = Typeface.DEFAULT_BOLD })
                    if (hint.isNotBlank()) c.addView(label(hint, 12f))
                    panel.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 9 })
                }
                metric("TOTAL BILLING / SALES", money(sales), "${invoices.size} bills")
                metric("COST OF GOODS SOLD", money(cost), "Based on saved purchase price at sale")
                metric("GROSS PROFIT", money(gross))
                metric("SHOP EXPENSES", money(expenseTotal), "${expenses.size} recorded expenses")
                metric("ESTIMATED NET PROFIT", money(gross - expenseTotal), "Gross margin less bill discounts and recorded expenses")
                val note = TextView(this@MainActivity).apply { text = "Note: Profit reporting starts with bills created in Version 2.0. Older bills do not contain saved purchase-cost details. GST is not treated as profit."; textSize = 12f; setTextColor(Color.DKGRAY); setPadding(4, 8, 4, 12) }
                panel.addView(note)
                metric("ITEMS SOLD", items.sumOf { it.quantity }.toString())
                if (expenses.isNotEmpty()) {
                    panel.addView(label("EXPENSE DETAILS", 13f))
                    expenses.forEach { panel.addView(label("${it.title} • ${money(it.amount)} • ${SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(it.createdAt))}", 13f)) }
                }
            }
        }
        selector.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { load(periods[position]) }
        }
        load(period)
        button("＋  Record shop expense", { addExpenseDialog() }, false)
    }

    private fun addExpenseDialog() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 5, 18, 0) }
        val title = field("Expense name (rent, electricity, transport...)")
        val amount = field("Amount ₹", "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val note = field("Note (optional)")
        panel.addView(title); panel.addView(amount); panel.addView(note)
        AlertDialog.Builder(this).setTitle("Add shop expense").setView(panel)
            .setPositiveButton("Save") { _, _ ->
                val value = amount.text.toString().toDoubleOrNull()
                if (title.text.isNullOrBlank() || value == null || value <= 0) toast("Enter expense name and valid amount")
                else lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.addExpense(ShopExpense(createdAt = System.currentTimeMillis(), title = title.text.toString().trim(), amount = value, note = note.text.toString())) }
                    toast("Expense saved"); analyticsScreen("Today")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun createBill() {
        val stamp = System.currentTimeMillis()
        val invoiceNo = "GF-" + SimpleDateFormat("yyMMdd-HHmmss", Locale.US).format(Date(stamp))
        lifecycleScope.launch {
            val pdf = withContext(Dispatchers.IO) { generatePdf(invoiceNo, stamp) }
            val invoice = Invoice(createdAt = stamp, invoiceNo = invoiceNo, customerName = customerName, customerPhone = customerPhone,
                subtotal = subtotal(), discount = discount, gstPercent = if (gstEnabled) gstPercent else 0.0,
                total = total(), paymentMode = paymentMode, pdfPath = pdf.absolutePath)
            withContext(Dispatchers.IO) {
                val id = dao.saveInvoice(invoice)
                dao.saveInvoiceItems(cart.map { line ->
                    InvoiceItem(invoiceId = id, createdAt = stamp, productId = line.product.id, barcode = line.product.barcode,
                        productName = line.product.name, size = line.product.size, quantity = line.qty,
                        sellingPrice = line.price, purchasePrice = line.product.purchasePrice)
                })
                cart.forEach { dao.reduceStock(it.product.id, it.qty) }
            }
            AlertDialog.Builder(this@MainActivity).setTitle("Bill saved")
                .setMessage("Invoice $invoiceNo\nTotal ${money(total())}\nPDF created.")
                .setPositiveButton("Share PDF") { _, _ -> sharePdf(pdf, invoiceNo) }
                .setNeutralButton("New bill") { _, _ -> newBill() }.setNegativeButton("Home") { _, _ -> showHome() }
                .setCancelable(false).show()
        }
    }

    private fun generatePdf(invoiceNo: String, stamp: Long): File {
        val lines = mutableListOf(SHOP_NAME, SHOP_ADDRESS, "Phone: $SHOP_PHONE", "UPI: $SHOP_UPI", "--------------------------------",
            "Invoice: $invoiceNo", "Date: " + SimpleDateFormat("dd-MM-yyyy HH:mm", Locale("en", "IN")).format(Date(stamp)))
        if (customerName.isNotBlank()) lines += "Customer: $customerName"
        if (customerPhone.isNotBlank()) lines += "Phone: $customerPhone"
        lines += "--------------------------------"
        cart.forEach { line ->
            lines += line.product.name + if (line.product.size.isNotBlank()) " (Size ${line.product.size})" else ""
            lines += "${line.qty} x ${money(line.price)} = ${money(line.qty * line.price)}"
        }
        lines += listOf("--------------------------------", "Subtotal: ${money(subtotal())}", "Discount: ${money(discount)}")
        if (gstEnabled) lines += "GST (${gstPercent}%): ${money(taxable() * gstPercent / 100.0)}"
        lines += listOf("TOTAL: ${money(total())}", "Payment: $paymentMode", "--------------------------------", "Thank you for shopping!", "Please visit again.")
        val width = (80.0 / 25.4 * 72.0).toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 8.5f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL) }
        val wrapped = lines.flatMap { wrapText(it, paint, width - 18f) }
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(width, max(300, wrapped.size * 15 + 42), 1).create())
        var y = 22f
        wrapped.forEachIndexed { i, line ->
            paint.typeface = Typeface.create(Typeface.MONOSPACE, if (i == 0) Typeface.BOLD else Typeface.NORMAL)
            page.canvas.drawText(line, 9f, y, paint); y += 15f
        }
        doc.finishPage(page)
        val dir = File(cacheDir, "invoices").apply { mkdirs() }
        val file = File(dir, "$invoiceNo.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }; doc.close()
        return file
    }

    private fun wrapText(s: String, paint: Paint, maxWidth: Float): List<String> {
        if (paint.measureText(s) <= maxWidth) return listOf(s)
        val out = mutableListOf<String>(); var current = ""
        s.split(" ").forEach { word ->
            val next = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(next) > maxWidth && current.isNotEmpty()) { out += current; current = word } else current = next
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private fun sharePdf(file: File, invoiceNo: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Gulati Footwear bill $invoiceNo")
                putExtra(Intent.EXTRA_TEXT, "Thank you for shopping at Gulati Footwear.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share bill PDF"))
        } catch (e: Exception) { toast("Could not share PDF: ${e.message}") }
    }

    private fun historyScreen() {
        baseScreen("Bill history")
        lifecycleScope.launch {
            val invoices = withContext(Dispatchers.IO) { dao.allInvoices() }
            if (invoices.isEmpty()) body.addView(label("No bills created yet."))
            invoices.forEach { inv ->
                val c = card()
                c.addView(label(inv.invoiceNo, 16f))
                c.addView(label("${SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date(inv.createdAt))}\n${inv.customerName.ifBlank { "Walk-in customer" }} • ${money(inv.total)} • ${inv.paymentMode}", 13f))
                val share = TextView(this@MainActivity).apply {
                    text = "Share / open PDF"; setTextColor(GREEN); setPadding(0, 8, 0, 5)
                    setOnClickListener { val f = File(inv.pdfPath); if (f.exists()) sharePdf(f, inv.invoiceNo) else toast("PDF file is unavailable") }
                }
                c.addView(share)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null && !result.contents.isNullOrBlank()) handleBarcode(result.contents)
    }
}

class ScanContractCompat : androidx.activity.result.contract.ActivityResultContract<Int, String?>() {
    override fun createIntent(context: android.content.Context, input: Int): Intent =
        IntentIntegrator(context as AppCompatActivity).setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES)
            .setPrompt("Scan footwear barcode").setBeepEnabled(true).setOrientationLocked(false).createScanIntent()
    override fun parseResult(resultCode: Int, intent: Intent?): String? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        return IntentIntegrator.parseActivityResult(IntentIntegrator.REQUEST_CODE, resultCode, intent)?.contents
    }
}
