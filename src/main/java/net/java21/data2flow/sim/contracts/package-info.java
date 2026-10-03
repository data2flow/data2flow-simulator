/**
 * <b>TODO contracts:</b> simulator가 내는 메시지의 로컬 정의. data2flow-contracts의 {@code EventType}·{@code domain-event.v1.json}에
 * 아직 없는 이벤트(EVT-SIM-01 {@code sim.run.*}, EVT-SIM-02 {@code sim.fault.*}, EVT-SIM-03 {@code device.command.ack}·
 * {@code device.state.reported})다. 봉투는 contracts {@code DomainEvent} 그대로 쓰고 페이로드만 여기 둔다. contracts에 옮기면 이 패키지를 지운다.
 */
package net.java21.data2flow.sim.contracts;
