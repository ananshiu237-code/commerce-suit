package com.example.posmobile

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val client = OkHttpClient()
    private val apiBase = "http://192.168.0.227/api"

    private lateinit var statusText: TextView
    private lateinit var cartText: TextView
    private lateinit var modeButton: Button
    private lateinit var checkoutButton: Button
    private lateinit var inventoryButton: Button
    private lateinit var inventoryQtyInput: EditText
    private lateinit var inventoryControls: LinearLayout
    private lateinit var rangeButton: Button

    enum class Mode { SALES, INVENTORY }
    enum class ReportRange(val label: String, val days: Long) {
        TODAY("今日", 0),
        YESTERDAY("昨天", 1),
        LAST7("近7天", 6),
        LAST15("近15天", 14),
        LAST30("近30天", 29)
    }
    private var mode: Mode = Mode.SALES
    private var reportRange: ReportRange = ReportRange.TODAY

    data class CartItem(
        val productId: Int,
        val name: String,
        val price: Double,
        var qty: Int
    )

    private val cart = linkedMapOf<Int, CartItem>()
    private val inventoryDraft = linkedMapOf<Int, CartItem>()
    private var lastInventoryProductId: Int? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val qr = result.contents
        if (qr.isNullOrBlank()) {
            setStatus("狀態：取消掃碼")
            return@registerForActivityResult
        }
        setStatus("狀態：掃碼成功 $qr，查詢商品中...")
        fetchProductByQr(qr)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        cartText = findViewById(R.id.cartText)
        modeButton = findViewById(R.id.modeButton)
        val scanButton = findViewById<Button>(R.id.scanButton)
        checkoutButton = findViewById(R.id.checkoutButton)
        inventoryButton = findViewById(R.id.inventoryButton)
        inventoryQtyInput = findViewById(R.id.inventoryQtyInput)
        inventoryControls = findViewById(R.id.inventoryControls)
        rangeButton = findViewById(R.id.rangeButton)
        val removeLastButton = findViewById<Button>(R.id.removeLastButton)
        val reportButton = findViewById<Button>(R.id.reportButton)

        modeButton.setOnClickListener { toggleMode() }
        scanButton.setOnClickListener { startScan() }
        checkoutButton.setOnClickListener { checkout() }
        inventoryButton.setOnClickListener { confirmAndSubmitInventoryCheck() }
        removeLastButton.setOnClickListener { removeLastInventoryItem() }
        reportButton.setOnClickListener { fetchTodayReports() }
        rangeButton.setOnClickListener { toggleRange() }

        renderCurrent()
        updateModeUI()
        rangeButton.text = "報表區間：${reportRange.label}"
    }

    private fun toggleMode() {
        mode = if (mode == Mode.SALES) Mode.INVENTORY else Mode.SALES
        setStatus("狀態：已切換為${if (mode == Mode.SALES) "銷售模式" else "盤點模式"}")
        updateModeUI()
        renderCurrent()
    }

    private fun updateModeUI() {
        val isSales = mode == Mode.SALES
        modeButton.text = "切換模式：目前 ${if (isSales) "銷售" else "盤點"}"
        checkoutButton.visibility = if (isSales) View.VISIBLE else View.GONE
        inventoryButton.visibility = if (isSales) View.GONE else View.VISIBLE
        inventoryControls.visibility = if (isSales) View.GONE else View.VISIBLE
    }

    private fun toggleRange() {
        reportRange = when (reportRange) {
            ReportRange.TODAY -> ReportRange.YESTERDAY
            ReportRange.YESTERDAY -> ReportRange.LAST7
            ReportRange.LAST7 -> ReportRange.LAST15
            ReportRange.LAST15 -> ReportRange.LAST30
            ReportRange.LAST30 -> ReportRange.TODAY
        }
        rangeButton.text = "報表區間：${reportRange.label}"
        setStatus("狀態：報表區間切換為 ${reportRange.label}")
    }

    private fun startScan() {
        val options = ScanOptions().apply {
            setPrompt("掃描商品 QR/條碼")
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        scanLauncher.launch(options)
    }

    private fun fetchProductByQr(code: String) {
        thread {
            try {
                val url = "$apiBase/products/by-qr/${code}?company_id=1&store_id=1"
                val req = Request.Builder().url(url).build()
                val res = client.newCall(req).execute()
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    runOnUiThread { setStatus("狀態：查無商品 ($code)") }
                    return@thread
                }

                val data = JSONObject(body).getJSONObject("data")
                val id = data.getInt("id")
                val name = data.getString("name")
                val price = data.getDouble("price")

                if (mode == Mode.SALES) {
                    val existing = cart[id]
                    if (existing == null) cart[id] = CartItem(id, name, price, 1) else existing.qty += 1
                } else {
                    val qtyInput = inventoryQtyInput.text.toString().trim().toIntOrNull() ?: 1
                    val countedQty = if (qtyInput < 0) 0 else qtyInput
                    inventoryDraft[id] = CartItem(id, name, price, countedQty)
                    lastInventoryProductId = id
                }

                runOnUiThread {
                    setStatus("狀態：${if (mode == Mode.SALES) "加入銷售" else "加入盤點"} $name")
                    renderCurrent()
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("狀態：錯誤 ${e.message}") }
            }
        }
    }

    private fun checkout() {
        if (cart.isEmpty()) {
            setStatus("狀態：購物車是空的")
            return
        }
        setStatus("狀態：送單中...")

        thread {
            try {
                val items = JSONArray()
                var total = 0.0
                cart.values.forEach {
                    items.put(JSONObject().put("product_id", it.productId).put("qty", it.qty))
                    total += it.price * it.qty
                }

                val payload = JSONObject()
                    .put("company_id", 1)
                    .put("store_id", 1)
                    .put("cashier_user_id", 1)
                    .put("items", items)
                    .put("payment", JSONObject().put("method_code", "CASH").put("amount", total))

                val req = Request.Builder()
                    .url("$apiBase/orders")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val res = client.newCall(req).execute()
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    runOnUiThread { setStatus("狀態：送單失敗 $body") }
                    return@thread
                }

                val data = JSONObject(body).getJSONObject("data")
                val orderNo = data.getString("order_no")
                cart.clear()
                runOnUiThread {
                    renderCurrent()
                    setStatus("狀態：送單成功，訂單 $orderNo")
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("狀態：送單錯誤 ${e.message}") }
            }
        }
    }

    private fun confirmAndSubmitInventoryCheck() {
        if (inventoryDraft.isEmpty()) {
            setStatus("狀態：盤點清單是空的")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("確認送出盤點")
            .setMessage("確定要送出盤點？送出後會直接覆蓋庫存數量。")
            .setPositiveButton("確定") { _, _ -> submitInventoryCheck() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun submitInventoryCheck() {
        setStatus("狀態：盤點送出中...")

        thread {
            try {
                val items = JSONArray()
                inventoryDraft.values.forEach {
                    items.put(JSONObject().put("product_id", it.productId).put("counted_qty", it.qty))
                }

                val payload = JSONObject()
                    .put("company_id", 1)
                    .put("store_id", 1)
                    .put("checked_by", 1)
                    .put("items", items)

                val req = Request.Builder()
                    .url("$apiBase/inventory/check")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val res = client.newCall(req).execute()
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    runOnUiThread { setStatus("狀態：盤點失敗 $body") }
                    return@thread
                }

                val checkNo = JSONObject(body).getJSONObject("data").getString("check_no")
                inventoryDraft.clear()
                lastInventoryProductId = null
                runOnUiThread {
                    renderCurrent()
                    setStatus("狀態：盤點成功，單號 $checkNo")
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("狀態：盤點錯誤 ${e.message}") }
            }
        }
    }

    private fun removeLastInventoryItem() {
        if (mode != Mode.INVENTORY) return
        val pid = lastInventoryProductId ?: return
        inventoryDraft.remove(pid)
        lastInventoryProductId = inventoryDraft.keys.lastOrNull()
        renderCurrent()
        setStatus("狀態：已刪除最後一筆盤點商品")
    }

    private fun fetchTodayReports() {
        setStatus("狀態：查詢${reportRange.label}報表中...")
        thread {
            try {
                val now = LocalDate.now()
                val to = now
                val from = when (reportRange) {
                    ReportRange.TODAY -> now
                    ReportRange.YESTERDAY -> now.minusDays(1)
                    ReportRange.LAST7 -> now.minusDays(6)
                    ReportRange.LAST15 -> now.minusDays(14)
                    ReportRange.LAST30 -> now.minusDays(29)
                }
                val fromStr = from.toString()
                val toStr = to.toString()

                val dailyReq = Request.Builder()
                    .url("$apiBase/reports/daily-sales?company_id=1&from=$fromStr&to=$toStr")
                    .build()
                val mixReq = Request.Builder()
                    .url("$apiBase/reports/payment-mix?company_id=1&from=$fromStr&to=$toStr")
                    .build()

                val dailyRes = client.newCall(dailyReq).execute()
                val dailyBody = dailyRes.body?.string().orEmpty()
                val mixRes = client.newCall(mixReq).execute()
                val mixBody = mixRes.body?.string().orEmpty()

                if (!dailyRes.isSuccessful || !mixRes.isSuccessful) {
                    runOnUiThread { setStatus("狀態：報表查詢失敗") }
                    return@thread
                }

                val dailyData = JSONObject(dailyBody).getJSONArray("data")
                val mixData = JSONObject(mixBody).getJSONArray("data")

                val sb = StringBuilder()
                sb.append("【${reportRange.label}營收】 $fromStr ~ $toStr\n")
                var grandTotal = 0.0
                for (i in 0 until dailyData.length()) {
                    val r = dailyData.getJSONObject(i)
                    val totalSales = r.getString("total_sales").toDoubleOrNull() ?: 0.0
                    grandTotal += totalSales
                    sb.append("店：${r.getString("store_name")}")
                        .append("  單數：${r.getInt("order_count")}")
                        .append("  營收：${r.getString("total_sales")}\n")
                }
                sb.append("小計營收：${"%.2f".format(grandTotal)}\n")

                sb.append("\n【支付占比】\n")
                for (i in 0 until mixData.length()) {
                    val r = mixData.getJSONObject(i)
                    val amt = r.getString("total_amount").toDoubleOrNull() ?: 0.0
                    val ratio = if (grandTotal > 0) (amt * 100.0 / grandTotal) else 0.0
                    sb.append("${r.getString("method_name")}: ")
                        .append("筆數 ${r.getInt("txn_count")}, 金額 ${r.getString("total_amount")}")
                        .append(" (${"%.1f".format(ratio)}%)\n")
                }

                runOnUiThread {
                    cartText.text = sb.toString()
                    setStatus("狀態：${reportRange.label}報表查詢完成")
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("狀態：報表錯誤 ${e.message}") }
            }
        }
    }

    private fun renderCurrent() {
        val target = if (mode == Mode.SALES) cart else inventoryDraft
        if (target.isEmpty()) {
            cartText.text = "（尚無資料）"
            return
        }
        val sb = StringBuilder()
        var total = 0.0
        target.values.forEach {
            val line = it.price * it.qty
            total += line
            sb.append("${it.name}  x${it.qty}")
            if (mode == Mode.SALES) sb.append("  = ${"%.0f".format(line)}")
            sb.append("\n")
        }
        if (mode == Mode.SALES) sb.append("\n合計：${"%.0f".format(total)}")
        cartText.text = sb.toString()
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }
}
