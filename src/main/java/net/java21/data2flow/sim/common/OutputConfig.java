package net.java21.data2flow.sim.common;

import io.micrometer.core.instrument.MeterRegistry;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.contracts.messaging.SuperStreamSpec;
import net.java21.data2flow.sim.command.repository.CommandInboxRepository;
import net.java21.data2flow.sim.device.repository.SimDeviceRepository;
import net.java21.data2flow.sim.fault.repository.FaultRepository;
import net.java21.data2flow.sim.heartbeat.HeartbeatCanary;
import net.java21.data2flow.sim.output.AmqpSimEventPublisher;
import net.java21.data2flow.sim.output.MemoryRawOutput;
import net.java21.data2flow.sim.output.MemorySimEventPublisher;
import net.java21.data2flow.sim.output.RawOutput;
import net.java21.data2flow.sim.output.RawStreamPublisher;
import net.java21.data2flow.sim.output.SimEventPublisher;
import net.java21.data2flow.sim.run.repository.LeaseRepository;
import net.java21.data2flow.sim.run.repository.RunRepository;
import net.java21.data2flow.sim.run.service.CoreVirtualDataPurger;
import net.java21.data2flow.sim.run.service.SimulationExecutor;
import net.java21.data2flow.sim.run.service.VirtualDataPurger;
import net.java21.data2flow.sim.run.service.WorldFactory;
import net.java21.data2flow.sim.space.repository.SpacePhysicsRepository;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/** 출력(data2flow.raw·data2flow.events)과 실행기 빈 */
@Configuration(proxyBeanMethods = false)
public class OutputConfig {

    @Bean
    @ConditionalOnMissingBean
    RawOutput rawOutput(SimProperties properties, MeterRegistry registry) {
        if ("MEMORY".equalsIgnoreCase(properties.output().mode())) {
            return new MemoryRawOutput();
        }
        return new RawStreamPublisher(properties.stream(), SuperStreamSpec.RAW, registry);
    }

    @Bean
    @ConditionalOnMissingBean
    SimEventPublisher simEventPublisher(SimProperties properties, RabbitTemplate rabbit, Clock clock) {
        if (!properties.output().events()) {
            return new MemorySimEventPublisher();
        }
        return new AmqpSimEventPublisher(rabbit, clock);
    }

    /** 도메인 이벤트 topic exchange(없으면 만든다. 이름·종류는 모든 서비스가 같다) */
    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(MessagingNames.EXCHANGE_EVENTS, true, false);
    }

    @Bean
    SimDirectory simDirectory(SimProperties properties, RestClient.Builder builder, Clock clock) {
        return new SimDirectory(properties, builder, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    VirtualDataPurger virtualDataPurger(RestClient.Builder builder, SimProperties properties) {
        return new CoreVirtualDataPurger(builder, properties.coreUri());
    }

    @Bean
    SimulationExecutor simulationExecutor(SimProperties properties, SimDirectory directory, RunRepository runs, LeaseRepository leases,
                                          FaultRepository faults, SimDeviceRepository devices, SpacePhysicsRepository spaces,
                                          CommandInboxRepository inbox, WorldFactory factory, RawOutput output, SimEventPublisher events,
                                          Clock clock, MeterRegistry registry) {
        return new SimulationExecutor(properties, directory, runs, leases, faults, devices, spaces, inbox, factory, output, events,
                clock, registry);
    }

    @Bean
    HeartbeatCanary heartbeatCanary(SimProperties properties, SimDirectory directory, RawOutput output, Clock clock) {
        return new HeartbeatCanary(properties, directory, output, clock);
    }

    /** readiness: data2flow.raw에 기록할 수 있어야 한다(reliability-and-ha.md §4.2) */
    @Bean
    HealthIndicator rawStream(RawOutput output) {
        return () -> output.ready() ? Health.up().build() : Health.down().withDetail("stream", "data2flow.raw 생산자 준비 안 됨").build();
    }
}
