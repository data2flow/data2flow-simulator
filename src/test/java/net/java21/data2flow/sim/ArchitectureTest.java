package net.java21.data2flow.sim;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import net.java21.data2flow.contracts.test.arch.Data2flowArchRules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 공통 ArchUnit 규칙(testing/backend.md §6)과 결정적 재현 규칙(TC-SIM-089): 물리·생성기·엔진 패키지에서 {@code Math.random}·
 * {@code ThreadLocalRandom}·{@code java.util.Random}·시스템 시계 호출 금지. 공용 MQTT 클라이언트 의존 금지(CLAUDE.md §5).
 */
@AnalyzeClasses(packages = "net.java21.data2flow.sim", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule organizationScoped = Data2flowArchRules.REPOSITORY_QUERIES_ARE_ORGANIZATION_SCOPED;
    @ArchTest
    static final ArchRule noUnscopedCrud = Data2flowArchRules.UNSCOPED_CRUD_LOOKUPS_ARE_NOT_CALLED;
    @ArchTest
    static final ArchRule noSleep = Data2flowArchRules.NO_THREAD_SLEEP;
    @ArchTest
    static final ArchRule noSystemClock = Data2flowArchRules.NO_SYSTEM_CLOCK;

    /** [SIM-08.03][AT-SIM-08.3][TC-SIM-089] 결정적 재현: 전역 난수 금지 */
    @ArchTest
    static final ArchRule deterministicRandom = noClasses()
            .that().resideInAnyPackage("..sim.physics..", "..sim.sensor..", "..sim.engine..", "..sim.fault..", "..sim.actuator..",
                    "..sim.payload..", "..sim.random..", "..sim.scenario.domain..")
            .should().callMethod(Math.class, "random")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.ThreadLocalRandom")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.Random")
            .because("같은 시드 = 같은 결과(BR-SIM-07): 난수는 SimRandom으로만 뽑는다");

    /** 공용 MQTT 브로커에 발행할 수단 자체를 두지 않는다(CLAUDE.md §5, SIM-02.07 M7 전까지) */
    @ArchTest
    static final ArchRule noMqttClient = noClasses().should().dependOnClassesThat().resideInAnyPackage("com.hivemq..", "org.eclipse.paho..")
            .because("시뮬레이터는 공용 브로커 iot-data.java21.net에 어떤 경우에도 발행하지 않는다");
}
