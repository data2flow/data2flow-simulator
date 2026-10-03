package net.java21.data2flow.sim.run.domain;

import net.java21.data2flow.sim.engine.WorldDevice;
import net.java21.data2flow.sim.engine.WorldSpace;
import net.java21.data2flow.sim.scenario.domain.Scenario;

import java.util.List;

/**
 * 실행 계획({@code runs.plan}): 시작할 때 고정한 시나리오·공간 물리·기기 설정. 실행 중 시나리오를 고쳐도 이어 실행 결과가 같다.
 * 실행 중 기기 설정(생성기 등)을 바꾸면 계획도 함께 고친다(SIM-02.03 "다음 보고부터 반영").
 *
 * @param scenarioName 시나리오 이름(리포트용)
 */
public record RunPlan(Long scenarioId, String scenarioName, Scenario scenario, List<WorldSpace> spaces, List<WorldDevice> devices,
                      int tickSec) {
}
