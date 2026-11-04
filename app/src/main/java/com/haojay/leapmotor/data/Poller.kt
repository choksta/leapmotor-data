package com.haojay.leapmotor.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自适应轮询器：App 在前台时按车辆状态调整采样频率并落库。
 *
 * 频率依据：
 * - 行驶 6 秒：里程/能耗变化快，官方 App 也在这个量级
 * - 充电 60 秒：SOC 每分钟约涨 0.1~0.5%，再快是浪费请求
 * - 驻车 600 秒：车机本来就不刷新，采样再密也只是重复同一帧
 *
 * 去重：以 `signal_time`（车机上报时间）为准，同一帧只入库一次。
 * 这也是驻车时不会灌满垃圾数据的原因——车机不更新就采不到新帧。
 *
 * 局限（B 方案固有）：App 不在前台就停止采样，行程会断段。
 */
class Poller(
    private val db: LpDb,
    private val fetch: suspend () -> CarSnapshot,
) {
    private var job: Job? = null

    /** 上一帧的车辆状态，用于检测翻转、切分行程。 */
    private var lastState: String? = null

    /** 最近一次采样的状态与去重结果，供 UI 展示。 */
    var lastTick: Tick? = null
        private set

    private data class Round(
        val state: String,
        val inserted: Boolean,
        val signalTime: Long,
        val nextSec: Long,
        val snapshot: CarSnapshot,
    )

    data class Tick(
        val state: String,
        val inserted: Boolean,
        val signalTime: Long,
        val at: Long,
        val snapshot: CarSnapshot,
    )

    val running: Boolean get() = job?.isActive == true

    fun start(scope: CoroutineScope, onTick: (Tick) -> Unit = {}) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                var nextSec = 600L
                // 网络与数据库必须离开主线程（否则 StrictMode 抛 NetworkOnMainThreadException）；
                // 只有 onTick 回调回到主线程去更新 Compose 状态。
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val snap = fetch()
                        val state = when {
                            snap.isCharging -> "charging"
                            snap.isDriving -> "driving"
                            else -> "parked"
                        }
                        val sample = snap.toSample(state)
                        val inserted = db.insertSample(sample)

                        // 行程切分与入库放在同一个 IO 块内完成
                        if (state != lastState) {
                            when {
                                state == "driving" -> db.openTrip(sample)
                                lastState == "driving" -> db.closeTrip(sample)
                            }
                            lastState = state
                        }
                        Round(
                            state = state,
                            inserted = inserted,
                            signalTime = snap.signalTime,
                            nextSec = when (state) {
                                "driving" -> 6L
                                "charging" -> 30L
                                // 驻车也要 30 秒：车机虽不刷新，但用户可能刚用 App 下过指令，
                                // 界面需要尽快追上真实状态（600 秒会让操作反馈看起来"失灵"）。
                                else -> 30L
                            },
                            snapshot = snap
                        )
                    }
                }

                result.onSuccess { r ->
                    nextSec = r.nextSec
                    val tick = Tick(
                        r.state, r.inserted, r.signalTime,
                        System.currentTimeMillis(), r.snapshot
                    )
                    lastTick = tick
                    onTick(tick)
                }.onFailure {
                    android.util.Log.e("Poller", "本轮采样失败", it)
                }
                delay(nextSec * 1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** 从已有数据里恢复上次状态，避免重启后重复开行程。 */
    fun restoreState(snap: CarSnapshot?) {
        lastState = snap?.let {
            when {
                it.isCharging -> "charging"
                it.isDriving -> "driving"
                else -> "parked"
            }
        }
    }
}

/** 把车况快照转成入库样本。 */
fun CarSnapshot.toSample(state: String): Sample = Sample(
    signalTime = if (signalTime > 0) signalTime else System.currentTimeMillis(),
    collectTime = System.currentTimeMillis(),
    soc = socPrecise,
    range = rangeLive,
    odo = odometer,
    speed = d("speed"),
    gear = i("gear"),
    charging = isCharging,
    state = state,
    lat = d("latitude"),
    lon = d("longitude"),
    locked = locked,
)
