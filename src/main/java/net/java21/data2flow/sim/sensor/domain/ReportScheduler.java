package net.java21.data2flow.sim.sensor.domain;

import net.java21.data2flow.sim.random.SimRandom;

/**
 * 보고 주기와 지터(SIM-02.05). 다음 보고 = 이전 보고 + 주기 × (1 + U(−j, +j)). 첫 보고는 시작 뒤 U(0, 주기) 안
 * (여러 기기가 한꺼번에 보고하지 않게). 난수는 (시드, 기기, 보고 순번)으로 정해 재현된다.
 */
public final class ReportScheduler {

    private ReportScheduler() {
    }

    /** 첫 보고까지(밀리초) */
    public static long firstOffsetMs(int intervalSec, long seed, String deviceKey) {
        return (long) Math.floor(SimRandom.uniform(seed, deviceKey, "first-report") * intervalSec * 1000.0);
    }

    /**
     * @param reportIndex 방금 한 보고의 순번(0부터)
     * @return 다음 보고까지(밀리초, 최소 1초)
     */
    public static long nextIntervalMs(int intervalSec, double jitterPct, long seed, String deviceKey, long reportIndex) {
        double j = Math.max(0, Math.min(50, jitterPct)) / 100.0;
        double u = j == 0 ? 0 : (SimRandom.uniform(seed, deviceKey, "jitter", reportIndex) * 2 - 1) * j;
        return Math.max(1000L, Math.round(intervalSec * 1000.0 * (1 + u)));
    }
}
