package net.java21.data2flow.sim.engine;

import net.java21.data2flow.sim.physics.domain.SpacePhysics;

/**
 * 시뮬레이션에 넣는 가상 공간.
 *
 * @param spaceId  공간 ID(core 공간 ID)
 * @param name     이름
 * @param physics  물리 파라미터
 * @param sandbox  샌드박스(SIM-07.03)
 */
public record WorldSpace(long spaceId, String name, SpacePhysics physics, boolean sandbox) {
}
