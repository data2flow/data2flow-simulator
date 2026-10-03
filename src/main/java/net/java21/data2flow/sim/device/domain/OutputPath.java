package net.java21.data2flow.sim.device.domain;

/**
 * 가상 센서 출력 경로(SIM-02.07). 운영·스테이징은 {@link #INTERNAL}(data2flow.raw 직접 주입)만 쓴다.
 * {@link #PLATFORM_MQTT}는 테스트 환경(Testcontainers Mosquitto) 전용이고 M7 범위다. 공용 브로커에는 어떤 경우에도 발행하지 않는다(CLAUDE.md §5).
 */
public enum OutputPath {
    INTERNAL, PLATFORM_MQTT
}
