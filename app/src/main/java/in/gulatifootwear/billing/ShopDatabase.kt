package in.gulatifootwear.billing

import android.content.Context
import androidx.room.*

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

@Dao
interface ShopDao {
    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): Product?

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
}

@Database(entities = [Product::class, Invoice::class], version = 1, exportSchema = false)
abstract class ShopDatabase : RoomDatabase() {
    abstract fun dao(): ShopDao

    companion object {
        @Volatile private var INSTANCE: ShopDatabase? = null
        fun get(context: Context): ShopDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ShopDatabase::class.java,
                    "gulati_footwear.db"
                ).build().also { INSTANCE = it }
            }
    }
}
