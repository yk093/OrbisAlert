package com.ykun.orbisalert

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.clustering.ClusterManager
import com.google.maps.android.clustering.view.DefaultClusterRenderer
import org.json.JSONArray
import kotlin.math.*

import android.content.Context
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions

import android.widget.Button
import android.widget.TextView
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import android.os.Build
import androidx.core.graphics.scale
import android.widget.ImageView
import android.widget.Toast
import com.google.android.gms.ads.AdError

// AdMob用
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

// firebase用
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var map: GoogleMap
    private lateinit var clusterManager: ClusterManager<OrbisMarker>
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var accuracyCircle: Circle? = null
    private var headingArrow: Marker? = null
    private var infoWindowAdapter: InfoWindowAdapter? = null // InfoWindowAdapterのインスタンスを保持する変数

    private val iconCache = mutableMapOf<String, BitmapDescriptor>()
    private var isAutoCentering = true
    private lateinit var locationInfoText: TextView

    private val DEFAULT_ZOOM = 14f

    private lateinit var gpsOverlayText: TextView
    private lateinit var distanceOverlayText: TextView

    private var sectorPolygon: Polygon? = null

    // native admob再読込
    private var adReloadJob: Job? = null
    private val adReloadIntervalMillis = 60_000L  // ← 60秒ごとに再読み込み

    // リワード広告
    private var rewardedAd: RewardedAd? = null

    // 広告ユニットid
    private lateinit var nativeAdUnitId: String
    private lateinit var rewardAdUnitId: String

    // json url
    private lateinit var orbisJsonUrl: String

    // 権限確認
    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }
    }.toTypedArray()

    private fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private var isLocationPermissionChecked = false

    companion object {
        val arrSystemType = mapOf(
            1 to "レーダー式",
            2 to "Ｈシステム",
            3 to "ループコイル式",
            4 to "ループコイル式Ｈ",
            5 to "移動式車両",
            6 to "移動式小型",
            7 to "移動式中型",
            8 to "レーザー自立型",
            9 to "レーザー支柱型",
            10 to "半固定式"
        )

        val arrCameraPos = mapOf(
            1 to "左側に設置",
            2 to "右側に設置",
            3 to "上に設置"
        )

        val arrLaneSide = mapOf(
            1 to "左",
            2 to "右"
        )

        val arrRoadType = mapOf(
            1 to "一般道",
            2 to "有料・高速"
        )

        val arrDirectionType = mapOf(
            1 to "上り方面",
            2 to "下り方面",
            3 to "北行き",
            4 to "北東行き",
            5 to "東行き",
            6 to "南東行き",
            7 to "南行き",
            8 to "南西行き",
            9 to "西行き",
            10 to "北西行き",
            11 to "内回り",
            12 to "外回り"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // ← ここでレイアウトが読み込まれる

        // onCreate メソッド内で Context が利用可能になってから初期化
        nativeAdUnitId = getString(R.string.admob_native_id)
        rewardAdUnitId = getString(R.string.admob_reward_id)
        orbisJsonUrl = getString(R.string.orbis_json_url)

        // アプリ起動時に権限が既に許可されているか確認し、サービス起動・レシーバー登録
        if (hasAllPermissions()) {
            Log.d("Permission", "アプリ起動時：全てのパーミッションが既に許可されています。")
            setupLocationServices() // サービスとレシーバーのセットアップを呼び出す
        } else {
            // 権限が不足している場合、権限リクエストプロセスを開始
            Log.d("Permission", "アプリ起動時：パーミッションが不足しています。リクエストを開始します。")
            // 位置情報権限をリクエスト
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    LOCATION_PERMISSION_REQUEST_CODE
                )
            }
        }

        // admob初期化
        MobileAds.initialize(this)
        loadNativeAd()
        loadRewardAd()

        // jsonファイルの取得日表示
        showJsonUpdateTime()

        // ネイティブ広告の定期読み込み
        adReloadJob = lifecycleScope.launch {
            while (isActive) {
                delay(adReloadIntervalMillis)
                Log.d("AdReload", "周期的に広告を再読み込み")
                loadNativeAd()
            }
        }

        // 現在地ボタンのクリック処理
        findViewById<Button>(R.id.btn_my_location).setOnClickListener {
            headingArrow?.position?.let { pos ->
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, DEFAULT_ZOOM))
                isAutoCentering = true
                Log.d("MainActivity", "現在地ボタンで再センタリング")
            }

            // 表示中のオービスのinfoWindowは閉じる
            for (item in clusterManager.algorithm.items) {
                item.marker?.hideInfoWindow()
            }
        }

        // jsonファイルの更新ボタン
        findViewById<Button>(R.id.btn_update_json).setOnClickListener {
            Log.d("reward", "ボタンが押された")
            showRewardAd {
                downloadOrbisJson()
            }
        }

        // TextView を取得
        locationInfoText = findViewById(R.id.location_info)
        // gps不安定のオーバーレイを取得
        gpsOverlayText = findViewById(R.id.gps_overlay)
        // 残距離のオーバーレイを取得
        distanceOverlayText = findViewById(R.id.distance_overlay)


        // マップ初期化
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map_fragment) as? SupportMapFragment
        if (mapFragment == null) {
            Log.e("MainActivity", "### map_fragment が見つからないか、SupportMapFragment ではありません。XMLファイルを確認してください！")
            // ここでアプリの動作を停止させるか、マップなしで続行するなどのエラーハンドリングを行う
            return // これがないと、mapFragment が null のまま getMapAsync を呼び出し、クラッシュします
        } else {
            Log.d("MainActivity", "### map_fragment は見つかりました。getMapAsync を呼び出します。")
            mapFragment.getMapAsync(this)
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

    }

    override fun onDestroy() {
        adReloadJob?.cancel()                        // Coroutine 停止（非同期タスク）

        unregisterReceiver(locationReceiver)         // BroadcastReceiver の解除
        val stopIntent = Intent(this, LocationService::class.java)
        stopService(stopIntent)                      // サービス停止

        super.onDestroy()                            // 最後に Activity 本体を破棄
        Log.d("MainActivity", "LocationService停止")
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "フォアグラウンド復帰")

        // フォアグラウンド復帰時に再度 GPS更新間隔を再設定
        val intent = Intent("com.example.orbisalert.FOREGROUND_REENTERED")
        sendBroadcast(intent)
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "🕶 バックグラウンド移行")

        // Service に通知
        val intent = Intent("com.example.orbisalert.BACKGROUND_ENTERED")
        sendBroadcast(intent)
    }

    // LocationService からの位置情報受信 → 現在地マーカー＋方向マーカー＋精度円を更新
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val lat = intent?.getDoubleExtra("lat", 0.0) ?: return
            val lng = intent.getDoubleExtra("lng", 0.0)
            val speed = intent.getDoubleExtra("speed", 0.0)
            val accuracy = intent.getFloatExtra("accuracy", 0f)
            val bearing = intent.getDoubleExtra("bearing", 0.0)

            Log.d("MainActivity", "位置更新: lat=$lat, lng=$lng, speed=$speed, accuracy=$accuracy, bearing=$bearing")

            val position = LatLng(lat, lng)

            // 判断：GPSが不安定か（しきい値は適宜調整）
            val isGpsUnstable = accuracy > 20
            if (isGpsUnstable) {
                gpsOverlayText.text = "⚠ 現在地の精度が低下しています（±${"%.1f".format(accuracy)}m）"
                gpsOverlayText.visibility = View.VISIBLE
            } else {
                gpsOverlayText.visibility = View.GONE
            }

            // オービスまでの距離を表示
            val distanceToOrbis = intent.getDoubleExtra("distanceToOrbis", -1.0)

            if (distanceToOrbis >= 0 && distanceToOrbis < 2000) {
                distanceOverlayText.text = "⚠ オービスまで残り約 ${distanceToOrbis.toInt()} m"
                distanceOverlayText.visibility = View.VISIBLE
            } else {
                distanceOverlayText.visibility = View.GONE
            }


            // headingArrow を更新
            headingArrow?.let { arrow ->
                arrow.isVisible = true
                arrow.position = position

                val isArrowMode = speed >= 15.0
                val newTag = if (isArrowMode) "arrow" else "dot"
                val currentTag = arrow.tag as? String

                // bearing の更新は高速走行時のみ
                if (isArrowMode) {
                    arrow.rotation = bearing.toFloat()
                }

                // アイコンが変わるときだけ再設定
                if (currentTag != newTag) {
                    val iconRes = if (isArrowMode) R.drawable.blue_arrow_icon else R.drawable.blue_dot_icon
                    val bitmap = BitmapFactory.decodeResource(resources, iconRes)
                    val scaledBitmap = bitmap.scale(if (isArrowMode) 90 else 80, if (isArrowMode) 120 else 80)

                    arrow.setIcon(BitmapDescriptorFactory.fromBitmap(scaledBitmap))
                    arrow.tag = newTag
                }
            } ?: run {
                Log.e("LocationReceiver", "headingArrow is unexpectedly null!")
            }

            // 現在地から ±10度の2km扇形を描画
            //オービスの検知は時速30km/h以上で有効
            if (speed >= 30.0) {
                sectorPolygon?.remove() // 前回の扇形を削除
                sectorPolygon = drawSector(position, bearing, 2000.0, 20.0,  map)
            } else {
                sectorPolygon?.remove()
                sectorPolygon = null
            }

            // 精度円
            accuracyCircle?.let { circle ->
                circle.isVisible = true // ★表示する
                circle.center = position
                circle.radius = accuracy.toDouble()
            } ?: run {
                Log.e("LocationReceiver", "accuracyCircle is unexpectedly null!")
                // このケースは onMapReady で初期化していれば基本的には発生しないはず
            }

            // 自動センタリング
            if (isAutoCentering) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(position, DEFAULT_ZOOM))
            }

            val time = intent.getStringExtra("time") ?: ""
            val infoText =
                """
                更新: $time
                緯度: ${lat.format(6)}
                経度: ${lng.format(6)}
                速度: ${"%.1f".format(speed)} km/h
                精度: ${"%.1f".format(accuracy)} m
                """.trimIndent()

            locationInfoText.text = infoText

        }
    }

    private fun setupLocationServices() {
        try {
            // 位置情報ブロードキャストを受信
            val filter = IntentFilter("com.example.orbisalert.LOCATION_UPDATE")
            ContextCompat.registerReceiver(
                this,
                locationReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            Log.d("Permission", "locationReceiver 登録完了。")
        } catch (e: IllegalArgumentException) {
            Log.w("Permission", "locationReceiver は既に登録されています。", e)
        }

        // LocationService を起動（バックグラウンド）
        val intent = Intent(this, LocationService::class.java)
        ContextCompat.startForegroundService(this, intent)

        Log.d("Permission", "サービスとレシーバーがセットアップされました。")
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false // 二本指回転を無効化
        map.uiSettings.isTiltGesturesEnabled = false   // チルト（斜め視点）を無効化

        // 位置情報権限がある場合にのみ isMyLocationEnabled を設定
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                map.isMyLocationEnabled = false // デフォルトの現在地ボタンを無効化
                Log.d("onMapReady", "map.isMyLocationEnabled を false に設定しました。")
            } catch (e: SecurityException) {
                Log.e("onMapReady", "位置情報権限があるにも関わらず SecurityException が発生しました。", e)
            }
        } else {
            Log.w("onMapReady", "位置情報権限がないため、map.isMyLocationEnabled は設定されません。")
        }

        // 初期カメラ位置を日本の地理的中心に設定
        val japanCenter = LatLng(36.2048, 138.2529)
        val initialZoom = 4.5f // 日本全体を表示
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(japanCenter, initialZoom))
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(japanCenter, initialZoom))

        // 進行方向マーカー（arrow_icon.png を res/drawable に配置）
        // 初期状態(10km/h以下)では青い点アイコンで作成
        // headingArrow を初期作成 (最初は非表示、またはデフォルト位置・アイコンで)
        val initialPosition = LatLng(0.0, 0.0) // 仮の初期位置
        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.blue_dot_icon)
        val scaledBitmap = bitmap.scale(80, 80)  // 矢印より小さく
        val descriptor = BitmapDescriptorFactory.fromBitmap(scaledBitmap)
        headingArrow = map.addMarker(
            MarkerOptions()
                .position(initialPosition)
                .icon(descriptor)
                .anchor(0.5f, 0.5f)
                .rotation(0f)
                .flat(true)
                .visible(false)
                .zIndex(10f)
        )
        headingArrow?.tag = "dot" // 初期状態は "dot"

        // 精度円
        val initialAccuracy = 0.0
        val circleColor = 0x6633B5E5 // 半透明のGoogle標準の青っぽい色（ARGB）
        accuracyCircle = map.addCircle(
            CircleOptions()
                .center(initialPosition)
                .radius(initialAccuracy)
                .strokeColor(0xAA33B5E5.toInt())  // 縁線：やや濃い青
                .fillColor(circleColor.toInt())   // 塗り：半透明の青
                .strokeWidth(3f)
                .visible(false) // ★最初は非表示にしておく
        )

        setupClusterManager()

        // InfoWindowAdapterのインスタンスを一度だけ作成
        infoWindowAdapter = InfoWindowAdapter()
        // クラスタマーカーに対して InfoWindowAdapter を設定
        clusterManager.markerCollection.setInfoWindowAdapter(infoWindowAdapter!!)

        // Coroutine でバックグラウンド処理
        lifecycleScope.launch(Dispatchers.Default) {
            loadOrbisMarkers()
        }

        // ユーザー操作を検知
        map.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                isAutoCentering = false
                Log.d("MainActivity", "ユーザー操作でセンタリング停止")
            }
        }
    }

    private fun setupClusterManager() {
        clusterManager = ClusterManager(this, map)
        val renderer = OrbisRenderer(this, map, clusterManager)
        clusterManager.renderer = renderer

        // カメラ移動後に矢印を再描画
        map.setOnCameraIdleListener {
            clusterManager.onCameraIdle()  // クラスタの再構築
            renderer.refreshVisibleArrows()  // 矢印の再描画
        }

        // OrbisMarker 本体をタップしたとき（clusterManager管理下のもの）
        clusterManager.setOnClusterItemClickListener { item ->
            val marker = clusterManager.markerCollection.markers.find { it.tag == item }
            if (marker != null) {
                marker.showInfoWindow()
                Log.d("ClusterDebug", "個別マーカークリック: ${item.getTitle()}")
            } else {
                Log.e("ClusterDebug", "クリックされたアイテムに対応するマーカーが見つかりません。")
            }
            true
        }

        // クラスタ円をタップしたときの挙動を上書き（InfoWindow防止）
        clusterManager.setOnClusterClickListener { cluster ->
            // ズームインしてクラスタを展開する
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(cluster.position, map.cameraPosition.zoom + 2))
            true  // ← デフォルトの InfoWindow を無効化
        }
    }

    // オービスマーカーを systemType と roadType に基づいてスケーリング付きで表示
    private fun loadOrbisMarkers() {
        val json = assets.open("orbis_locations.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val lat = obj.getDouble("decLatitude")
            val lng = obj.getDouble("decLongitude")
            val title = obj.optString("strRoadName", "不明")
            val snippet = obj.optString("strPrefecturesName", "") +
                    obj.optString("strCityName", "") +
                    obj.optString("strAddress", "")

            val systemType = obj.optInt("intSystemType", 0)

            // 移動式オービスは表示から除外
            // val isMobileOrbis = systemType in listOf(5, 6, 7)
            //if (isMobileOrbis) continue  // ← マップに表示しない

            val roadType = obj.optInt("intRoadType", 1)

            // 詳細データも取得
            val cameraPos = obj.optInt("intCameraPosition", 0)
            val speedLimit = obj.optInt("intSpeedLimit", 0)
            val laneType = obj.optInt("intLaneType", 0)
            val laneSide = obj.optInt("intLaneSide", 0)
            val laneNum = obj.optInt("intLaneNum", 0)

            val iconResId = getPinResourceId(systemType, roadType)
            if (iconResId != 0) {
                val cacheKey = "$systemType-$roadType"
                val descriptor = iconCache.getOrPut(cacheKey) {
                    val bitmap = BitmapFactory.decodeResource(resources, iconResId)
                    val scaledBitmap = bitmap.scale(110, 110, false)
                    BitmapDescriptorFactory.fromBitmap(scaledBitmap)
                }
                // OrbisMarker に全データを渡す
                val marker = OrbisMarker(
                    lat, lng, title, snippet, descriptor,
                    systemType = systemType,
                    cameraPos = cameraPos,
                    speedLimit = speedLimit,
                    laneType = laneType,
                    laneSide = laneSide,
                    laneNum = laneNum,
                    direction = obj.optInt("intDirection", 0)
                )

                clusterManager.addItem(marker)

            } else {
                Log.w("loadOrbisMarkers", "invalid resource id for systemType=$systemType roadType=$roadType")
            }
        }

        // ここだけメインスレッドで呼ぶ
        lifecycleScope.launch(Dispatchers.Main) {
            clusterManager.cluster()
        }
    }



    // ピン画像のリソースIDを返す（drawable にある小文字ファイル名を前提）
    private fun getPinResourceId(systemType: Int, roadType: Int): Int {
        val roadLabel = if (roadType == 1) "blue" else "green"
        val name = when (systemType) {
            1 -> "${roadLabel}_r_marker"
            2 -> "${roadLabel}_h_marker"
            3 -> "${roadLabel}_l_marker"
            4 -> "${roadLabel}_lh_marker"
            5 -> "orange_iv_marker"
            6 -> "orange_is_marker"
            7 -> "orange_im_marker"
            8 -> "${roadLabel}_lp_marker"
            9 -> "${roadLabel}_ls_marker"
            10 -> "${roadLabel}_hk_marker"
            else -> "red_pin"
        }
        val id = resources.getIdentifier(name, "drawable", packageName)
        if (id == 0) {
            Log.w("getPinResourceId", "drawable/$name が見つかりません")
        }
        return id
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d("PermissionResult", "onRequestPermissionsResult called for requestCode: $requestCode")

        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                isLocationPermissionChecked = true
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("Permission", "ACCESS_FINE_LOCATION 権限が許可されました")
                } else {
                    Log.w("Permission", "⚠ACCESS_FINE_LOCATION 権限が拒否されました")
                }
                checkPermissionResults() // 最終的な権限状態の確認
            }
        }
    }

    private fun checkPermissionResults() {
        if (isLocationPermissionChecked) {
            if (!hasAllPermissions()) {
                Toast.makeText(this, "現在地表示には位置情報の許可が必要です", Toast.LENGTH_LONG).show()
                // 権限が不足している場合、サービスを停止
                val stopIntent = Intent(this, LocationService::class.java)
                stopService(stopIntent)
            } else {
                Log.d("Permission", "全てのパーミッションが許可されました")
                // 全ての権限が揃った時点でサービスを開始し、レシーバーを登録
                setupLocationServices()
            }
        }
    }

    // InfoWindowAdapter
    inner class InfoWindowAdapter : GoogleMap.InfoWindowAdapter {
        override fun getInfoWindow(marker: Marker): View? {
            // OrbisMarker 用 InfoWindow
            val orbis = marker.tag as? OrbisMarker
            if (orbis != null) {
                val view = layoutInflater.inflate(R.layout.info_window, null)
                view.findViewById<TextView>(R.id.title).text = orbis.getTitle()
                view.findViewById<TextView>(R.id.address).text = orbis.getSnippet()
                view.findViewById<TextView>(R.id.system_type).text =
                    arrSystemType[orbis.systemType] ?: "不明"
                view.findViewById<TextView>(R.id.camera_pos).text =
                    arrCameraPos[orbis.cameraPos] ?: ""
                view.findViewById<TextView>(R.id.speed_limit).text =
                    if (orbis.speedLimit > 0) "${orbis.speedLimit}km/h" else "不明"
                view.findViewById<TextView>(R.id.lane_info).text =
                    if (orbis.laneType == 1) "全車線"
                    else "${arrLaneSide[orbis.laneSide] ?: ""}側 ${if (orbis.laneNum > 0) "${orbis.laneNum}車線" else ""}"
                return view
            } else {
                // タグが違うマーカー用には空ビューを返して InfoWindow 抑制
                return View(this@MainActivity)
            }
        }

        override fun getInfoContents(marker: Marker): View? {
            Log.d("InfoWindowAdapter", "getInfoContents called for marker title: ${marker.title}")
            return null // デフォルトのウィンドウフレーム内のコンテンツをカスタマイズしたい場合を除き、nullを返します。
        }
    }

    // native AdMob
    private fun loadNativeAd() {
        val adContainer = findViewById<LinearLayout>(R.id.ad_container)
        val adLoader = AdLoader.Builder(this, nativeAdUnitId) // 広告ユニットID
            .forNativeAd { nativeAd ->
                val adView = layoutInflater.inflate(R.layout.ad_native_layout, null) as NativeAdView
                populateNativeAdView(nativeAd, adView)

                // フェードアニメーション
                if (adContainer.childCount > 0) {
                    val oldAdView = adContainer.getChildAt(0)

                    // 既存の広告をフェードアウト → 新しい広告をフェードイン
                    oldAdView.animate()
                        .alpha(0f)
                        .setDuration(300)
                        .withEndAction {
                            adContainer.removeAllViews()
                            adView.alpha = 0f
                            adContainer.addView(adView)

                            adView.animate()
                                .alpha(1f)
                                .setDuration(300)
                                .start()
                        }
                        .start()
                } else {
                    // 最初の広告表示（アニメーションなしまたはフェードイン）
                    adView.alpha = 0f
                    adContainer.removeAllViews()
                    adContainer.addView(adView)
                    adView.animate()
                        .alpha(1f)
                        .setDuration(300)
                        .start()
                }

                adContainer.visibility = View.VISIBLE  // ← 成功時に表示
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.e("AdMob", "ネイティブ広告読み込み失敗: ${error.message}")
                    adContainer.visibility = View.GONE // ← 失敗時に非表示
                }

                override fun onAdLoaded() {
                    Log.d("AdMob", "ネイティブ広告読み込み成功")
                    // ※ forNativeAd内でも visibility を表示にしてるのでここでは省略可
                }
            })
            .withNativeAdOptions(NativeAdOptions.Builder().build())
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }


    private fun populateNativeAdView(nativeAd: NativeAd, adView: NativeAdView) {
        // View の取得
        adView.headlineView = adView.findViewById(R.id.ad_headline)
        adView.bodyView = adView.findViewById(R.id.ad_body)
        adView.callToActionView = adView.findViewById(R.id.ad_call_to_action)
        adView.iconView = adView.findViewById(R.id.ad_app_icon)

        // テキスト設定
        (adView.headlineView as TextView).text = nativeAd.headline
        (adView.bodyView as TextView).text = nativeAd.body
        (adView.callToActionView as Button).text = nativeAd.callToAction

        // アイコン画像を設定
        val icon = nativeAd.icon
        if (icon != null) {
            (adView.iconView as ImageView).setImageDrawable(icon.drawable)
            adView.iconView?.visibility = View.VISIBLE
        } else {
            adView.iconView?.visibility = View.GONE
        }

        // バインド
        adView.setNativeAd(nativeAd)
    }

    // reward AdMob
    fun loadRewardAd() {
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(this, rewardAdUnitId, adRequest, object : RewardedAdLoadCallback() { // 広告ユニットID
            override fun onAdLoaded(ad: RewardedAd) {
                rewardedAd = ad
                Log.d("AdMob", "リワード広告読み込み完了")
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                Log.e("AdMob", "リワード広告読み込み失敗: ${error.message}")
            }
        })
    }

    // 広告表示＆報酬検知
    fun showRewardAd(onRewardEarned: () -> Unit) {
        val ad = rewardedAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    Log.d("AdMob", "広告が閉じられたので再読み込みを行う")
                    rewardedAd = null
                    loadRewardAd() // ← 再読み込み
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    Log.e("AdMob", "広告表示失敗: ${adError.message}")
                    rewardedAd = null
                    loadRewardAd()
                }

                override fun onAdShowedFullScreenContent() {
                    Log.d("AdMob", "広告が表示されました")
                }
            }

            // rewardItem を取得してログに出力
            ad.show(this) { rewardItem ->
                val amount = rewardItem.amount
                val type = rewardItem.type
                Log.d("AdMob", "リワード獲得！ amount=$amount, type=$type")

                // 実際の報酬処理
                onRewardEarned()
            }
        } else {
            Toast.makeText(this, "広告がまだ読み込まれていません", Toast.LENGTH_SHORT).show()
            loadRewardAd() // ← ★ 念のためここでも再読み込み
        }
    }

    // jsonダウンロード処理
    fun downloadOrbisJson() {
        val url = URL(orbisJsonUrl)

        Thread {
            try {
                val conn = url.openConnection() as HttpURLConnection
                conn.connect()
                if (conn.responseCode == 200) {
                    val input = conn.inputStream
                    val file = File(filesDir, "orbis_location.json")
                    file.outputStream().use { input.copyTo(it) }

                    runOnUiThread {
                        Toast.makeText(this, "最新オービスデータを取得しました", Toast.LENGTH_SHORT).show()
                        saveJsonUpdateTime()
                        showJsonUpdateTime()

                        // LocationService に再読込要求を送る
                        val intent = Intent("com.example.orbisalert.UPDATE_ORBIS_JSON")
                        sendBroadcast(intent)
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "ダウンロード失敗 (HTTP ${conn.responseCode})", Toast.LENGTH_SHORT).show()
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "エラー：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // 保存用（更新成功時に呼ぶ）
    fun saveJsonUpdateTime() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs.edit().putLong("json_last_update", now).apply()
    }

    // 表示用（アプリ起動時や更新後）
    fun showJsonUpdateTime() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val last = prefs.getLong("json_last_update", 0L)
        val textView = findViewById<TextView>(R.id.json_last_updated)

        if (last == 0L) {
            textView.text = "データ更新日: 未更新"
        } else {
            val format = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.JAPAN)
            textView.text = "データ更新日: ${format.format(java.util.Date(last))}"
        }
    }
}


