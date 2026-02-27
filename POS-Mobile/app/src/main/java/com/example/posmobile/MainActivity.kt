package com.example.posmobile

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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

    data class CartItem(
        val productId: Int,
        val name: String,
        val price: Double,
        var qty: Int
    )

    private val cart = linkedMapOf<Int, CartItem>()

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
        val scanButton = findViewById<Button>(R.id.scanButton)
        val checkoutButton = findViewById<Button>(R.id.checkoutButton)

        scanButton.setOnClickListener { startScan() }
        checkoutButton.setOnClickListener { checkout() }

        renderCart()
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

                val root = JSONObject(body)
                val data = root.getJSONObject("data")
                val id = data.getInt("id")
                val name = data.getString("name")
                val price = data.getDouble("price")

                val existing = cart[id]
                if (existing == null) {
                    cart[id] = CartItem(id, name, price, 1)
                } else {
                    existing.qty += 1
                }

                runOnUiThread {
                    setStatus("狀態：已加入 $name")
                    renderCart()
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
                    val obj = JSONObject()
                    obj.put("product_id", it.productId)
                    obj.put("qty", it.qty)
                    items.put(obj)
                    total += it.price * it.qty
                }

                val payload = JSONObject()
                payload.put("company_id", 1)
                payload.put("store_id", 1)
                payload.put("cashier_user_id", 1)
                payload.put("items", items)
                payload.put("payment", JSONObject().put("method_code", "CASH").put("amount", total))

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

                val root = JSONObject(body)
                val data = root.getJSONObject("data")
                val orderNo = data.getString("order_no")
                val totalResp = data.getDouble("total")

                cart.clear()
                runOnUiThread {
                    renderCart()
                    setStatus("狀態：送單成功，訂單 $orderNo，金額 $totalResp")
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("狀態：送單錯誤 ${e.message}") }
            }
        }
    }

    private fun renderCart() {
        if (cart.isEmpty()) {
            cartText.text = "（尚無商品）"
            return
        }

        val sb = StringBuilder()
        var total = 0.0
        cart.values.forEach {
            val line = it.price * it.qty
            total += line
            sb.append("${it.name}  x${it.qty}  @ ${it.price}  = ${"%.0f".format(line)}\n")
        }
        sb.append("\n合計：${"%.0f".format(total)}")
        cartText.text = sb.toString()
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }
}
