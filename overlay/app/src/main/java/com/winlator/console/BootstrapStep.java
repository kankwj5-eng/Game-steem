package com.winlator.console;

public final class BootstrapStep {
    public enum State { WAITING, RUNNING, DONE, ERROR }

    public final String id;
    public final String title;
    public final String detail;
    public final State state;
    public final int progress;

    public BootstrapStep(String id, String title, String detail, State state, int progress) {
        this.id = id;
        this.title = title;
        this.detail = detail;
        this.state = state;
        this.progress = progress;
    }
}
