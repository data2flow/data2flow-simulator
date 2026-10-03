package net.java21.data2flow.sim.sensor.domain;

/** 생성기 하나의 상태(체크포인트에 직렬화) */
public final class GeneratorState {
    /** 랜덤 워크 현재 값 */
    public double current;
    public boolean initialized;
    /** EVENT: 활성 끝 시각(epoch 초). 0이면 비활성 */
    public long activeUntil;
    /** EVENT: 지금까지 일어난 이벤트 수 */
    public long events;

    public GeneratorState copy() {
        GeneratorState s = new GeneratorState();
        s.current = current;
        s.initialized = initialized;
        s.activeUntil = activeUntil;
        s.events = events;
        return s;
    }
}
