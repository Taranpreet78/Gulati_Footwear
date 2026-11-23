package com.gulatifootwear.billing

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "products", indices = [Index(value = ["barcode"], unique = true)])
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val barcode: String,
    val name: String,
    val size: String = "",
    val brand: String = "",
    val purchasePrice: Double = 0.0,
    val sellingPrice: Double,
    val stock: Int = 0,
    val imagePath: String? = null
)

@Entity(tableName = "invoices")
data class Invoice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val invoiceNo: String,
    val customerName: String,
    val customerPhone: String,
    val subtotal: Double,
    val discount: Double,
    val gstPercent: Double,
    val total: Double,
    val paymentMode: String,
    val pdfPath: String
)

@Entity(tableName = "invoice_items", indices = [Index("invoiceId"), Index("createdAt")])
data class InvoiceItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceId: Long,
    val createdAt: Long,
    val productId: Long,
    val barcode: String,
    val productName: String,
    val size: String,
    val quantity: Int,
    val sellingPrice: Double,
    val purchasePrice: Double
)

@Entity(tableName = "expenses", indices = [Index("createdAt")])
data class ShopExpense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val title: String,
    val amount: Double,
    val note: String = ""
)

@Entity(tableName = "wholesalers")
data class Wholesaler(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val phone: String = "", val note: String = "")

@Entity(tableName = "wholesaler_purchases", indices = [Index("wholesalerId"), Index("purchaseDate")])
data class WholesalerPurchase(@PrimaryKey(autoGenerate = true) val id: Long = 0, val wholesalerId: Long, val purchaseDate: Long, val billAmount: Double, val billNumber: String = "", val note: String = "")

@Entity(tableName = "wholesaler_payments", indices = [Index("wholesalerId"), Index("paymentDate")])
data class WholesalerPayment(@PrimaryKey(autoGenerate = true) val id: Long = 0, val wholesalerId: Long, val paymentDate: Long, val amount: Double, val note: String = "")

