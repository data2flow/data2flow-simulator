package net.java21.data2flow.sim.common;

import org.flywaydb.core.Flyway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/** simulator 공통 빈: 시계, Flyway 실행 방식 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SimProperties.class)
@EnableScheduling
public class SimConfig {

    /** 운영 코드는 이 시계만 쓴다(ArchUnit NO_SYSTEM_CLOCK). 테스트는 MutableClock으로 바꾼다 */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * ADR-030: staging과 prod가 DB 하나를 함께 쓰므로 migrate는 staging 배포와 시험에서만 한다. 그 밖(prod·local)은 validate만.
     */
    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy(SimProperties properties) {
        return (Flyway flyway) -> {
            if ("migrate".equalsIgnoreCase(properties.flywayMode())) {
                flyway.migrate();
            } else {
                flyway.validate();
            }
        };
    }
}
