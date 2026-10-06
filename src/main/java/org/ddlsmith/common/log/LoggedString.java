package org.ddlsmith.common.log;

public class LoggedString implements Loggable {

    private final String string;
    private final boolean lineBreak;

    public LoggedString(String string, boolean lineBreak) {
        this.string = string;
        this.lineBreak = lineBreak;
    }

    @Override
    public String getLogString() {
        return lineBreak ? string + "\n" : string;
    }
}
