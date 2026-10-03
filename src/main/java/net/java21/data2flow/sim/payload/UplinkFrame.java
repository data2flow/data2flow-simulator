package net.java21.data2flow.sim.payload;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 가상 센서 보고 한 건의 내용(형식과 무관).
 *
 * @param organizationId  조직
 * @param sourceId        SIM 데이터 소스
 * @param externalId      기기 외부 ID(devEui)
 * @param deviceName      기기 이름
 * @param deviceProfile   기기 프로필 이름(연결 모델 코드 또는 유형 키)
 * @param measuredAt      측정 시각
 * @param values          측정 키 → 값(순서 유지)
 * @param units           측정 키 → 단위
 * @param frameCounter    fCnt
 * @param gatewayEui      가상 게이트웨이
 * @param rssi            수신 세기
 * @param snr             신호 대 잡음비
 * @param deduplicationId ChirpStack deduplicationId(결정적 UUID)
 * @param tags            기기 태그
 */
public record UplinkFrame(long organizationId, long sourceId, String externalId, String deviceName, String deviceProfile,
                          Instant measuredAt, Map<String, Double> values, Map<String, String> units, long frameCounter,
                          String gatewayEui, double rssi, double snr, UUID deduplicationId, Map<String, String> tags) {
}
