package com.haojay.leapmotor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haojay.leapmotor.ui.AppTheme
import com.haojay.leapmotor.ui.CarScreen
import com.haojay.leapmotor.ui.ControlScreen
import com.haojay.leapmotor.ui.MainViewModel
import com.haojay.leapmotor.ui.TrendScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.Dp

private data class Tab(
    val label: String,
    val icon: ImageVector,
    val iconOff: ImageVector,
)

private val TABS = listOf(
    Tab("车辆", Icons.Filled.DirectionsCar, Icons.Outlined.DirectionsCar),
    Tab("趋势", Icons.Filled.Insights, Icons.Outlined.Insights),
    Tab("控制", Icons.Filled.Tune, Icons.Outlined.Tune),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 导航条/状态栏都用透明样式，并关掉系统自动加的对比遮罩，
        // 否则浅色主题下底部会多出一条半透明白带（"小白条不沉浸"）。
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        // 通道必须早于任何通知创建：Service 起来时若通道不存在，
        // 通知虽然能发出去，但用户去设置里看不到分类，也没法单独关掉某一类提醒。
        com.haojay.leapmotor.data.AlertCenter.ensureChannels(this)

        // Android 13+ 通知需要运行时授权。拿不到只是没有充电通知，
        // 其它功能不受影响，所以只请求一次、不阻塞启动。
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        setContent {
            AppTheme {
                // 铺满整屏（含系统栏），否则系统栏区域露出 Window 默认白底，
                // 在浅色主题下就是那条"不沉浸的小白条"。
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot(vm: MainViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    var showUpdate by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            snackbar.showSnackbar(m)
            vm.message = null
        }
    }

    // 未登录：直接进登录页，不显示主界面
    if (!vm.loggedIn) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                com.haojay.leapmotor.ui.LoginScreen(
                    busy = vm.loginBusy,
                    codeSent = vm.codeSent,
                    error = vm.loginError,
                    onSendCode = vm::sendCode,
                    onLogin = vm::login,
                )
            }
        }
        return
    }

    // 前后台感知：只在 App 可见时采样（B 方案的限制，后台不采）
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, vm.loggedIn) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    vm.startPolling()
                    // 回到前台时再看一眼是否到达检查间隔（长期挂后台不会自己计时）
                    vm.maybeCheckUpdate()
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> vm.stopPolling()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        // 注册时若已处于 STARTED，不会补发 ON_START，需立即启动一次
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.STARTED
            )
        ) {
            vm.startPolling()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 胶囊离底距离：手势导航下 26dp 观感最好；三键导航系统栏更高，
    // 取较大值，避免胶囊压住系统按键。
    val navInset = androidx.compose.foundation.layout.WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    val capsuleBottom = maxOf(26.dp, navInset)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // M3 窗口尺寸类：Compact < 600dp、Medium 600~839dp、Expanded >= 840dp。
        // 官方对 Medium/Expanded 的建议是把底部导航换成左侧 NavigationRail ——
        // 手机上底部栏顺手，宽屏上底部栏离手指太远、且浪费整条横向空间。
        val wide = maxWidth >= 600.dp
        // 宽屏没有底部胶囊，底部留白只需给系统栏让位
        val listBottom = if (wide) 32.dp else capsuleBottom + 74.dp
        // 内容限宽：太宽的一列文字读起来很累（一行超过 80 字符就难跟行）
        val contentMax = when {
            maxWidth >= 1200.dp -> 880.dp
            wide -> 640.dp
            else -> Dp.Unspecified
        }

        Scaffold(
            snackbarHost = {
                // 悬浮胶囊在底部，Snackbar 默认贴底会被它盖住。
                // 抬高到"胶囊离底距离 + 胶囊高度(60) + 间隙(16)"，宽屏没有胶囊只留一点余量。
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.padding(
                        start = 16.dp, end = 16.dp,
                        bottom = if (wide) 12.dp else capsuleBottom + 34.dp
                    )
                )
            }
        ) { pad ->
            // ⚠️ 关键：innerPadding 必须交给列表的 contentPadding，不能套在容器上。
            // 套在容器上（Box(Modifier.padding(pad))）会把内容整体压到系统栏内侧，
            // 系统栏区域就露出一条平色带 —— 也就是"底部小白条不沉浸"。
            // 交给 contentPadding 后，列表项能滚到屏幕最边缘、从系统栏下方穿过去，
            // 这才是官方说的 edge-bleeding scroll。
            val listTop = pad.calculateTopPadding()

            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    WideNavRail(
                        current = tab,
                        onSelect = { tab = it },
                        modifier = Modifier.padding(top = listTop)
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .consumeWindowInsets(pad),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = if (contentMax == Dp.Unspecified)
                            Modifier.fillMaxSize()
                        else
                            Modifier.widthIn(max = contentMax).fillMaxHeight()
                    ) {
                        // 三屏保活：访问过的页面常驻组合，切页只是换摆放位置，
                        // 不再整屏重建（实测切页 99th 从 48~53ms 降到单帧）。
                        val visited = remember { androidx.compose.runtime.mutableStateListOf(0) }
                        LaunchedEffect(tab) { if (!visited.contains(tab)) visited += tab }
                        KeepAlive(activeIndex = visited.indexOf(tab).coerceAtLeast(0)) {
                            visited.forEach { index ->
                                androidx.compose.runtime.key(index) {
                        when (index) {
                            0 -> CarScreen(
                                snap = vm.snapshot,
                                loading = vm.loadingSnapshot,
                                error = vm.error,
                                polling = vm.polling,
                                updatedAt = vm.lastUpdateAt,
                                pendingLock = vm.pendingLock,
                                pendingTrunk = vm.pendingTrunk,
                                onRefresh = vm::refreshSnapshot,
                                onLogout = vm::logout,
                                topInset = listTop,
                                bottomInset = listBottom,
                                mapUseWgs = vm.mapUseWgs,
                                onToggleDatum = vm::toggleMapDatum,
                                parkingPhoto = vm.parkingPhoto,
                                parkingPhotoAt = vm.parkingPhotoAt,
                                parkingPhotoLoading = vm.parkingPhotoLoading,
                                parkingPhotoError = vm.parkingPhotoError,
                                onLoadParkingPhoto = vm::loadParkingPhoto
                            )
                            1 -> TrendScreen(
                                trend = vm.trend,
                                loading = vm.loadingTrend,
                                onLoad = { vm.refreshTrend() },
                                onOpenReport = { showReport = true },
                                topInset = listTop,
                                bottomInset = listBottom
                            )
                            else -> ControlScreen(
                                snap = vm.snapshot,
                                busy = vm.busyCommand,
                                hasPin = vm.hasPin,
                                pendingAcOn = vm.pendingAcOn,
                                pendingLock = vm.pendingLock,
                                chargeSoc = vm.chargeSoc,
                                cmdLog = vm.cmdLog,
                                cmdLogPath = vm.cmdLogPath,
                                chargeBusy = vm.chargeBusy,
                                onSetChargeSoc = vm::applyChargeSoc,
                                onLoadChargePlan = vm::loadChargePlan,
                                onSavePin = vm::savePin,
                                onSend = vm::sendCommand,
                                topInset = listTop,
                                bottomInset = listBottom,
                                versionName = vm.currentVersion,
                                updateHint = if (vm.updateAvailable) {
                                    "有新版本 " + (vm.latestRelease?.version ?: "") + "，点我查看"
                                } else "",
                                onOpenUpdate = { showUpdate = true },
                                monitorOn = vm.monitorOn,
                                onMonitorChange = vm::applyMonitor
                            )
                        }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!wide) {
            FloatingTabBar(
                current = tab,
                onSelect = { tab = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = capsuleBottom)
            )
        }
    }

    if (showReport) {
        com.haojay.leapmotor.ui.ReportDialog { showReport = false }
    }

    if (showUpdate) {
        com.haojay.leapmotor.ui.UpdateDialog(vm) { showUpdate = false }
    }
}

/**
 * 悬浮胶囊导航（M3 Expressive）。
 *
 * 与标准 NavigationBar 的差别：
 * - 不贴边，浮在内容之上，靠阴影分层
 * - 选中项展开显示文字，未选中只留图标 —— 省空间且焦点明确
 * - 整条是胶囊形（999dp），选中项本身也是小胶囊
 */
@Composable
private fun FloatingTabBar(
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 12.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TABS.forEachIndexed { i, t ->
                val sel = current == i
                Row(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                        .background(
                            if (sel) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        // 自定义导航项要用 selectable + Role.Tab，
                        // 否则 TalkBack 只念标签文字、不播报"已选中/标签页"状态。
                        .selectable(
                            selected = sel,
                            role = androidx.compose.ui.semantics.Role.Tab,
                            onClick = { onSelect(i) }
                        )
                        .padding(
                            horizontal = if (sel) 18.dp else 15.dp,
                            vertical = 10.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (sel) t.icon else t.iconOff,
                        contentDescription = t.label,
                        tint = if (sel) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    if (sel) {
                        Spacer(Modifier.width(7.dp))
                        Text(
                            t.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

/**
 * 宽屏（Medium / Expanded）用的左侧导航栏。
 *
 * 官方对窗口尺寸类的建议：Compact 用底部 NavigationBar，
 * Medium 以上改用 NavigationRail —— 手机单手握持时底部栏在拇指范围内，
 * 但平板/折叠屏展开后底部栏离手太远，横向空间反而被浪费。
 *
 * 这里直接复用官方 [androidx.compose.material3.NavigationRail]，
 * 不自绘，保证选中指示器、涟漪、无障碍语义都是规范行为。
 */
@Composable
private fun WideNavRail(
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        TABS.forEachIndexed { i, t ->
            androidx.compose.material3.NavigationRailItem(
                selected = current == i,
                onClick = { onSelect(i) },
                icon = {
                    Icon(
                        if (current == i) t.icon else t.iconOff,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                },
                label = { Text(t.label, fontSize = 12.sp) }
            )
        }
    }
}

/**
 * 保活容器：所有子项都参与组合与测量，但只有 [activeIndex] 那一项被摆到可见位置，
 * 其余放到屏幕外。这样切页不触发重建，代价是每次数据变化时多测量两个页面
 * （LazyColumn 只测可见项，成本很小）。
 */
@Composable
private fun KeepAlive(activeIndex: Int, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, placeable ->
                if (i == activeIndex) placeable.place(0, 0) else placeable.place(-30000, 0)
            }
        }
    }
}
