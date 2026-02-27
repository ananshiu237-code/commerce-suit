package com.example.posmobile

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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

    enum class Mode { SALES, INVENTORY }
    private var mode = Mode.SALES

    data class Item(val productId: Int, val name: String, val price: Double, var qty: Int)

    private val cart = linkedMapOf<Int, Item>()
    private val inventoryDraft = linkedMapOf<Int, Item>()
    private var lastInventoryProductId: Int? = null

    private lateinit var statusText: TextView
    private lateinit var cartText: TextView
    private lateinit var modeButton: Button
    private lateinit var checkoutButton: Button
    private lateinit var inventoryButton: Button
    private lateinit var inventoryControls: LinearLayout
    private lateinit var salesEditControls: LinearLayout
    private lateinit var inventoryEditControls: LinearLayout

    private lateinit var inventoryQtyInput: EditText
    private lateinit var salesEditProductIdInput: EditText
    private lateinit var salesEditQtyInput: EditText
    private lateinit var inventoryEditProductIdInput: EditText
    private lateinit var inventoryEditQtyInput: EditText

    private lateinit var storeIdInput: EditText
    private lateinit var fromDateInput: EditText
    private lateinit var toDateInput: EditText

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val code = result.contents
        if (code.isNullOrBlank()) {
            setStatus("狀態：取消掃碼")
            return@registerForActivityResult
        }
        fetchProductByQr(code)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        cartText = findViewById(R.id.cartText)
        modeButton = findViewById(R.id.modeButton)
        checkoutButton = findViewById(R.id.checkoutButton)
        inventoryButton = findViewById(R.id.inventoryButton)
        inventoryControls = findViewById(R.id.inventoryControls)
        salesEditControls = findViewById(R.id.salesEditControls)
        inventoryEditControls = findViewById(R.id.inventoryEditControls)

        inventoryQtyInput = findViewById(R.id.inventoryQtyInput)
        salesEditProductIdInput = findViewById(R.id.salesEditProductIdInput)
        salesEditQtyInput = findViewById(R.id.salesEditQtyInput)
        inventoryEditProductIdInput = findViewById(R.id.inventoryEditProductIdInput)
        inventoryEditQtyInput = findViewById(R.id.inventoryEditQtyInput)

        storeIdInput = findViewById(R.id.storeIdInput)
        fromDateInput = findViewById(R.id.fromDateInput)
        toDateInput = findViewById(R.id.toDateInput)

        findViewById<Button>(R.id.modeButton).setOnClickListener { toggleMode() }
        findViewById<Button>(R.id.scanButton).setOnClickListener { startScan() }
        findViewById<Button>(R.id.checkoutButton).setOnClickListener { checkout() }
        findViewById<Button>(R.id.inventoryButton).setOnClickListener { submitInventoryCheck() }
        findViewById<Button>(R.id.removeLastButton).setOnClickListener { removeLastInventoryItem() }
        findViewById<Button>(R.id.salesApplyEditButton).setOnClickListener { applySalesEdit() }
        findViewById<Button>(R.id.salesRemoveButton).setOnClickListener { removeSalesItem() }
        findViewById<Button>(R.id.inventoryApplyEditButton).setOnClickListener { applyInventoryEdit() }
        findViewById<Button>(R.id.reportButton).setOnClickListener { fetchReports() }
        findViewById<Button>(R.id.syncStatusButton).setOnClickListener { fetchSyncStatus() }

        updateModeUI()
        renderCurrent()
    }

    private fun toggleMode() {
        mode = if (mode == Mode.SALES) Mode.INVENTORY else Mode.SALES
        setStatus("狀態：切換為 ${if (mode == Mode.SALES) "銷售" else "盤點"} 模式")
        updateModeUI()
        renderCurrent()
    }

    private fun updateModeUI() {
        val isSales = mode == Mode.SALES
        modeButton.text = "切換模式：目前 ${if (isSales) "銷售" else "盤點"}"
        checkoutButton.visibility = if (isSales) View.VISIBLE else View.GONE
        inventoryButton.visibility = if (isSales) View.GONE else View.VISIBLE
        salesEditControls.visibility = if (isSales) View.VISIBLE else View.GONE
        inventoryControls.visibility = if (isSales) View.GONE else View.VISIBLE
        inventoryEditControls.visibility = if (isSales) View.GONE else View.VISIBLE
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
        setStatus("狀態：掃碼成功 $code，查詢中...")
        thread {
            try {
                val storeId = storeIdInput.text.toString().toIntOrNull() ?: 1
                val url = "$apiBase/products/by-qr/$code?company_id=1&store_id=$storeId"
                val res = client.newCall(Request.Builder().url(url).build()).execute()
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    runOnUiThread { setStatus("狀態：查無商品") }
                    return@thread
                }

                val d = JSONObject(body).getJSONObject("data")
                val id = d.getInt("id")
                val name = d.getString("name")
                val price = d.getDouble("price")

                if (mode == Mode.SALES) {
                    val e = cart[id]
                    if (e == null) cart[id] = Item(id, name, price, 1) else e.qty += 1
                } else {
                    val counted = inventoryQtyInput.text.toString().toIntOrNull() ?: 1
                    inventoryDraft[id] = Item(id, name, price, if (counted < 0) 0 else counted)
                    lastInventoryProductId = id
                }

                runOnUiThread {
                    renderCurrent()
                    setStatus("狀態：已加入 $name")
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("狀態：錯誤 ${e.message}") }
            }
        }
    }

    private fun checkout() {
        if (cart.isEmpty()) {
            setStatus("狀態：購物車為空")
            return
        }
        thread {
            try {
                val storeId = storeIdInput.text.toString().toIntOrNull() ?: 1
                val arr = JSONArray()
                var total = 0.0
                cart.values.forEach {
                    arr.put(JSONObject().put("product_id", it.productId).put("qty", it.qty))
                    total += it.price * it.qty
                }
                val payload = JSONObject()
                    .put("company_id", 1)
                    .put("store_id", storeId)
                    .put("cashier_user_id", 1)
                    .put("items", arr)
                    .put("payment", JSONObject().put("method_code", "CASH").put("amount", total))

                val req = Request.Builder().url("$apiBase/orders")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
                val res = client.newCall(req).execute()
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    runOnUiThread { setStatus("狀態：送單失敗 $body") }
                    return@thread
                }
                val orderNo = JSONObject(body).getJSONObject("data").getString("order_no")
                cart.clear()
                runOnUiThread {
                    renderCurrent()
                    setStatus("狀態：送單成功 $orderNo")
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("狀態：錯誤 ${e.message}") }
            }
        }
    }

    private fun submitInventoryCheck() {
        if (inventoryDraft.isEmpty()) {
            setStatus("狀態：盤點清單為空")
            return
        }
        thread {
            try {
                val storeId = storeIdInput.text.toString().toIntOrNull() ?: 1
                val arr = JSONArray()
                inventoryDraft.values.forEach {
                    arr.put(JSONObject().put("product_id", it.productId).put("counted_qty", it.qty))
                }
                val payload = JSONObject()
                    .put("company_id", 1)
                    .put("store_id", storeId)
                    .put("checked_by", 1)
                    .put("items", arr)
                val req = Request.Builder().url("$apiBase/inventory/check")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
                val res = client.newCall(req).execute()
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    runOnUiThread { setStatus("狀態：盤點失敗 $body") }
                    return@thread
                }
                val checkNo = JSONObject(body).getJSONObject("data").getString("check_no")
                inventoryDraft.clear()
                runOnUiThread {
                    renderCurrent()
                    setStatus("狀態：盤點成功 $checkNo")
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("狀態：錯誤 ${e.message}") }
            }
        }
    }

    private fun applySalesEdit() {
        if (mode != Mode.SALES) return
        val pid = salesEditProductIdInput.text.toString().toIntOrNull()
        val qty = salesEditQtyInput.text.toString().toIntOrNull()
        if (pid == null || qty == null) {
            setStatus("狀態：請輸入銷售商品ID與新數量")
            return
        }
        val item = cart[pid]
        if (item == null) {
            setStatus("狀態：購物車沒有商品ID=$pid")
            return
        }
        if (qty <= 0) {
            cart.remove(pid)
            setStatus("狀態：已移除商品ID=$pid")
        } else {
            item.qty = qty
            setStatus("狀態：已更新商品ID=$pid 數量=$qty")
        }
        renderCurrent()
    }

    private fun removeSalesItem() {
        if (mode != Mode.SALES) return
        val pid = salesEditProductIdInput.text.toString().toIntOrNull()
        if (pid == null) {
            setStatus("狀態：請輸入要刪除的商品ID")
            return
        }
        if (cart.remove(pid) != null) {
            setStatus("狀態：已刪除商品ID=$pid")
            renderCurrent()
        } else {
            setStatus("狀態：購物車沒有商品ID=$pid")
        }
    }

    private fun removeLastInventoryItem() {
        if (mode != Mode.INVENTORY) return
        val pid = lastInventoryProductId ?: return
        inventoryDraft.remove(pid)
        lastInventoryProductId = inventoryDraft.keys.lastOrNull()
        renderCurrent()
        setStatus("狀態：已刪最後一筆")
    }

    private fun applyInventoryEdit() {
        if (mode != Mode.INVENTORY) return
        val pid = inventoryEditProductIdInput.text.toString().toIntOrNull()
        val qty = inventoryEditQtyInput.text.toString().toIntOrNull()
        if (pid == null || qty == null) {
            setStatus("狀態：請輸入商品ID與新實盤數")
            return
        }
        val item = inventoryDraft[pid]
        if (item == null) {
            setStatus("狀態：盤點清單沒有商品ID=$pid")
            return
        }
        item.qty = if (qty < 0) 0 else qty
        renderCurrent()
        setStatus("狀態：已更新商品ID=$pid 實盤數=$qty")
    }

    private fun fetchReports() {
        val storeId = storeIdInput.text.toString().toIntOrNull() ?: 1
        val from = fromDateInput.text.toString().trim()
        val to = toDateInput.text.toString().trim()
        if (from.isBlank() || to.isBlank()) {
            setStatus("狀態：請輸入起訖日期")
            return
        }

        setStatus("狀態：查詢報表中...")
        thread {
            try {
                val dailyUrl = "$apiBase/reports/daily-sales?company_id=1&store_id=$storeId&from=$from&to=$to"
                val mixUrl = "$apiBase/reports/payment-mix?company_id=1&store_id=$storeId&from=$from&to=$to"

                val dailyRes = client.newCall(Request.Builder().url(dailyUrl).build()).execute()
                val dailyBody = dailyRes.body?.string().orEmpty()
                val mixRes = client.newCall(Request.Builder().url(mixUrl).build()).execute()
                val mixBody = mixRes.body?.string().orEmpty()

                if (!dailyRes.isSuccessful || !mixRes.isSuccessful) {
                    runOnUiThread { setStatus("狀態：報表查詢失敗") }
                    return@thread
                }

                val daily = JSONObject(dailyBody).getJSONArray("data")
                val mix = JSONObject(mixBody).getJSONArray("data")

                val sb = StringBuilder()
                sb.append("【營收報表】店別=$storeId 期間 $from ~ $to\n")
                var total = 0.0
                for (i in 0 until daily.length()) {
                    val r = daily.getJSONObject(i)
                    val amount = r.getString("total_sales").toDoubleOrNull() ?: 0.0
                    total += amount
                    sb.append("${r.getString("business_date")} ${r.getString("store_name")} ")
                        .append("單數:${r.getInt("order_count")} 營收:${r.getString("total_sales")}\n")
                }
                sb.append("小計：${"%.2f".format(total)}\n\n")
                sb.append("【支付占比】\n")
                for (i in 0 until mix.length()) {
                    val r = mix.getJSONObject(i)
                    val amt = r.getString("total_amount").toDoubleOrNull() ?: 0.0
                    val pct = if (total > 0) amt * 100.0 / total else 0.0
                    sb.append("${r.getString("method_name")} 筆數:${r.getInt("txn_count")} 金額:${r.getString("total_amount")} (${"%.1f".format(pct)}%)\n")
                }
                runOnUiThread {
                    cartText.text = sb.toString()
                    setStatus("狀態：報表查詢完成")
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("狀態：報表錯誤 ${e.message}") }
            }
        }
    }

    private fun fetchSyncStatus() {
        setStatus("狀態：查詢總店同步監控中...")
        thread {
            try {
                val url = "$apiBase/sync/status?company_id=1"
                val res = client.newCall(Request.Builder().url(url).build()).execute()
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    runOnUiThread { setStatus("狀態：同步監控查詢失敗") }
                    return@thread
                }

                val data = JSONObject(body).getJSONObject("data")
                val summary = data.getJSONArray("summary")
                val byStore = data.getJSONArray("by_store")

                val sb = StringBuilder()
                sb.append("【同步狀態統計】\n")
                for (i in 0 until summary.length()) {
                    val r = summary.getJSONObject(i)
                    sb.append("${r.getString("sync_status")}: ${r.getInt("cnt")}\n")
                }
                sb.append("\n【分店同步狀況】\n")
                for (i in 0 until byStore.length()) {
                    val r = byStore.getJSONObject(i)
                    sb.append("店ID ${r.getInt("source_store_id")} ${r.optString("store_name", "")}")
                        .append("\n上傳次數:${r.getInt("upload_count")}")
                        .append(" 最後上傳:${r.optString("last_upload_at", "-")}")
                        .append("\n最後同步:${r.optString("last_synced_at", "-")}\n\n")
                }

                runOnUiThread {
                    cartText.text = sb.toString()
                    setStatus("狀態：同步監控查詢完成")
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("狀態：同步監控錯誤 ${e.message}") }
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
            sb.append("ID:${it.productId} ${it.name} x${it.qty}")
            if (mode == Mode.SALES) sb.append(" = ${"%.0f".format(line)}")
            sb.append("\n")
        }
        if (mode == Mode.SALES) sb.append("\n合計：${"%.0f".format(total)}")
        cartText.text = sb.toString()
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }
}