@Dao
interface ShopDao {
    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): Product?

    @Query("SELECT * FROM products WHERE name LIKE '%' || :query || '%' OR barcode LIKE '%' || :query || '%' OR brand LIKE '%' || :query || '%' OR size LIKE '%' || :query || '%' ORDER BY name COLLATE NOCASE LIMIT 60")
    suspend fun searchProducts(query: String): List<Product>

    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE")
    suspend fun allProducts(): List<Product>

    @Query("SELECT barcode FROM products")
    suspend fun allBarcodes(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProduct(product: Product): Long

    @Query("UPDATE products SET stock = stock - :qty WHERE id = :productId AND stock >= :qty")
    suspend fun reduceStock(productId: Long, qty: Int): Int

    @Query("UPDATE products SET stock = stock + :qty WHERE id = :productId")
    suspend fun increaseStock(productId: Long, qty: Int): Int
    @Query("SELECT COUNT(*) FROM products WHERE id = :productId")
    suspend fun productExists(productId: Long): Int
    @Query("DELETE FROM products WHERE id = :productId")
    suspend fun deleteProduct(productId: Long): Int

    @Query("SELECT * FROM invoices ORDER BY createdAt DESC")
    suspend fun allInvoices(): List<Invoice>

    @Insert
    suspend fun saveInvoice(invoice: Invoice): Long

    @Update
    suspend fun updateInvoice(invoice: Invoice)
    @Query("SELECT * FROM invoice_items WHERE invoiceId = :invoiceId ORDER BY id")
    suspend fun invoiceItems(invoiceId: Long): List<InvoiceItem>
    @Query("DELETE FROM invoice_items WHERE invoiceId = :invoiceId")
    suspend fun deleteInvoiceItems(invoiceId: Long)
    @Query("DELETE FROM invoices WHERE id = :invoiceId")
    suspend fun deleteInvoice(invoiceId: Long): Int

    @Transaction
    suspend fun removeInvoiceAndRestoreStock(invoiceId: Long) {
        invoiceItems(invoiceId).forEach { increaseStock(it.productId, it.quantity) }
        deleteInvoiceItems(invoiceId); deleteInvoice(invoiceId)
    }
    @Transaction
    suspend fun replaceInvoiceAndAdjustStock(invoice: Invoice, newItems: List<InvoiceItem>) {
        invoiceItems(invoice.id).forEach { increaseStock(it.productId, it.quantity) }
        newItems.forEach { item ->
            if (item.productId > 0 && productExists(item.productId) > 0 && reduceStock(item.productId, item.quantity) == 0)
                throw IllegalStateException("Not enough stock for " + item.productName)
        }
        updateInvoice(invoice); deleteInvoiceItems(invoice.id); saveInvoiceItems(newItems)
    }

    @Insert
    suspend fun saveInvoiceItems(items: List<InvoiceItem>)

    @Query("SELECT * FROM invoice_items WHERE createdAt BETWEEN :from AND :to")
    suspend fun salesItems(from: Long, to: Long): List<InvoiceItem>

    @Query("SELECT * FROM expenses WHERE createdAt BETWEEN :from AND :to ORDER BY createdAt DESC")
    suspend fun expenses(from: Long, to: Long): List<ShopExpense>

    @Insert
    suspend fun addExpense(expense: ShopExpense): Long

    @Update
    suspend fun updateExpense(expense: ShopExpense)
    @Query("DELETE FROM expenses WHERE id = :expenseId")
    suspend fun deleteExpense(expenseId: Long): Int

    @Query("SELECT * FROM invoices WHERE createdAt BETWEEN :from AND :to ORDER BY createdAt DESC")
    suspend fun invoicesBetween(from: Long, to: Long): List<Invoice>

    @Query("SELECT * FROM wholesalers ORDER BY name COLLATE NOCASE")
    suspend fun allWholesalers(): List<Wholesaler>
    @Insert suspend fun addWholesaler(item: Wholesaler): Long
    @Update suspend fun updateWholesaler(item: Wholesaler)
    @Query("DELETE FROM wholesalers WHERE id = :id") suspend fun deleteWholesaler(id: Long): Int
    @Insert suspend fun addWholesalerPurchase(item: WholesalerPurchase): Long
    @Insert suspend fun addWholesalerPayment(item: WholesalerPayment): Long
    @Query("SELECT * FROM wholesaler_purchases WHERE wholesalerId = :supplierId ORDER BY purchaseDate DESC, id DESC") suspend fun wholesalerPurchases(supplierId: Long): List<WholesalerPurchase>
    @Query("SELECT * FROM wholesaler_payments WHERE wholesalerId = :supplierId ORDER BY paymentDate DESC, id DESC") suspend fun wholesalerPayments(supplierId: Long): List<WholesalerPayment>
    @Query("SELECT COALESCE(SUM(billAmount), 0) FROM wholesaler_purchases WHERE wholesalerId = :supplierId") suspend fun wholesalerPurchased(supplierId: Long): Double
    @Query("SELECT COALESCE(SUM(amount), 0) FROM wholesaler_payments WHERE wholesalerId = :supplierId") suspend fun wholesalerPaid(supplierId: Long): Double
    @Query("SELECT COALESCE(SUM(billAmount), 0) FROM wholesaler_purchases") suspend fun totalWholesalerPurchases(): Double
    @Query("SELECT COALESCE(SUM(amount), 0) FROM wholesaler_payments") suspend fun totalWholesalerPayments(): Double
    @Query("DELETE FROM wholesaler_purchases") suspend fun deleteAllWholesalerPurchases()
    @Query("DELETE FROM wholesaler_payments") suspend fun deleteAllWholesalerPayments()
    @Query("DELETE FROM wholesalers") suspend fun deleteAllWholesalers()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreWholesalers(items: List<Wholesaler>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreWholesalerPurchases(items: List<WholesalerPurchase>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreWholesalerPayments(items: List<WholesalerPayment>)

    @Query("DELETE FROM invoice_items")
    suspend fun deleteAllInvoiceItems()

    @Query("DELETE FROM invoices")
    suspend fun deleteAllInvoices()

    @Query("DELETE FROM expenses")
    suspend fun deleteAllExpenses()

    @Query("DELETE FROM products")
    suspend fun deleteAllProducts()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreProducts(items: List<Product>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreInvoices(items: List<Invoice>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreInvoiceItems(items: List<InvoiceItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreExpenses(items: List<ShopExpense>)

    @Transaction
    suspend fun replaceAllData(products: List<Product>, invoices: List<Invoice>, items: List<InvoiceItem>, expenses: List<ShopExpense>, wholesalers: List<Wholesaler> = emptyList(), purchases: List<WholesalerPurchase> = emptyList(), payments: List<WholesalerPayment> = emptyList()) {
        deleteAllInvoiceItems()
        deleteAllInvoices()
        deleteAllExpenses()
        deleteAllWholesalerPurchases(); deleteAllWholesalerPayments(); deleteAllWholesalers()
        deleteAllProducts()
        if (products.isNotEmpty()) restoreProducts(products)
        if (invoices.isNotEmpty()) restoreInvoices(invoices)
        if (items.isNotEmpty()) restoreInvoiceItems(items)
        if (expenses.isNotEmpty()) restoreExpenses(expenses)
        if (wholesalers.isNotEmpty()) restoreWholesalers(wholesalers)
        if (purchases.isNotEmpty()) restoreWholesalerPurchases(purchases)
        if (payments.isNotEmpty()) restoreWholesalerPayments(payments)
    }
}

@Database(entities = [Product::class, Invoice::class, InvoiceItem::class, ShopExpense::class, Wholesaler::class, WholesalerPurchase::class, WholesalerPayment::class], version = 4, exportSchema = false)
abstract class ShopDatabase : RoomDatabase() {
    abstract fun dao(): ShopDao

    companion object {
        @Volatile private var INSTANCE: ShopDatabase? = null
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN imagePath TEXT")
            }
        }
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS invoice_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    invoiceId INTEGER NOT NULL, createdAt INTEGER NOT NULL,
                    productId INTEGER NOT NULL, barcode TEXT NOT NULL,
                    productName TEXT NOT NULL, size TEXT NOT NULL,
                    quantity INTEGER NOT NULL, sellingPrice REAL NOT NULL,
                    purchasePrice REAL NOT NULL)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_invoice_items_invoiceId ON invoice_items(invoiceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_invoice_items_createdAt ON invoice_items(createdAt)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS expenses (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    createdAt INTEGER NOT NULL, title TEXT NOT NULL,
                    amount REAL NOT NULL, note TEXT NOT NULL)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_createdAt ON expenses(createdAt)")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS wholesalers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL, note TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS wholesaler_purchases (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, wholesalerId INTEGER NOT NULL, purchaseDate INTEGER NOT NULL, billAmount REAL NOT NULL, billNumber TEXT NOT NULL, note TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wholesaler_purchases_wholesalerId ON wholesaler_purchases(wholesalerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wholesaler_purchases_purchaseDate ON wholesaler_purchases(purchaseDate)")
                db.execSQL("CREATE TABLE IF NOT EXISTS wholesaler_payments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, wholesalerId INTEGER NOT NULL, paymentDate INTEGER NOT NULL, amount REAL NOT NULL, note TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wholesaler_payments_wholesalerId ON wholesaler_payments(wholesalerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wholesaler_payments_paymentDate ON wholesaler_payments(paymentDate)")
            }
        }
        fun get(context: Context): ShopDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, ShopDatabase::class.java, "gulati_footwear.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
            }
    }
}
