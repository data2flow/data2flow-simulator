package net.java21.data2flow.sim.output;

import net.java21.data2flow.contracts.message.RawEnvelope;

import java.util.concurrent.CompletionStage;

/**
 * 가상 센서 원본의 출력 경로(SIM-02.07, BR-SIM-01): 내부 직접 주입({@code data2flow.raw} Super Stream). 공용 MQTT 브로커에는 어떤 경우에도
 * 발행하지 않는다(CLAUDE.md §5). 발행 확인(confirm)을 받으면 완료된다.
 */
public interface RawOutput {

    CompletionStage<Void> publish(RawEnvelope envelope);

    boolean ready();
}
