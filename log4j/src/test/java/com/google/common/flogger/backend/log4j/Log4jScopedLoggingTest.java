package com.google.common.flogger.backend.log4j;

import com.google.common.flogger.GoogleLogContext;
import com.google.common.flogger.GoogleLogger;
import com.google.common.flogger.MetadataKey;
import com.google.common.flogger.backend.LoggerBackend;
import com.google.common.flogger.context.ContextDataProvider;
import com.google.common.flogger.context.ScopedLoggingContext;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.WriterAppender;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;
import static org.apache.log4j.Level.*;

@RunWith(JUnit4.class)
public class Log4jScopedLoggingTest {

    private static final AtomicInteger uid = new AtomicInteger();
    private static final MetadataKey<Integer> COUNT_KEY = MetadataKey.single("count", Integer.class);
    private static GoogleLogger googleLogger;
    private LoggerBackend backend;
    private List<LoggingEvent> events;
    private Writer w;

    @Before
    public void setUpLoggerBackend() {
        // A unique name should produce a different logger for each test allowing tests to be run in
        // parallel.
        String loggerName = Log4jScopedLoggingTest.class.getName();
        w = new StringWriter();
        CapturingAppender appender = new CapturingAppender(w);
        googleLogger = GoogleLogger.forEnclosingClass();
        Logger logger = Logger.getLogger(loggerName);
        logger.addAppender(appender);
        backend = new Log4jLoggerBackend(logger);
        events = appender.events;
    }

    @After
    public void tearDown() {
        Logger.getRootLogger().getLoggerRepository().resetConfiguration();
    }

    void assertLogEntry(int index, Level level, String message, Map<String, Object> contextData) {
        final LoggingEvent event = events.get(index);
        assertThat(event.getLevel()).isEqualTo(level);
        assertThat(event.getRenderedMessage()).isEqualTo(message);
        assertThat(event.getThrowableInformation()).isNull();
        String loggerName = Log4jScopedLoggingTest.class.getName();
        Logger logger = Logger.getLogger(loggerName);
        // TODO: Check why printing to console doe snot work during test execution.
        //       To print the log statement in teh test output, I need to do the formatting manually. I do not know
        //       why this is the case.
        System.out.println("Output="+((CapturingAppender)logger.getAllAppenders().nextElement()).getLayout().format(event));
        for (Map.Entry<String, Object> entry : contextData.entrySet()) {
            assertThat(event.getProperties().containsKey(entry.getKey())).isTrue();
            assertThat(event.getProperties().get(entry.getKey()).toString().equals(entry.getValue().toString())).isTrue();
        }
    }

    void assertLogCount(int count) {
        assertThat(events).hasSize(count);
    }

    @Test
    public void testSingleNonRepeatableMetadata() {
        try (ScopedLoggingContext.LoggingContextCloseable ctx = ContextDataProvider.getInstance()
                .getContextApiSingleton()
                .newContext()
                .withMetadata(COUNT_KEY, 23)
                .install()
        ) {
            GoogleLogContext logContext = (GoogleLogContext) googleLogger.atInfo();
            logContext.log("test");
            backend.log(logContext); // this event will be caught
            System.out.println("output="+w.toString());
            Map<String, Object> contextMap = new HashMap<String, Object>();
            contextMap.put("count", 23);
            assertLogCount(2);
            assertLogEntry(1, INFO, "test", contextMap);
        }
    }

    private static final class CapturingAppender extends WriterAppender {
        private final List<LoggingEvent> events = new ArrayList<>();

        CapturingAppender(Writer writer) {
            super(new PatternLayout("%m count=%X{count}"), writer);
            setThreshold(ALL);
            setEncoding("UTF-8");
            activateOptions();
        }

        @Override
        public void append(LoggingEvent event) {
            events.add(event);
        }
    }
}
