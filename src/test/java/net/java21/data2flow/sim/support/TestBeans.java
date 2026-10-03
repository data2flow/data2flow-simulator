package net.java21.data2flow.sim.support;

import io.micrometer.core.instrument.MeterRegistry;
import net.java21.data2flow.sim.common.SimProperties;
import net.java21.data2flow.sim.run.service.VirtualDataPurger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

/** 시험 빈: 움직일 수 있는 시계, 기록하는 원본 출력, core 정리 요청 대역 */
@TestConfiguration(proxyBeanMethods = false)
public class TestBeans {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(MutableClock.T0);
    }

    @Bean
    RecordingRawOutput rawOutput(SimProperties properties, MeterRegistry registry) {
        return new RecordingRawOutput(properties.stream(), registry);
    }

    @Bean
    FakePurger virtualDataPurger() {
        return new FakePurger();
    }

    /** core 가상 데이터 정리 요청 대역 */
    public static class FakePurger implements VirtualDataPurger {
        public final List<List<Long>> requests = new ArrayList<>();
        public volatile boolean accept = true;

        @Override
        public synchronized boolean requestPurge(long organizationId, List<Long> runIds) {
            requests.add(List.copyOf(runIds));
            return accept;
        }
    }
}
