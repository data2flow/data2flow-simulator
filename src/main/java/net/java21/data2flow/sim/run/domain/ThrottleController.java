package net.java21.data2flow.sim.run.domain;

/**
 * 가상 메시지 속도 제한(SIM-11.02, BR-SIM-09). 조직 전체 가상 메시지가 초당 상한(설계 처리량 200의 50% = 100)을 넘으면 실행의 가속을
 * 절반씩 낮추고, 상한의 절반 아래로 1분 동안 머물면 요청한 가속 쪽으로 두 배씩 되돌린다. 실제 수집률을 알면 상한을 그만큼 줄인다
 * (실제 + 가상 ≤ 설계 처리량).
 */
public final class ThrottleController {

    private final double designPerSec;
    private final double share;
    private double windowCount;
    private long windowStartMs = -1;
    private long calmSinceMs = -1;

    public ThrottleController(double designPerSec, double share) {
        this.designPerSec = designPerSec;
        this.share = share;
    }

    /**
     * @param from   바꾸기 전 가속
     * @param to     바꾼 가속
     * @param rate   측정한 가상 메시지 초당 건수
     * @param reason 이유
     */
    public record Decision(int from, int to, double rate, String reason) {
        public boolean changed() {
            return from != to;
        }
    }

    /** 가상 메시지 상한(초당). 실제 수집률이 있으면 남는 몫과 50% 중 작은 값 */
    public double limit(double realIngestPerSec) {
        double base = designPerSec * share;
        double free = designPerSec - Math.max(0, realIngestPerSec);
        return Math.max(1, Math.min(base, free));
    }

    /** 메시지 건수를 더한다(1초 창) */
    public void record(long nowMs, int messages) {
        if (windowStartMs < 0 || nowMs - windowStartMs >= 1000) {
            windowStartMs = nowMs;
            windowCount = 0;
        }
        windowCount += messages;
    }

    /**
     * @param effective 지금 가속
     * @param requested 요청한 가속
     * @param measuredRate 최근 1초 가상 메시지 수(조직 전체)
     */
    public Decision decide(long nowMs, int effective, int requested, double measuredRate, double realIngestPerSec) {
        double limit = limit(realIngestPerSec);
        if (measuredRate > limit && effective > 1) {
            calmSinceMs = -1;
            int to = Math.max(1, effective / 2);
            return new Decision(effective, to, measuredRate, "VIRTUAL_RATE_LIMIT");
        }
        if (measuredRate <= limit / 2 && effective < requested) {
            if (calmSinceMs < 0) {
                calmSinceMs = nowMs;
            }
            if (nowMs - calmSinceMs >= 60_000) {
                calmSinceMs = nowMs;
                return new Decision(effective, Math.min(requested, effective * 2), measuredRate, "RECOVERED");
            }
        } else {
            calmSinceMs = -1;
        }
        return new Decision(effective, effective, measuredRate, null);
    }

    public double currentWindow() {
        return windowCount;
    }
}
