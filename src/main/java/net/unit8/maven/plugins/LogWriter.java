package net.unit8.maven.plugins;

import org.apache.maven.plugin.logging.Log;
import org.eclipse.persistence.internal.helper.Helper;

import java.io.StringWriter;

public class LogWriter extends StringWriter {
    private Log log;

    public LogWriter(Log log) {
        super();
        this.log = log;
    }

    @Override
    public void write(String str) {
        if (!Helper.cr().equals(str)) {
            getBuffer().append(str);
        } else {
            flush();
        }
    }

    @Override
    public void flush() {
        if (getBuffer().length() > 0) {
            log.info(getBuffer());
            getBuffer().delete(0, getBuffer().length());
        }
    }
}
