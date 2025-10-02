package com.example.ingress

import com.amap.api.maps.model.BitmapDescriptorFactory
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    // 添加重置按钮引用
   // private lateinit var btnReset: android.widget.Button
    private lateinit var mapView: MapView
    private lateinit var aMap: AMap
    private var score = 0
    private lateinit var poiService: PoiSearchService
    private val webApiKey = "b595bbb139c690f6682a217492b5f282"
    // 定位相关变量
    private lateinit var locationClient: AMapLocationClient
    private var isFirstLocation = true // 标记是否是第一次定位
    // 添加已收集物品的集合
    private val collectedItems = mutableSetOf<String>()
    // 添加当前位置变量
    private var currentLocation: LatLng? = null

    // 虚拟物品数据
    private val virtualItems = listOf(
        VirtualItem("能量点1", LatLng(39.9042, 116.4074)), // 北京
        VirtualItem("能量点2", LatLng(31.2304, 121.4737)), // 上海
        VirtualItem("能量点3", LatLng(23.1291, 113.2644)),  // 广州
        VirtualItem("能量点4", LatLng(30.3128, 120.3944))
    )
    private companion object {
        const val COLLECT_DISTANCE = 100.0 // 100米内才能收集
        const val PREF_FIRST_LAUNCH = "is_first_launch"   // 首次启动标记的键
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 隐私合规设置
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 设置 Toolbar 作为 ActionBar
        setSupportActionBar(findViewById(R.id.toolbar))
        poiService = PoiSearchService.create()
        initMap(savedInstanceState)
        addVirtualItemsToMap()
        initLocation() // 初始化定位
        // 检查是否是首次启动
        checkFirstLaunch()
    }

    // 检查是否是首次启动
    private fun checkFirstLaunch() {
        val prefs = getSharedPreferences("ingress_game", MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean(PREF_FIRST_LAUNCH, true)

        if (isFirstLaunch) {
            // 显示游戏规则
            showGameRulesDialog()

            // 标记为已启动过
            val editor = prefs.edit()
            editor.putBoolean(PREF_FIRST_LAUNCH, false)
            editor.apply()
        }
    }

    private fun initMap(savedInstanceState: Bundle?) {
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        aMap = mapView.map
        aMap.uiSettings.isZoomControlsEnabled = true
        aMap.uiSettings.isMyLocationButtonEnabled = true // 显示定位按钮

        // 初始化定位蓝点样式类
        val myLocationStyle = MyLocationStyle()
        myLocationStyle.interval(2000) // 设置定位间隔
        // 设置定位蓝点的Style
        aMap.myLocationStyle = myLocationStyle
        // 设置为true表示启动显示定位蓝点，false表示隐藏定位蓝点并不进行定位
        aMap.isMyLocationEnabled = true // :cite[3]:cite[8]

        aMap.uiSettings.isZoomControlsEnabled = true
        aMap.uiSettings.isMyLocationButtonEnabled = true // 显示默认的定位按钮
        // 设置标记点击事件
        aMap.setOnMarkerClickListener { marker ->
            handleMarkerClick(marker)
            true // 返回true表示已处理，不显示默认信息窗口
        }

        // 加载保存的游戏数据
        loadGameData()
    }

    private fun initLocation() {
        try {
            // 初始化定位客户端
            locationClient = AMapLocationClient(applicationContext)

            // 设置定位参数
            val locationOption = AMapLocationClientOption()
            locationOption.locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            locationOption.isOnceLocation = false // 持续定位
            locationOption.interval = 3000 // 3秒定位一次
            locationOption.isNeedAddress = true // 需要地址信息
            locationClient.setLocationOption(locationOption)

            // 设置定位监听
            locationClient.setLocationListener(locationListener)

            // 检查权限并开始定位
            checkLocationPermission()

        } catch (e: Exception) {
            Log.e("Location", "定位初始化失败", e)
            Toast.makeText(this, "定位功能初始化失败", Toast.LENGTH_SHORT).show()
        }
    }


    // 处理标记点击
    private fun handleMarkerClick(marker: com.amap.api.maps.model.Marker) {
        val title = marker.title ?: return

        // 检查是否是虚拟物品标记
        val virtualItem = virtualItems.find { it.name == title }
        if (virtualItem != null) {
            handleVirtualItemClick(virtualItem, marker)
            return
        }

        // 检查是否是POI标记（学校等）
        if (title.startsWith("学校:") || title.startsWith("商场:") || title.startsWith("景区:")) {
            handlePoiItemClick(title, marker)
            return
        }

        // 其他标记（如"我的位置"）不处理收集
        Toast.makeText(this, "这是我的位置", Toast.LENGTH_SHORT).show()
    }

    // 处理虚拟物品点击
    private fun handleVirtualItemClick(item: VirtualItem, marker: com.amap.api.maps.model.Marker) {
        val currentLoc = currentLocation

        if (currentLoc == null) {
            Toast.makeText(this, "无法获取当前位置", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查是否已收集
        if (item.isCollected || collectedItems.contains(item.name)) {
            Toast.makeText(this, "${item.name} 已经收集过了", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d("DistanceDebug", "当前位置: ${currentLoc.latitude}, ${currentLoc.longitude}")
        Log.d("DistanceDebug", "目标位置: ${item.location.latitude}, ${item.location.longitude}")

        // 计算距离
        val distance = calculateDistance(currentLoc, item.location)

        // 检查是否在收集范围内
        if (distance <= COLLECT_DISTANCE) {
            // 可以收集
            collectItem(item, marker)
        } else {
            // 距离太远
            Toast.makeText(this,
                "距离太远！需要走到 ${COLLECT_DISTANCE}米内\n当前距离: ${String.format("%.1f", distance)}米",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // 处理POI物品点击
    private fun handlePoiItemClick(title: String, marker: com.amap.api.maps.model.Marker) {
        val currentLoc = currentLocation

        if (currentLoc == null) {
            Toast.makeText(this, "无法获取当前位置", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查是否已收集
        if (collectedItems.contains(title)) {
            Toast.makeText(this, "$title 已经收集过了", Toast.LENGTH_SHORT).show()
            return
        }

        // 计算距离
        val markerPosition = marker.position
        val distance = calculateDistance(currentLoc, markerPosition)

        // 检查是否在收集范围内
        if (distance <= COLLECT_DISTANCE) {
            // 可以收集
            collectPoiItem(title, marker)
        } else {
            // 距离太远
            Toast.makeText(this,
                "距离太远！需要走到 ${COLLECT_DISTANCE}米内\n当前距离: ${String.format("%.1f", distance)}米",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // 收集虚拟物品
    private fun collectItem(item: VirtualItem, marker: com.amap.api.maps.model.Marker) {
        item.isCollected = true
        collectedItems.add(item.name)

        // 更新标记样式
        marker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        marker.snippet = "已收集"

        // 更新分数
        updateScore(100)

        Toast.makeText(this, "✅ 收集成功: ${item.name}", Toast.LENGTH_SHORT).show()

        // 保存游戏数据
        saveGameData()
    }

    // 收集POI物品
    private fun collectPoiItem(title: String, marker: com.amap.api.maps.model.Marker) {
        collectedItems.add(title)

        // 更新标记样式
        marker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        marker.snippet = "已收集 - ${marker.snippet}"

        // 更新分数
        updateScore(15) // POI物品分数更高

        Toast.makeText(this, "✅ 收集成功: $title", Toast.LENGTH_SHORT).show()

        // 保存游戏数据
        saveGameData()
    }


    // 定位监听器
    private val locationListener = AMapLocationListener { location ->
        if (location.errorCode == 0) {
            // 定位成功
            onLocationChanged(location)
        } else {
            // 定位失败
            Log.e("Location", "定位失败: ${location.errorCode}, ${location.errorInfo}")
            Toast.makeText(this, "定位失败: ${location.errorInfo}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onLocationChanged(location: AMapLocation) {
        val currentLatLng = LatLng(location.latitude, location.longitude)
        currentLocation = currentLatLng // 保存当前位置
        Log.d("Location", "当前位置: ${location.latitude}, ${location.longitude}")
        searchNearbyPois(currentLatLng)
        if (isFirstLocation) {
            // 第一次定位，移动到当前位置
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
            isFirstLocation = false
            // 第一次定位时搜索周边POI
            searchNearbyPois(currentLatLng)
            Toast.makeText(this, "定位成功！开始搜索周边地点...", Toast.LENGTH_SHORT).show()

        }

    }
    // 搜索周边POI
    private fun searchNearbyPois(center: LatLng) {
        lifecycleScope.launch {
            try {
                val locationStr = "${center.longitude},${center.latitude}"

                val response = poiService.searchAround(
                    key = webApiKey,
                    location = locationStr,
                    types = "141200", // 学校类型编码
                    radius = 3000
                )

                if (response.status == "1") {
                    // 搜索成功
                    response.pois?.let { pois ->
                        Log.d("POISearch", "成功找到 ${pois.size} 个学校")
                        addPoisToMap(pois, "学校")
                    }
                } else {
                    // 搜索失败
                    Log.e("POISearch", "搜索失败: ${response.info}")
                }

            } catch (e: Exception) {
                Log.e("POISearch", "搜索异常", e)
            }
        }
    }

    private fun calculateDistance(latLng1: LatLng, latLng2: LatLng): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            latLng1.latitude, latLng1.longitude,
            latLng2.latitude, latLng2.longitude,
            results
        )
        return results[0]
    }


    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            // 已有权限，开始定位
            locationClient.startLocation()
            Toast.makeText(this, "开始定位...", Toast.LENGTH_SHORT).show()
        } else {
            // 请求权限
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1001 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 权限 granted，开始定位
                    locationClient.startLocation()
                    Toast.makeText(this, "权限已授予，开始定位", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "需要位置权限才能显示您的位置", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun updateScore(points: Int) {
        score += points
        findViewById<android.widget.TextView>(R.id.tvScore).text = "分数: $score"
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        // 重新开始定位
        if (::locationClient.isInitialized) {
            locationClient.startLocation()
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        // 停止定位以省电
        if (::locationClient.isInitialized) {
            locationClient.stopLocation()
        }
    }
    //将虚拟点添加到地图
    private fun addVirtualItemsToMap() {
        virtualItems.forEach { item ->
            aMap.addMarker(
                MarkerOptions()
                    .position(item.location)
                    .title(item.name)
                    .snippet("点击收集")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
            )
        }
    }

    // 将POI添加到地图
    private fun addPoisToMap(pois: List<Poi>, category: String) {
        pois.forEach { poi ->
            val locationParts = poi.location.split(",")
            if (locationParts.size == 2) {
                val lat = locationParts[1].toDouble()
                val lng = locationParts[0].toDouble()
                val latLng = LatLng(lat, lng)

                // 创建POI的完整标题（用于标识）
                val poiTitle = "$category: ${poi.name}"

                // 检查是否已收集
                val isCollected = collectedItems.contains(poiTitle)

                // 根据收集状态设置不同颜色
                val iconColor = if (isCollected) {
                    BitmapDescriptorFactory.HUE_GREEN // 已收集：绿色
                } else {
                    when (category) {
                        "学校" -> BitmapDescriptorFactory.HUE_RED
                        else -> BitmapDescriptorFactory.HUE_MAGENTA
                    }
                }

                // 设置不同的提示文本
                val snippet = if (isCollected) {
                    "已收集 - 地址: ${poi.address}"
                } else {
                    "点击收集 - 地址: ${poi.address}"
                }

                aMap.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .title(poiTitle)
                        .snippet(snippet)
                        .icon(BitmapDescriptorFactory.defaultMarker(iconColor))
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
        // 释放定位资源
        if (::locationClient.isInitialized) {
            locationClient.onDestroy()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    // 保存游戏数据
    private fun saveGameData() {
        val prefs = getSharedPreferences("ingress_game", MODE_PRIVATE)
        val editor = prefs.edit()

        editor.putInt("score", score)
        editor.putStringSet("collected_items", collectedItems)
        editor.apply()
    }

    // 加载游戏数据
    private fun loadGameData() {
        val prefs = getSharedPreferences("ingress_game", MODE_PRIVATE)

        // 加载分数
        score = prefs.getInt("score", 0)
        updateScoreDisplay()

        // 加载已收集物品
        val savedItems = prefs.getStringSet("collected_items", setOf()) ?: setOf()
        collectedItems.clear()
        collectedItems.addAll(savedItems)

        // 更新虚拟物品的收集状态
        virtualItems.forEach { item ->
            item.isCollected = collectedItems.contains(item.name)
        }
    }

    // 更新分数显示
    private fun updateScoreDisplay() {
        findViewById<android.widget.TextView>(R.id.tvScore).text = "分数: $score"
    }

    // 创建选项菜单
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    // 处理菜单项点击
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_game_rules -> {
                showGameRulesDialog()
                true
            }
            R.id.menu_reset_game -> {
                showResetConfirmationDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // 显示游戏规则对话框
    private fun showGameRulesDialog() {
        val rules = """
        🎮 iNgress 游戏规则 🎮
        
        🎯 游戏目标：
        • 收集地图上的能量点和兴趣点
        • 获得尽可能高的分数
        
        📍 收集规则：
        • 走到目标点100米范围内才能收集
        • 已收集的点不能重复收集
        
        ⭐ 分数系统：
        • 虚拟能量点：100分
        • 学校POI点：15分
        
        🗺️ 地图功能：
        • 蓝色圆点：你的当前位置
        • 黄色标记：未收集的虚拟能量点
        • 红色标记：未收集的学校
        • 绿色标记：已收集的点
        
        💡 提示：
        • 点击标记查看详细信息
        • 走到标记附近才能收集
        • 重置游戏会清除所有进度
        
        祝您游戏愉快！🎊
        """.trimIndent()

        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("游戏规则")
            .setMessage(rules)
            .setPositiveButton("明白了") { dialog, which ->
                // 关闭对话框
            }
            .show()
    }

    // 显示重置确认对话框
    private fun showResetConfirmationDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("重置游戏")
            .setMessage("确定要重置游戏吗？这将清除所有分数和收集记录。")
            .setPositiveButton("确定") { dialog, which ->
                resetGameState()
            }
            .setNegativeButton("取消") { dialog, which ->
                // 用户取消，什么都不做
            }
            .show()
    }

    // 重置游戏状态
    private fun resetGameState() {
        // 1. 重置分数
        score = 0
        updateScoreDisplay()

        // 2. 清空已收集物品
        collectedItems.clear()

        // 3. 重置虚拟物品的收集状态
        virtualItems.forEach { item ->
            item.isCollected = false
        }

        // 4. 清除保存的游戏数据
        clearGameData()

        // 5. 重新加载地图标记（更新颜色）
        refreshMapMarkers()

        Toast.makeText(this, "游戏已重置", Toast.LENGTH_SHORT).show()
    }

    // 清除保存的游戏数据
    private fun clearGameData() {
        val prefs = getSharedPreferences("ingress_game", MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean(PREF_FIRST_LAUNCH, false)
        val editor = prefs.edit()
        editor.clear() // 清除所有数据
        // 恢复首次启动标志
        if (!isFirstLaunch) {
            editor.putBoolean(PREF_FIRST_LAUNCH, false)
        }
        editor.apply()
    }

    // 刷新地图标记
    private fun refreshMapMarkers() {
        // 清除所有标记
        aMap.clear()

        // 重新添加虚拟物品（会显示正确的颜色）
        addVirtualItemsToMap()

        // 重新搜索POI（会显示正确的颜色）
        currentLocation?.let {
            searchNearbyPois(it)
        }
    }

}

data class VirtualItem(val name: String, val location: LatLng, var isCollected: Boolean = false)