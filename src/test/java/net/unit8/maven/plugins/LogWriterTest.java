package net.unit8.maven.plugins;

import org.apache.maven.monitor.logging.DefaultLog;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

public class LogWriterTest {
    @Test
    public void test() {
        Logger logger = spy(new ConsoleLogger());
        Log log = new DefaultLog(logger);
        LogWriter logWriter = new LogWriter(log);

        logger.info(anyString());
        new Exception().printStackTrace(new PrintWriter(logWriter));
        verify(logger).info("java.lang.Exception");
    }
}
