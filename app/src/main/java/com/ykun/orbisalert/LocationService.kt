package com.ykun.orbisalert

import android.Manifest
import android.app.*
import android.location.Location
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.AudioFocusRequest
import android.os.*
import android.content.pm.PackageManager
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*
import java.io.File
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * オービス警告アプリのバックグラウンド位置監視サービス
 * Foreground Serviceとして動作し、音声・振動・地図連携を行う
 */
class LocationService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var lastLatLng: Pair<Double, Double>? = null
    private var nearestOrbisKey: String? = null
    private var alarmPlayer: MediaPlayer? = null

    private lateinit var orbisArray: JSONArray

    private val notifyThresholds = listOf(2000, 1000, 500)
    private val clearDistance = 50
    private val notifiedDistanceMap = mutableSetOf<String>()
    private var isAlarmActive = false
    // オービス接近追跡用の状態変数
    private var lastDistanceToOrbis = Double.MAX_VALUE
    private var wasApproachingOrbis = false
    private var previousOrbisObj: JSONObject? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 方角を保持する変数
    private var currentBearing: Double = 0.0

    // アラームのタイムアウト
    private var alarmTimeoutJob: Job? = null


    // Audio Focus
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    // Audioフォーカスの変化を監視するリスナー
    private val afChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d("Audio", "Audio focus regained")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d("Audio", "Ducking")
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d("Audio", "Audio focus lost")
            }
        }
    }

    // バーストGPS
    private var isVehicleMode = false // GPS更新間隔を切り替えるための状態変数（trueなら車両モードで1秒更新）
    private var currentLocationRequest: LocationRequest? = null // 現在の位置リクエストを保持する変数（解除や再登録時に使用）
    private val VEHICLE_SPEED_THRESHOLD = 15.0 // km/h 以上で車両判定
    private val BURST_INTERVAL_MILLIS = 60_000L
    private lateinit var burstCheckJob: Job
    private var isAppInForeground: Boolean = true

    override fun onCreate() {
        super.onCreate()
        Log.d("LocationService", "サービス開始")

        // フラグを初期化
        wasApproachingOrbis = false
        isAlarmActive = false
        lastDistanceToOrbis = Double.MAX_VALUE
        nearestOrbisKey = null

        // 権限チェック（Android 12以降では追加権限あり）
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        ) {
            Log.e("LocationService", "必要な位置情報権限がありません。サービス終了")
            stopSelf()
            return
        }

        // Foreground通知を開始する
        startForegroundService()

        // AudioManager初期化
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // オービス情報を読み込み
        loadOrbisJson()

        // 位置情報取得の初期化
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                Log.d("LocationService", "位置: ${location.latitude}, ${location.longitude}")

                // バックグラウンド中のみバーストGPSを実行
                if (!isAppInForeground) {
                    launchSpeedCheckBurst()
                }
                handleLocation(location)
            }
        }

        // 最初の位置更新制御（GPSは停止状態からスタート）
        updateLocationRequest()

        // バックグラウンドで車両判定によりGPS再開
        if (!isAppInForeground) {
            launchSpeedCheckBurst()
        }

        // バーストGPSを定期実行（60秒おき）
        burstCheckJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(BURST_INTERVAL_MILLIS)
                if (!isAppInForeground) {
                    launchSpeedCheckBurst()
                }
            }
        }

        // フォアグラウンド復帰通知のレシーバ
        ContextCompat.registerReceiver(
            this,
            foregroundReenteredReceiver,
            IntentFilter("com.example.orbisalert.FOREGROUND_REENTERED"),
            ContextCompat.RECEIVER_EXPORTED
        )

        // バックグラウンド移行通知のレシーバ
        ContextCompat.registerReceiver(
            this,
            backgroundEnteredReceiver,
            IntentFilter("com.example.orbisalert.BACKGROUND_ENTERED"),
            ContextCompat.RECEIVER_EXPORTED
        )

        // jsonファイルのダウンロード完了通知のレシーバ
        ContextCompat.registerReceiver(
            this,
            jsonUpdateReceiver,
            IntentFilter("com.example.orbisalert.UPDATE_ORBIS_JSON"),
            ContextCompat.RECEIVER_EXPORTED  // ← これを明示的に指定
        )
    }

    /**
     * 現在地が更新されたときに呼ばれる処理
     */
    private fun handleLocation(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        val speed = location.speed * 3.6
        val accuracy = location.accuracy

        // 進行方位を更新（速度10km/h以上でのみ更新）
        val bearing = if (speed >= 10.0){ // 10km/hのときだけ方角を更新
            lastLatLng?.let {
                calculateBearing(it.first, it.second, lat, lng)
            } ?: 0.0
        } else {
            currentBearing // 保持しておく方向値を使う
        }
        if (speed >= 10.0) {
            currentBearing = bearing
        }

        // 前回位置の記録
        lastLatLng = Pair(lat, lng)

        // Coroutineでバックグラウンド処理に移行
        scope.launch(Dispatchers.Default) {
            val candidateList = mutableListOf<JSONObject>()

            // 半径5km以内の全オービスを候補に
            for (i in 0 until orbisArray.length()) {
                val obj = orbisArray.getJSONObject(i)
                val olat = obj.getDouble("decLatitude")
                val olng = obj.getDouble("decLongitude")
                val dist = distanceInMeters(lat, lng, olat, olng)
                if (dist < 5000) {
                    candidateList.add(obj)
                }
            }

            // 固定式と移動式を別々に追跡
            var closestFixedObj: JSONObject? = null
            var closestFixedDistance = Double.MAX_VALUE

            var closestMobileObj: JSONObject? = null
            var closestMobileDistance = Double.MAX_VALUE

            for (obj in candidateList) {
                val olat = obj.getDouble("decLatitude")
                val olng = obj.getDouble("decLongitude")
                val dist = distanceInMeters(lat, lng, olat, olng)

                val systemType = obj.optInt("intSystemType", 0)
                val isMobileOrbis = systemType in listOf(5, 6, 7)

                if (isMobileOrbis) {
                    // 移動式オービス：方向条件なし、2km以内なら対象
                    if (dist < 2000.0 && dist < closestMobileDistance) {
                        closestMobileObj = obj
                        closestMobileDistance = dist
                    }
                } else {
                    // 固定式オービス：方向条件を満たすかチェック
                    val oangle = obj.optInt("intDirection", 0)
                    val directionDiff = abs(angleDifference(bearing, oangle.toDouble()))

                    val angleToOrbis = calculateBearing(lat, lng, olat, olng)
                    val angleDiffToOrbis = angleDifference(bearing, angleToOrbis)

                    Log.d("DEBUG_COND", "obj:${obj.getInt("intOrbisKey")}")
                    Log.d("DEBUG_COND", "進行方向とオービスの方向の差：$directionDiff")
                    Log.d("DEBUG_COND", "進行方向から見たオービスの位置：$angleDiffToOrbis")

                    val meetsDirectionCriteria = directionDiff in 150.0..210.0 &&
                            angleDiffToOrbis <= 30.0

                    Log.d("DEBUG_COND", "方向条件通過：$meetsDirectionCriteria")


                    if (
                        speed >= 30.0 &&       // 最低速度
                        accuracy < 25.0 &&     // GPS精度良好
                        meetsDirectionCriteria &&
                        dist < closestFixedDistance
                    ) {
                        closestFixedObj = obj
                        closestFixedDistance = dist
                    }
                }
            }

            // 優先順位：固定式オービス > 移動式オービス
            val closestObj = closestFixedObj ?: closestMobileObj

            withContext(Dispatchers.Main) {
                processOrbisProximity(closestObj, lat, lng, speed, accuracy, bearing)
            }
        }
    }

    private fun processOrbisProximity(closestObj: JSONObject?, lat: Double, lng: Double, speed: Double, accuracy: Float, bearing: Double) {
        var minDistance = -1.0  // ← 距離の初期化（見つからない場合は -1.0）

        // オービスが見つからなくても以前の対象があれば使用する
        val obj = closestObj ?: previousOrbisObj

        if (obj != null) {
            Log.d("DEBUG_COND", "closestobj:${obj.getInt("intOrbisKey")}")
            val orbisLat = obj.getDouble("decLatitude")
            val orbisLng = obj.getDouble("decLongitude")
            val keyBase = "${orbisLat},${orbisLng}"
            val systemType = obj.optInt("intSystemType", 0)
            val isMobileOrbis = systemType in listOf(5, 6, 7)

            // オービスまでの距離を計算
            // 通過直後はminDistanceを計算しない
            if (closestObj != null) {
                minDistance = distanceInMeters(lat, lng, orbisLat, orbisLng)
            } else {
                minDistance = -1.0
            }

            // 接近 or 離反の検出
            val isApproaching = minDistance < lastDistanceToOrbis && minDistance > 0
            if (isApproaching) {
                lastDistanceToOrbis = minDistance
                wasApproachingOrbis = true
            }

            // 次回の比較のために保存
            previousOrbisObj = obj

            // 通過判定：一度接近 → 離れ始め → 50m超え → 通過通知 （移動式はスキップ）
            val passedKey = "${keyBase}_passed"
            val alreadyPassed = notifiedDistanceMap.contains(passedKey)
            Log.d("DEBUG_COND", """
                        isMobileOrbis: $isMobileOrbis
                        isAlarmActive: $isAlarmActive
                        wasApproachingOrbis: $wasApproachingOrbis
                        isApproaching: $isApproaching
                        minDistance: $minDistance
                        clearDistance: $clearDistance
                        alreadyPassed: $alreadyPassed
                    """.trimIndent())

            if (!isMobileOrbis && isAlarmActive && wasApproachingOrbis && !isApproaching &&
                minDistance > clearDistance && !alreadyPassed) {
                Log.d("OrbisAlert", "通過判定（${minDistance}m）")
                notifiedDistanceMap.add(passedKey)
                stopAlert()
                stopVibration()
                isAlarmActive = false
                wasApproachingOrbis = false
                previousOrbisObj = null
                playAlert("alert_passed.mp3")
                return
            }

            // 距離別通知（最も近い閾値のみ再生）
            for (threshold in notifyThresholds) {
                val notifyKey = "${keyBase}_${threshold}"
                if (minDistance < threshold && !notifiedDistanceMap.contains(notifyKey)) {
                    // 移動式は 500m 通知スキップ
                    if (isMobileOrbis && threshold == 500) continue

                    notifiedDistanceMap.add(notifyKey)
                    // intRoadType に応じて alert_ippan.mp3 または alert_kosoku.mp3 を先に再生
                    val roadType = obj.optInt("intRoadType", 1) // 1=一般道, 2=高速
                    val categoryAlert = if (roadType == 1) "alert_ippan.mp3" else "alert_kosoku.mp3"
                    val suffix = if (isMobileOrbis) "_move" else ""
                    if (threshold == notifyThresholds.min()) {
                        playAlert(categoryAlert) {
                            playAlert("alert_${threshold}m$suffix.mp3") {
                                // 再生完了後にアラームを開始
                                if (!isAlarmActive && !alreadyPassed) {
                                    Log.d("OrbisAlert", "アラーム開始 (${threshold}m完了後)")
                                    playAlert("alert_alarm.mp3")
                                    startVibration()
                                    isAlarmActive = true

                                    // タイマーで一定時間後に自動停止
                                    alarmTimeoutJob?.cancel()  // 既存があればキャンセル
                                    alarmTimeoutJob = CoroutineScope(Dispatchers.Default).launch {
                                        delay(30_000L)  // 30秒（ミリ秒単位）
                                        withContext(Dispatchers.Main) {
                                            Log.d("OrbisAlert", "アラーム自動停止（30秒経過）")
                                            stopAlert()
                                            stopVibration()
                                            isAlarmActive = false
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        playAlert(categoryAlert) {
                            playAlert("alert_${threshold}m$suffix.mp3")
                        }
                    }
                    break
                }
            }

            // 監視対象変更ログ
            if (keyBase != nearestOrbisKey) {
                nearestOrbisKey = keyBase
                Log.d("OrbisAlert", "監視対象変更 → ${obj.optString("strAddress")}")
            }

        } else {
            // 一致するオービスがなくなったときの処理
            if (nearestOrbisKey != null) {
                Log.d("OrbisAlert", "オービス対象から離脱")
            }
            isAlarmActive = false
            wasApproachingOrbis = false
            lastDistanceToOrbis = Double.MAX_VALUE
            nearestOrbisKey = null

            // 最後の対象も忘れる
            previousOrbisObj = null

            // 現在地から十分に離れたオービス通知のみを削除する
            notifiedDistanceMap.removeIf { key ->
                val parts = key.split("_")
                if (parts.size < 2) return@removeIf false
                val latLng = parts[0].split(",")
                if (latLng.size != 2) return@removeIf false

                val oLat = latLng[0].toDoubleOrNull() ?: return@removeIf false
                val oLng = latLng[1].toDoubleOrNull() ?: return@removeIf false
                val dist = distanceInMeters(lat, lng, oLat, oLng)
                dist > clearDistance * 2
            }
        }

        // MainActivity へ位置＋方位をブロードキャスト送信
        val timeStr = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

        val intent = Intent("com.example.orbisalert.LOCATION_UPDATE").apply {
            putExtra("lat", lat)
            putExtra("lng", lng)
            putExtra("speed", speed)
            putExtra("accuracy", accuracy)
            putExtra("time", timeStr)
            putExtra("bearing", bearing)
            putExtra("distanceToOrbis", minDistance)
        }
        sendBroadcast(intent)

        Log.d("LocationService", "Broadcast Send: lat=$lat, lng=$lng, speed=$speed, accuracy=$accuracy, time=$timeStr, bearing=$bearing, distance=$minDistance")
    }

    // 警告音の再生
    private fun playAlert(fileName: String, onComplete: (() -> Unit)? = null) {
        stopAlert()
        try {
            requestAudioFocus()  // オーディオフォーカス取得

            val afd = assets.openFd(fileName)
            val player = MediaPlayer()
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            player.isLooping = fileName == "alert_alarm.mp3"
            player.prepare()
            player.start()
            if (onComplete != null) {
                player.setOnCompletionListener {
                    abandonAudioFocus() // 再生後にフォーカス返す
                    onComplete()
                }
            } else {
                player.setOnCompletionListener {
                    abandonAudioFocus() // 通常再生後も返す
                }
            }
            alarmPlayer = player
        } catch (e: Exception) {
            Log.e("Sound", "再生失敗: $fileName", e)
        }
    }

    // 警告音の停止（安全に解放）
    private fun stopAlert() {
        alarmPlayer?.let { player ->
            try {
                player.setOnCompletionListener(null)
                player.setOnPreparedListener(null)
                player.setOnErrorListener(null)

                if (player.isPlaying) {
                    player.stop()
                }

                player.release()
            } catch (e: Exception) {
                Log.e("Sound", "MediaPlayer解放中にエラー", e)
            }
        }
        alarmPlayer = null

        // タイマーもキャンセル
        alarmTimeoutJob?.cancel()
        alarmTimeoutJob = null

        // フォーカスを明示的に返す
        abandonAudioFocus()
    }


    // バイブレーション開始
    private fun startVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        vibrator?.takeIf { it.hasVibrator() }?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0)
        )
    }

    // バイブレーション停止
    private fun stopVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator?.cancel()
    }

    // 方位角の計算
    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    // 2点間の距離を計算
    private fun distanceInMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val loc1 = Location("").apply { latitude = lat1; longitude = lon1 }
        val loc2 = Location("").apply { latitude = lat2; longitude = lon2 }
        return loc1.distanceTo(loc2).toDouble()
    }

    // 角度差（0〜180度）を計算
    private fun angleDifference(a: Double, b: Double): Double {
        return min(abs(a - b), 360 - abs(a - b))
    }

    // assetsからオービスデータを読み込む
    private fun loadOrbisJson() {
        val file = File(filesDir, "orbis_location.json")
        val jsonStr = if (file.exists()) {
            Log.d("LocationService", "保存済みJSONを読み込みます")
            file.readText()
        } else {
            Log.d("LocationService", "assetsの初期JSONを使用します")
            assets.open("orbis_locations.json").bufferedReader().use { it.readText() }
        }
        orbisArray = JSONArray(jsonStr)
    }

    // Foreground通知の作成と開始
    private fun startForegroundService() {
        val channelId = "orbis_alert_channel"
        val channel = NotificationChannel(channelId, "オービス通知", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("オービス監視中")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        startForeground(1, notification)
    }

    // サービスはバインド不可（startService専用）
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopAlert()
        stopVibration()
        unregisterReceiver(jsonUpdateReceiver)
        unregisterReceiver(foregroundReenteredReceiver)
        unregisterReceiver(backgroundEnteredReceiver)
        scope.cancel()  // 忘れるとメモリリークの可能性
    }

    // Audio Focusをリクエスト/開放する関数
    private fun requestAudioFocus(): Boolean {
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setOnAudioFocusChangeListener(afChangeListener)
            .build()

        audioFocusRequest = focusRequest

        val result = audioManager.requestAudioFocus(focusRequest)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
    }


    // jsonダウンロード完了の通知を受信
    private val jsonUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("LocationService", "📥 JSON再読み込み要求を受信")
            loadOrbisJson()
        }
    }

    // 位置情報の更新設定（1秒更新または停止）
    private fun updateLocationRequest() {
        // 車両モード または フォアグラウンド時は1秒更新、それ以外はGPS停止
        val interval = if (isVehicleMode || isAppInForeground) 1000L else null

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("LocationService", "⚠️ 位置情報パーミッションがありません。GPS更新スキップ")
            return
        }

        // 変更がなければスキップ（再設定しない）
        if (interval != null && currentLocationRequest?.intervalMillis == interval) {
            return  // 同じ設定なので再登録不要
        }
        if (interval == null && currentLocationRequest == null) {
            return  // すでに停止中なので再設定不要
        }

        // 一旦すべての位置更新を解除（再設定のため）
        fusedLocationClient.removeLocationUpdates(locationCallback)

        if (interval != null) {
            // 1秒間隔の高精度位置リクエストを作成して再登録
            val newRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval).build()
            fusedLocationClient.requestLocationUpdates(newRequest, locationCallback, Looper.getMainLooper())
            currentLocationRequest = newRequest
            Log.d("LocationService", "📡 GPS更新再開: ${interval}ms")
        } else {
            // 停止処理（リクエストをnullにしてログ出力）
            currentLocationRequest = null
            Log.d("LocationService", "🛑 GPS更新停止（非車両時にバックグラウンド）")
        }
    }

    // バーストGPSで車両判定を行う処理（1秒ごとに最大5回速度測定）
    private fun launchSpeedCheckBurst(): Job = scope.launch {
        // 実行前にパーミッションチェック
        if (ActivityCompat.checkSelfPermission(this@LocationService, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("LocationService", "⚠️ パーミッションなし → バーストスキップ")
            return@launch
        }

        val speeds = mutableListOf<Double>() // 測定した速度のリスト
        // 一時的なLocationCallbackを定義（速度だけ記録）
        val tempCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val speedKmh = result.lastLocation?.speed?.times(3.6) ?: 0.0
                speeds.add(speedKmh)
                Log.d("SpeedBurst", "🔍 Burst速度 = %.1f km/h".format(speedKmh))
            }
        }

        // バースト用に1秒間隔×最大5回のリクエストを作成
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMaxUpdates(5)
            .build()

        // 一時的な位置更新リクエストを登録
        fusedLocationClient.requestLocationUpdates(req, tempCallback, Looper.getMainLooper())
        delay(5500L) // 5回 + 余裕分の待機

        // 念のためパーミッション再確認して解除
        if (ActivityCompat.checkSelfPermission(this@LocationService, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.removeLocationUpdates(tempCallback)
        }

        // 平均速度を算出
        val avg = if (speeds.isNotEmpty()) speeds.average() else 0.0
        Log.d("SpeedBurst", "📊 平均速度 = %.1f km/h".format(avg))

        // 閾値以上なら車両モードと判定、それ以外は停止
        val previousMode = isVehicleMode // ← 変更ログ用に現在の状態を記録
        if (avg >= VEHICLE_SPEED_THRESHOLD) {
            Log.d("SpeedBurst", "✅ 車両と判定 → GPS常時ON")
            isVehicleMode = true
        } else {
            Log.d("SpeedBurst", "⏹ 低速のためGPS停止（次は1分後）")
            isVehicleMode = false
        }

        // モード変更ログを出力
        if (previousMode != isVehicleMode) {
            Log.i("SpeedBurst", "🚦 GPS更新モード変更: $previousMode → $isVehicleMode")
        }

        updateLocationRequest()
    }

    // フォアグラウンド復帰通知の受信
    private val foregroundReenteredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("LocationService", "📲 フォアグラウンド復帰検知 → GPS更新間隔を再設定")
            isAppInForeground = true
            updateLocationRequest()
        }
    }

    //バックグラウンド移行通知の受信
    private val backgroundEnteredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("LocationService", "🕶 MainActivity がバックグラウンドに移行")
            isAppInForeground = false
            updateLocationRequest() // IN_VEHICLE以外のときはバックグラウンドで位置の更新ストップ
        }
    }

}