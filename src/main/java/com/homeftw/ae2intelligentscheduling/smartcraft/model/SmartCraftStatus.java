package com.homeftw.ae2intelligentscheduling.smartcraft.model;

public enum SmartCraftStatus {

    ANALYZING,
    QUEUED,
    PENDING,
    WAITING_CPU,
    SUBMITTING,
    RUNNING,
    VERIFYING_OUTPUT,
    DONE,
    FAILED,
    PAUSED,
    CANCELLED,
    COMPLETED;

    public boolean isTerminalTaskState() {
        return this == DONE || this == FAILED || this == CANCELLED || this == COMPLETED;
    }

    public boolean isActiveTaskState() {
        return this == SUBMITTING || this == RUNNING || this == VERIFYING_OUTPUT;
    }
}
