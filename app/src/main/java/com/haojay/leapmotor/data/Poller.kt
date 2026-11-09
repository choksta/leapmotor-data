package com.haojay.leapmotor.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 前台自适应轮询器。
 *
 * 只做一件事：按车辆状态调整拉取间隔，把最新快照推给界面。
 * 之前它还负责把每帧写入 SQLite 以便切分行程，那套记录已经移除——
 * 界面实时性依赖的是"拉取 + 回传"，跟落库无关，所以拆掉记录不影响自动刷新。
 *
 * 频率依据：
 * - 行驶 6 秒：里程/能耗变化快，官方 App 也在这个量级
 * - 充电前段 15 秒、80% 之后 30 秒：前段 SOC 涨得快，后段曲线变平（见 [Cadence]）
 * - 驻车 30 秒：车机虽不刷新，但用户可能刚下过指令，界面需要尽快追上真实状态
 */
class Poller(
    private val fetch: suspend () -> CarSnapshot,
) {
    private var job: Job? = null

    /** 最近一次拉取的结果，供 UI 展示状态与间隔。 */
    var lastTick: Tick? = null
        private set

    private data class Round(
        val state: String,
        val signalTime: Long,
        val nextSec: Long,
        val snapshot: CarSnapshot,
    )

    data class Tick(
        val state: String,
        val signalTime: Long,
        val at: Long,
        val snapshot: CarSnapshot,
        val nextSec: Long,
    )

    val running: Boolean get() = job?.isActive == true

    fun start(scope: CoroutineScope, onTick: (Tick) -> Unit = {}) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                var nextSec = 30L
                // 网络必须离开主线程（否则 StrictMode 抛 NetworkOnMainThreadException）；
                // 只有 onTick 回调回到主线程去更新 Compose 状态。
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val snap = fetch()
                        val state = when {
                            snap.isCharging -> "charging"
                            snap.isDriving -> "driving"
                            else -> "parked"
                        }
                        Round(
                            state = state,
                            signalTime = snap.signalTime,
                            nextSec = when (state) {
                                "driving" -> Cadence.DRIVING_MS / 1000
                                // 充电前段 15 秒跟手，80% 之后回到 30 秒省请求
                                "charging" -> Cadence.forSnapshot(snap) / 1000
                                else -> Cadence.IDLE_MS / 1000
                            },
                            snapshot = snap
                        )
                    }
                }

                result.onSuccess { r ->
                    nextSec = r.nextSec
                    val tick = Tick(
                        state = r.state,
                        signalTime = r.signalTime,
                        at = System.currentTimeMillis(),
                        snapshot = r.snapshot,
                        nextSec = r.nextSec,
                    )
                    lastTick = tick
                    onTick(tick)
                }.onFailure {
                    android.util.Log.e("Poller", "本轮刷新失败", it)
                }
                delay(nextSec * 1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
