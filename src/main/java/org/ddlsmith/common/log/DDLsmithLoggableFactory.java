package org.ddlsmith.common.log;

import java.io.PrintWriter;
import java.io.StringWriter;

/** The default log format of DDLsmith: plain SQL, one statement per line. */
public class DDLsmithLoggableFactory implements LoggableFactory {

    @Override
    public Loggable createLoggable(String input) {
        return new LoggedString(input, true);
    }

    @Override
    public Loggable createLoggableWithNoLinebreak(String input) {
        return new LoggedString(input, false);
    }

    @Override
    public Loggable convertStacktraceToLoggable(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        return new LoggedString(stringWriter.toString(), true);
    }

    @Override
    public Loggable getInfo(String databaseName, String databaseVersion, long seedValue) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- DDLsmith reproduction log\n");
        sb.append("-- database : ").append(databaseName).append('\n');
        sb.append("-- version  : ").append(databaseVersion).append('\n');
        sb.append("-- seed     : ").append(seedValue).append('\n');
        return new LoggedString(sb.toString(), true);
    }
}
