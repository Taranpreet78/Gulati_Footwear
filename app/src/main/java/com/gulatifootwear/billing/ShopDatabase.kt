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
    val stock: Int = 0
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

@Dao
interface ShopDao {
    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): Product?

    @Query("SELECT * FROM products WHERE name LIKE '%' || :query || '%' OR barcode LIKE '%' || :query || '%' OR brand LIKE '%' || :query || '%' OR size LIKE '%' || :query || '%' ORDER BY name COLLATE NOCASE LIMIT 60")
    suspend fun searchProducts(query: String): List<Product>

    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE")
    suspend fun allProducts(): List<Product>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProduct(product: Product): Long

    @Query("UPDATE products SET stock = stock - :qty WHERE id = :productId AND stock >= :qty")
    suspend fun reduceStock(productId: Long, qty: Int): Int

    @Query("SELECT * FROM invoices ORDER BY createdAt DESC")
    suspend fun allInvoices(): List<Invoice>

    @Insert
    suspend fun saveInvoice(invoice: Invoice): Long

    @Insert
    suspend fun saveInvoiceItems(items: List<InvoiceItem>)

    @Query("SELECT * FROM invoice_items WHERE createdAt BETWEEN :from AND :to")
    suspend fun salesItems(from: Long, to: Long): List<InvoiceItem>

    @Query("SELECT * FROM expenses WHERE createdAt BETWEEN :from AND :to ORDER BY createdAt DESC")
    suspend fun expenses(from: Long, to: Long): List<ShopExpense>

    @Insert
    suspend fun addExpense(expense: ShopExpense): Long

    @Query("SELECT * FROM invoices WHERE createdAt BETWEEN :from AND :to ORDER BY createdAt DESC")
    suspend fun invoicesBetween(from: Long, to: Long): List<Invoice>
}

@Database(entities = [Product::class, Invoice::class, InvoiceItem::class, ShopExpense::class], version = 2, exportSchema = false)
abstract class ShopDatabase : RoomDatabase() {
    abstract fun dao(): ShopDao

    companion object {
        @Volatile private var INSTANCE: ShopDatabase? = null
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
        fun get(context: Context): ShopDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, ShopDatabase::class.java, "gulati_footwear.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}
