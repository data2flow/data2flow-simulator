package net.java21.data2flow.sim.support;

import net.java21.data2flow.sim.physics.domain.ActuatorEffects;
import net.java21.data2flow.sim.physics.domain.Outdoor;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.physics.domain.PhysicsModel;
import net.java21.data2flow.sim.physics.domain.SolarModel;
import net.java21.data2flow.sim.physics.domain.SpacePhysics;
import net.java21.data2flow.sim.physics.domain.SpacePreset;
import net.java21.data2flow.sim.physics.domain.SpaceState;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 물리 하네스(SIM test-plan "공통 테스트 환경"): 공간 물리 모델을 서비스 없이 순수 Java로 틱 단위로 돌린다. 실제 대기 없음.
 */
public final class PhysicsHarness {

    public static final Instant NIGHT = Instant.parse("2026-08-10T15:00:00Z");   // 서울 자정(해 없음)
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SpacePhysics physics;
    private final SpaceState state;
    private OutdoorSpec outdoor = OutdoorSpec.constant(33, 60);
    private Instant now = NIGHT;
    private double tickSec = 1;
    private final SolarModel sun = SolarModel.seoul();
    private ActuatorEffects effects = new ActuatorEffects();
    private PhysicsModel.StepResult last = PhysicsModel.StepResult.IDLE;

    public PhysicsHarness(SpacePhysics physics) {
        this.physics = physics;
        this.state = SpaceState.initial(physics);
    }

    public static PhysicsHarness classroom() {
        return new PhysicsHarness(SpacePhysics.preset(SpacePreset.CLASSROOM));
    }

    public PhysicsHarness outdoor(OutdoorSpec spec) {
        this.outdoor = spec;
        return this;
    }

    public PhysicsHarness at(Instant start) {
        this.now = start;
        return this;
    }

    public PhysicsHarness tickSec(double sec) {
        this.tickSec = sec;
        return this;
    }

    public PhysicsHarness effects(Consumer<ActuatorEffects> setup) {
        ActuatorEffects e = new ActuatorEffects();
        setup.accept(e);
        this.effects = e;
        return this;
    }

    public PhysicsHarness state(Consumer<SpaceState> setup) {
        setup.accept(state);
        return this;
    }

    public SpaceState state() {
        return state;
    }

    public PhysicsModel.StepResult last() {
        return last;
    }

    public Instant now() {
        return now;
    }

    /** 한 틱 */
    public void tick() {
        Instant mid = now.plusMillis((long) (tickSec * 500));
        Outdoor o = outdoor.at(mid, SEOUL, sun, physics.outdoorCo2Ppm());
        last = PhysicsModel.step(state, physics, o, effects, tickSec);
        now = now.plusMillis((long) (tickSec * 1000));
    }

    /** 시뮬레이션 초만큼 진행하며 틱마다 값을 모은다 */
    public List<Double> run(double seconds, java.util.function.ToDoubleFunction<SpaceState> probe) {
        List<Double> values = new ArrayList<>();
        int ticks = (int) Math.round(seconds / tickSec);
        for (int i = 0; i < ticks; i++) {
            tick();
            values.add(probe.applyAsDouble(state));
        }
        return values;
    }

    /** 조건을 만족할 때까지 걸린 시뮬레이션 초(최대 maxSeconds, 못 닿으면 -1) */
    public double secondsUntil(double maxSeconds, java.util.function.Predicate<SpaceState> condition) {
        int ticks = (int) Math.round(maxSeconds / tickSec);
        for (int i = 1; i <= ticks; i++) {
            tick();
            if (condition.test(state)) {
                return i * tickSec;
            }
        }
        return -1;
    }

    /** 에어컨 냉방 설정 */
    public static Consumer<ActuatorEffects> cooling(double capacityKw, double setpoint) {
        return e -> {
            e.hvacMode = ActuatorEffects.HvacMode.COOL;
            e.coolingCapacityW = capacityKw * 1000;
            e.setpoint = setpoint;
        };
    }

    public static Consumer<ActuatorEffects> heating(double capacityKw, double setpoint) {
        return e -> {
            e.hvacMode = ActuatorEffects.HvacMode.HEAT;
            e.heatingCapacityW = capacityKw * 1000;
            e.setpoint = setpoint;
        };
    }
}
