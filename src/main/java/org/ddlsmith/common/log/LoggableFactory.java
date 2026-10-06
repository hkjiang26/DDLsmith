package org.ddlsmith.common.log;

public interface LoggableFactory {

    Loggable createLoggable(String input);

    Loggable createLoggableWithNoLinebreak(String input);

    Loggable convertStacktraceToLoggable(Throwable throwable);

    Loggable getInfo(String databaseName, String databaseVersion, long seedValue);
}
