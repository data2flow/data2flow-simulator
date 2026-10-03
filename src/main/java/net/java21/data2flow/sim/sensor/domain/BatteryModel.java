package net.java21.data2flow.sim.sensor.domain;

/** 배터리(SIM-02.05): 보고할 때마다 정해진 만큼 줄고 0 아래로 내려가지 않는다. 0이면 보고를 멈춘다(TC-SIM-025) */
public final class BatteryModel {

    private BatteryModel() {
    }

    public static double afterReport(double batteryPct, double drainPerReport) {
        return Math.max(0, batteryPct - Math.max(0, drainPerReport));
    }

    /** 배터리 수명(년)과 보고 주기로 보고당 소모량(%p)을 구한다 */
    public static double drainPerReport(double lifeYears, int intervalSec) {
        if (lifeYears <= 0) {
            return 0;
        }
        double reports = lifeYears * 365.0 * 86400.0 / Math.max(1, intervalSec);
        return 100.0 / reports;
    }

    public static boolean depleted(double batteryPct) {
        return batteryPct <= 0;
    }
}