// オービスを表すクラス（クラスタリング対応）
data class OrbisMarker(
    val lat: Double,
    val lng: Double,
    private val markerTitle: String,
    private val markerSnippet: String,
    val icon: BitmapDescriptor? = null,

    // 詳細表示用データ
    val systemType: Int = 0,
    val cameraPos: Int = 0,
    val speedLimit: Int = 0,
    val laneType: Int = 0,
    val laneSide: Int = 0,
    val laneNum: Int = 0,
    val direction: Int? = null,

    // マーカーを保持
    var marker: Marker? = null,
) : ClusterItem {
    override fun getPosition(): LatLng = LatLng(lat, lng)
    override fun getTitle(): String = markerTitle
    override fun getSnippet(): String = markerSnippet
    override fun getZIndex(): Float = 5.0f
}

// Marker 描画用のレンダラー（クラスタリング用）
class OrbisRenderer(
    private val context: Context,
    private val map: GoogleMap,
    private val clusterManager: ClusterManager<OrbisMarker>
) : DefaultClusterRenderer<OrbisMarker>(context, map, clusterManager) {

    private val DIRECTION_ARROW_VISIBLE_ZOOM = 12f

    companion object {
        // 矢印アイコンの元画像（decodeResource は重いのでキャッシュ）
        private var baseArrowBitmap: Bitmap? = null
    }

    // 矢印マーカーの保持用マップ（本体マーカー → 矢印マーカー）
    private val arrowMarkers = mutableMapOf<Marker, Marker>()

    override fun onBeforeClusterItemRendered(item: OrbisMarker, markerOptions: MarkerOptions) {
        item.icon?.let { markerOptions.icon(it) }
        markerOptions.title(item.getTitle())
        markerOptions.snippet(item.getSnippet())
        markerOptions.anchor(0.5f, 0.5f)
    }

    override fun onClusterItemRendered(item: OrbisMarker, marker: Marker) {
        super.onClusterItemRendered(item, marker)
        item.marker = marker  // ← 保持する
        marker.tag = item  // item にアクセスできるように tag を設定

        // 赤い矢印マーカーを追加
        drawOverlaysIfVisible(item, marker)
    }

    override fun onClusterItemUpdated(item: OrbisMarker, marker: Marker) {
        super.onClusterItemUpdated(item, marker)
        // 矢印マーカーが存在すれば削除
        arrowMarkers[marker]?.remove()
        arrowMarkers.remove(marker)
    }

    // 矢印の描画処理（条件：directionあり、画面内にいること）
    private fun drawOverlaysIfVisible(item: OrbisMarker, orbisMarker: Marker) {
        val direction = item.direction ?: return

        // ズームレベルがしきい値以上かチェック
        val currentZoom = map.cameraPosition.zoom
        if (currentZoom < DIRECTION_ARROW_VISIBLE_ZOOM) return

        val visibleBounds = map.projection.visibleRegion.latLngBounds
        if (!visibleBounds.contains(item.position)) return  // 画面外はスキップ

        // --- 矢印マーカーの描画 ---
        // 初回のみアイコン読み込み
        val bitmap = baseArrowBitmap ?: BitmapFactory.decodeResource(
            context.resources,
            R.drawable.red_arrow_icon
        ).also { baseArrowBitmap = it }

        // スケーリング（元画像ではなく回転用画像に適用）
        val scaledBitmap = bitmap.scale(80, 80)
        val descriptor = BitmapDescriptorFactory.fromBitmap(scaledBitmap)

        // 矢印マーカーを追加
        val arrowMarker = map.addMarker(
            MarkerOptions()
                .position(item.position)
                .icon(descriptor)
                .anchor(0.5f, 1.0f)
                .flat(true)
                .rotation(direction.toFloat())
                .zIndex(1.0f)
        )
        arrowMarker?.tag = item

        // マーカーに対して矢印マーカーを保持（削除用）
        // arrowMarker が null でないことを確認してからマップに追加
        if (arrowMarker != null) { // <-- ここで null チェックを追加
            // マーカーに対して矢印マーカーを保持（削除用）
            arrowMarkers[orbisMarker] = arrowMarker
        } else {
            // マーカーの追加に失敗した場合のログ出力など
            Log.e("OrbisRenderer", "Failed to add arrow marker for item: ${item.getTitle()}")
        }
    }

    // カメラ移動完了時に呼び出して、画面内にあるマーカーの矢印を再描画
    fun refreshVisibleArrows() {
        val visibleBounds = map.projection.visibleRegion.latLngBounds

        // 現在の矢印マーカーをすべて削除
        for ((_, arrowMarker) in arrowMarkers) {
            arrowMarker.remove()
        }
        arrowMarkers.clear()

        // clusterManager からすべてのマーカーを取得し、再描画
        for (marker in clusterManager.markerCollection.markers.filterNotNull()) {
            val item = marker.tag as? OrbisMarker ?: continue
            // 矢印は特定のズームレベル以上で表示されるので、その条件もここでチェック
            if (item.direction != null && visibleBounds.contains(item.position) && map.cameraPosition.zoom >= DIRECTION_ARROW_VISIBLE_ZOOM) {
                drawOverlaysIfVisible(item, marker)
            }
        }
    }
}

