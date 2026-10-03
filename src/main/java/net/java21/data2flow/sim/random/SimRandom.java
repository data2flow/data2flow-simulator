package net.java21.data2flow.sim.random;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * 결정적 난수(SIM-08.03, BR-SIM-07). 난수는 언제나 (실행 시드, 기기 키, 용도, 순번)으로 정한 독립 스트림에서 뽑는다.
 *
 * <p>상태를 들고 다니지 않으므로 체크포인트에서 이어 실행해도, 기기를 추가하거나 순서를 바꿔도 다른 기기의 값이 바뀌지 않는다
 * (TC-SIM-088 "seed ⊕ hash(deviceName)"). {@code Math.random}·{@code ThreadLocalRandom}·시스템 시계는 쓰지 않는다(ArchUnit으로 강제).
 * 정규분포는 JDK 구현 변화에 영향을 받지 않도록 Box–Muller를 직접 계산한다.
 */
public final class SimRandom {

    private SimRandom() {
    }

    /** (seed, 키들)로 정한 독립 난수 스트림 */
    public static SplittableRandom stream(long seed, Object... keys) {
        return new SplittableRandom(mix(seed, keys));
    }

    /** (seed, 키들)의 SHA-256 앞 8바이트 */
    public static long mix(long seed, Object... keys) {
        MessageDigest digest = sha256();
        digest.update(Long.toString(seed).getBytes(StandardCharsets.UTF_8));
        for (Object key : keys) {
            digest.update((byte) 0x1f);
            digest.update(String.valueOf(key).getBytes(StandardCharsets.UTF_8));
        }
        byte[] h = digest.digest();
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (h[i] & 0xff);
        }
        return v;
    }

    /** [0,1) 균등 난수 하나 */
    public static double uniform(long seed, Object... keys) {
        return stream(seed, keys).nextDouble();
    }

    /** 표준 정규 난수 하나 */
    public static double gaussian(long seed, Object... keys) {
        return gaussian(stream(seed, keys));
    }

    /** 표준 정규 난수(Box–Muller, 결정적) */
    public static double gaussian(SplittableRandom r) {
        double u1 = r.nextDouble();
        double u2 = r.nextDouble();
        if (u1 < 1e-300) {
            u1 = 1e-300;
        }
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    /** 확률 p로 true */
    public static boolean bernoulli(double p, long seed, Object... keys) {
        if (p <= 0) {
            return false;
        }
        if (p >= 1) {
            return true;
        }
        return uniform(seed, keys) < p;
    }

    /** 결정적 UUID(메시지 ID·ChirpStack deduplicationId). 같은 입력이면 같은 값이라 재생성분은 수집 단계에서 중복으로 걸러진다 */
    public static UUID uuid(long seed, Object... keys) {
        SplittableRandom r = stream(seed, keys);
        long msb = (r.nextLong() & ~0xF000L) | 0x4000L;
        long lsb = (r.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }
}
