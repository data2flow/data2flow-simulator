package net.java21.data2flow.sim.common;

import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.CurrentUserHolder;

/** 요청한 사용자(감사·작성자). 내부 호출에 사용자가 없으면 null(실행 requested_by는 0) */
public final class Users {

    private Users() {
    }

    public static Long current() {
        return CurrentUserHolder.find().map(CurrentUser::userId).orElse(null);
    }

    public static long currentOrSystem() {
        Long id = current();
        return id == null ? 0 : id;
    }
}
