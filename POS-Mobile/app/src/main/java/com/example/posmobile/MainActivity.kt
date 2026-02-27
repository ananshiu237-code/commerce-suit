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
    enum class Page { SALES, INVENTORY, REPORTS, HQ }

    private var mode = Mode.SALES
    private var page = Page.SALES

    data class Item(val productId: Int, val name: String, val price: Double, var qty: Int)

    private val cart = linkedMapOf<Int, Item>()
    private val inventoryDraft = linkedMapOf<Int, Item>()
    private var lastInventoryProductId: Int? = null

    private lateinit var statusText: TextView
    private lateinit var cartText: TextView
    private lateinit var scanButton: Button

    private lateinit var salesSection: LinearLayout
    private lateinit var inventorySection: LinearLayout
    private lateinit var reportsSection: LinearLayout
    private lateinit var hqSection: LinearLayout
    private lateinit var filterSection: LinearLayout

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
        scanButton = findViewById(R.id.scanButton)

        salesSection = findViewById(R.id.salesSection)
        inventorySection = findViewById(R.id.inventorySection)
        reportsSection = findViewById(R.id.reportsSection)
        hqSection = findViewById(R.id.hqSection)
        filterSection = findViewById(R.id.filterSection)

        inventoryQtyInput = findViewById(R.id.inventoryQtyInput)
        salesEditProductIdInput = findViewById(R.id.salesEditProductIdInput)
        salesEditQtyInput = findViewById(R.id.salesEditQtyInput)
        inventoryEditProductIdInput = findViewById(R.id.inventoryEditProductIdInput)
        inventoryEditQtyInput = findViewById(R.id.inventoryEditQtyInput)

        storeIdInput = findViewById(R.id.storeIdInput)
        fromDateInput = findViewById(R.id.fromDateInput)
        toDateInput = findViewById(R.id.toDateInput)

        findViewById<Button>(R.id.navSalesButton).setOnClickListener { showPage(Page.SALES) }
        findViewById<Button>(R.id.navInventoryButton).setOnClickListener { showPage(Page.INVENTORY) }
        findViewById<Button>(R.id.navReportsButton).setOnClickListener { showPage(Page.REPORTS) }
        findViewById<Button>(R.id.navHqButton).setOnClickListener { showPage(Page.HQ) }

        scanButton.setOnClickListener { startScan() }
        findViewById<Button>(R.id.checkoutButton).setOnClickListener { checkout() }
        findViewById<Button>(R.id.inventoryButton).setOnClickListener { submitInventoryCheck() }
        findViewById<Button>(R.id.removeLastButton).setOnClickListener { removeLastInventoryItem() }
        findViewById<Button>(R.id.salesApplyEditButton).setOnClickListener { applySalesEdit() }
        findViewById<Button>(R.id.salesRemoveButton).setOnClickListener { removeSalesItem() }
        findViewById<Button>(R.id.inventoryApplyEditButton).setOnClickListener { applyInventoryEdit() }

        findViewById<Button>(R.id.reportButton).setOnClickListener { fetchReports() }
        findViewById<Button>(R.id.storeRankingButton).setOnClickListener { fetchStoreRanking() }
        findViewById<Button>(R.id.lowStockButton).setOnClickListener { fetchLowStock() }
        findViewById<Button>(R.id.replenishButton).setOnClickListener { fetchReplenishmentSuggestions() }

        findViewById<Button>(R.id.syncStatusButton).setOnClickListener { fetchSyncStatus() }
        findViewById<Button>(R.id.hqSummaryButton).setOnClickListener { fetchHqSummary() }
        findViewById<Button>(R.id.createPoDraftButton).setOnClickListener { createPoDraft() }

        showPage(Page.SALES)
        renderCurrent()
    }

    private fun showPage(p: Page) {
        page = p
        salesSection.visibility = if (p == Page.SALES) View.VISIBLE else View.GONE
        inventorySection.visibility = if (p == Page.INVENTORY) View.VISIBLE else View.GONE
        reportsSection.visibility = if (p == Page.REPORTS) View.VISIBLE else View.GONE
        hqSection.visibility = if (p == Page.HQ) View.VISIBLE else View.GONE
        filterSection.visibility = if (p == Page.REPORTS || p == Page.HQ) View.VISIBLE else View.GONE
        scanButton.visibility = if (p == Page.SALES || p == Page.INVENTORY) View.VISIBLE else View.GONE

        mode = if (p == Page.INVENTORY) Mode.INVENTORY else Mode.SALES
        setStatus("狀態：切換到${when (p) {
            Page.SALES -> "銷售頁"
            Page.INVENTORY -> "盤點頁"
            Page.REPORTS -> "報表頁"
            Page.HQ -> "總店頁"
        }}")
        renderCurrent()
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
        if (cart.isEmpty()) return setStatus("狀態：購物車為空")
        thread {
            try {
                val storeId = storeIdInput.text.toString().toIntOrNull() ?: 1
                val arr = JSONArray()
                var total = 0.0
                cart.values.forEach {
                    arr.put(JSONObject().put("product_id", it.productId).put("qty", it.qty))
                    total += it.price * it.qty
                }
                val payload = JSONObject().put("company_id", 1).put("store_id", storeId).put("cashier_user_id", 1)
                    .put("items", arr).put("payment", JSONObject().put("method_code", "CASH").put("amount", total))
                val req = Request.Builder().url("$apiBase/orders")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
                val res = client.newCall(req).execute()
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) return@thread runOnUiThread { setStatus("狀態：送單失敗 $body") }
                val orderNo = JSONObject(body).getJSONObject("data").getString("order_no")
                cart.clear()
                runOnUiThread { renderCurrent(); setStatus("狀態：送單成功 $orderNo") }
            } catch (e: Exception) {
                runOnUiThread { setStatus("狀態：錯誤 ${e.message}") }
            }
        }
    }

    private fun submitInventoryCheck() {
        if (inventoryDraft.isEmpty()) return setStatus("狀態：盤點清單為空")
        thread {
            try {
                val storeId = storeIdInput.text.toString().toIntOrNull() ?: 1
                val arr = JSONArray()
                inventoryDraft.values.forEach { arr.put(JSONObject().put("product_id", it.productId).put("counted_qty", it.qty)) }
                val payload = JSONObject().put("company_id", 1).put("store_id", storeId).put("checked_by", 1).put("items", arr)
                val req = Request.Builder().url("$apiBase/inventory/check")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
                val res = client.newCall(req).execute()
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) return@thread runOnUiThread { setStatus("狀態：盤點失敗 $body") }
                val checkNo = JSONObject(body).getJSONObject("data").getString("check_no")
                inventoryDraft.clear()
                runOnUiThread { renderCurrent(); setStatus("狀態：盤點成功 $checkNo") }
            } catch (e: Exception) {
                runOnUiThread { setStatus("狀態：錯誤 ${e.message}") }
            }
        }
    }

    private fun applySalesEdit() {
        val pid = salesEditProductIdInput.text.toString().toIntOrNull()
        val qty = salesEditQtyInput.text.toString().toIntOrNull()
        if (pid == null || qty == null) return setStatus("狀態：請輸入銷售商品ID與新數量")
        val item = cart[pid] ?: return setStatus("狀態：購物車沒有商品ID=$pid")
        if (qty <= 0) cart.remove(pid) else item.qty = qty
        renderCurrent(); setStatus("狀態：已更新銷售項目")
    }

    private fun removeSalesItem() {
        val pid = salesEditProductIdInput.text.toString().toIntOrNull() ?: return setStatus("狀態：請輸入要刪除的商品ID")
        if (cart.remove(pid) != null) { renderCurrent(); setStatus("狀態：已刪除商品ID=$pid") }
        else setStatus("狀態：購物車沒有商品ID=$pid")
    }

    private fun removeLastInventoryItem() {
        val pid = lastInventoryProductId ?: return
        inventoryDraft.remove(pid)
        lastInventoryProductId = inventoryDraft.keys.lastOrNull()
        renderCurrent(); setStatus("狀態：已刪最後一筆")
    }

    private fun applyInventoryEdit() {
        val pid = inventoryEditProductIdInput.text.toString().toIntOrNull()
        val qty = inventoryEditQtyInput.text.toString().toIntOrNull()
        if (pid == null || qty == null) return setStatus("狀態：請輸入商品ID與新實盤數")
        val item = inventoryDraft[pid] ?: return setStatus("狀態：盤點清單沒有商品ID=$pid")
        item.qty = if (qty < 0) 0 else qty
        renderCurrent(); setStatus("狀態：已更新盤點項目")
    }

    private fun fetchReports() = reportRequest("營業報表", "$apiBase/reports/daily-sales?company_id=1&store_id=${store()}&from=${from()}&to=${to()}")
    private fun fetchStoreRanking() = reportRequest("分店營收排行", "$apiBase/reports/store-ranking?company_id=1&from=${from()}&to=${to()}")
    private fun fetchLowStock() = reportRequest("低庫存明細", "$apiBase/reports/low-stock?company_id=1&store_id=${store()}")
    private fun fetchReplenishmentSuggestions() = reportRequest("補貨建議", "$apiBase/reports/replenishment-suggestions?company_id=1&store_id=${store()}&target_multiplier=1.5")
    private fun fetchSyncStatus() = reportRequest("總店同步監控", "$apiBase/sync/status?company_id=1")
    private fun fetchHqSummary() = reportRequest("總店營運總覽", "$apiBase/dashboard/hq-summary?company_id=1&business_date=${to()}")

    private fun createPoDraft() {
        setStatus("狀態：產生採購單草稿中...")
        thread {
            try {
                val payload = JSONObject().put("company_id", 1).put("store_id", store()).put("supplier_id", 1).put("target_multiplier", 1.5)
                val req = Request.Builder().url("$apiBase/purchase-orders/draft-from-replenishment")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
                val res = client.newCall(req).execute()
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) return@thread runOnUiThread { setStatus("狀態：PO草稿失敗 $body") }
                val d = JSONObject(body).getJSONObject("data")
                runOnUiThread {
                    cartText.text = "PO單號: ${d.getString("po_no")}\n項目數: ${d.getInt("item_count")}\n總金額: ${d.getDouble("total_amount")}" 
                    setStatus("狀態：PO草稿建立成功")
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("狀態：PO草稿錯誤 ${e.message}") }
            }
        }
    }

    private fun reportRequest(title: String, url: String) {
        setStatus("狀態：查詢${title}中...")
        thread {
            try {
                val res = client.newCall(Request.Builder().url(url).build()).execute()
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) return@thread runOnUiThread { setStatus("狀態：$title 查詢失敗") }
                runOnUiThread {
                    cartText.text = "【$title】\n$body"
                    setStatus("狀態：$title 查詢完成")
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("狀態：$title 錯誤 ${e.message}") }
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

    private fun store() = storeIdInput.text.toString().toIntOrNull() ?: 1
    private fun from() = fromDateInput.text.toString().trim().ifBlank { "2026-02-27" }
    private fun to() = toDateInput.text.toString().trim().ifBlank { "2026-02-27" }
    private fun setStatus(text: String) { statusText.text = text }
}
