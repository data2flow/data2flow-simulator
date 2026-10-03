package net.java21.data2flow.sim.run.domain;

import net.java21.data2flow.sim.engine.WorldState;

/**
 * 체크포인트({@code runs.checkpoint}): 엔진 상태 + 가속 기준점. 10틱마다, 일시정지·정지할 때 저장한다.
 *
 * @param anchorRealMs 가속 기준 실제 시각(epoch ms)
 * @param anchorTick   가속 기준 틱
 * @param world        엔진 상태
 */
public record Checkpoint(long anchorRealMs, long anchorTick, WorldState world) {
}