// Double 型に対する format 拡張関数
private fun Double.format(digits: Int): String = String.format("%.${digits}f", this)

// Bearing ±10°の2km扇形を計算
fun computeOffset(from: LatLng, distance: Double, heading: Double): LatLng {
    val radius = 6371000.0 // 地球半径 (m)
    val dByR = distance / radius
    val headingRad = Math.toRadians(heading)

    val fromLat = Math.toRadians(from.latitude)
    val fromLng = Math.toRadians(from.longitude)

    val lat = asin(sin(fromLat) * cos(dByR) + cos(fromLat) * sin(dByR) * cos(headingRad))
    val lng = fromLng + atan2(
        sin(headingRad) * sin(dByR) * cos(fromLat),
        cos(dByR) - sin(fromLat) * sin(lat)
    )

    return LatLng(Math.toDegrees(lat), Math.toDegrees(lng))
}

// 扇形を描画
fun drawSector(center: LatLng, bearing: Double, radiusMeters: Double, angleDegrees: Double, map: GoogleMap): Polygon {
    val points = mutableListOf<LatLng>()
    val steps = 36

    val startAngle = bearing - angleDegrees
    val endAngle = bearing + angleDegrees

    points.add(center) // 中心点

    for (i in 0..steps) {
        val angle = startAngle + (endAngle - startAngle) * (i.toDouble() / steps)
        points.add(computeOffset(center, radiusMeters, angle))
    }

    return map.addPolygon(
        PolygonOptions()
            .addAll(points)
            .fillColor(0x33FF0000) // 半透明赤
            .strokeColor(0xFFFF0000.toInt())
            .strokeWidth(2f)
    )
}


