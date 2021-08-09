/*
 * Copyright (C) 2018 The Flogger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.flogger.backend.log4j;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.logging.Level.WARNING;

import com.google.common.flogger.LogContext;
import com.google.common.flogger.LogSite;
import com.google.common.flogger.MetadataKey;
import com.google.common.flogger.backend.BaseMessageFormatter;
import com.google.common.flogger.backend.LogData;
import com.google.common.flogger.backend.MessageUtils;
import com.google.common.flogger.backend.Metadata;
import com.google.common.flogger.backend.MetadataHandler;
import com.google.common.flogger.backend.MetadataProcessor;
import com.google.common.flogger.backend.Platform;
import com.google.common.flogger.backend.SimpleMessageFormatter;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.google.common.flogger.context.ScopedLoggingContext;
import com.google.common.flogger.context.Tags;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.MDC;
import org.apache.log4j.helpers.ThreadLocalMap;
import org.apache.log4j.spi.LocationInfo;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.log4j.spi.ThrowableInformation;

/**
 * Helper to format LogData.
 *
 * <p>Note: This code is mostly derived from the equivalently named class in the Log4j2 backend
 * implementation, and should be kept in-sync with it as far as possible. If possible, any changes
 * to the functionality of this class should first be made in the log4j2 backend and then reflected
 * here. If the behaviour of this class starts to deviate from that of the log4j2 backend in any
 * significant way, this difference should be called out clearly in the documentation.
 */
final class Log4jLoggingEventUtil {
  private Log4jLoggingEventUtil() {}

  static LoggingEvent toLog4jLoggingEvent(Logger logger, LogData logData) {
    MetadataProcessor metadata =
            MetadataProcessor.forScopeAndLogSite(Platform.getInjectedMetadata(), logData.getMetadata());
    String message = "";

    // Unlike log4j2, there is no such thing as a configuration class in log4j, hence one configures the
    // logger directly. This means, we need to check the loggers individually in order to determine if
    // appenders were added to a given logger (by default there no appenders added to a logger).
    // Given there is at least one appender added to teh given logger, we assume one may want to access the
    // MDC as well and we ensure that the MDC is populated with the data of flogger's current scoped logging
    // context. This data won't be included in teh formatted logs unless explicity requested via pattern conversion.
    Logger currentLogger = LogManager.getLoggerRepository().getLogger(logData.getLoggerName());
    Enumeration appenders = currentLogger.getAllAppenders();
    if (!appenders.hasMoreElements()) {
      message = SimpleMessageFormatter.getDefaultFormatter().format(logData, metadata);
    } else {
      message = BaseMessageFormatter.appendFormattedMessage(logData, new StringBuilder()).toString();
    }
    Throwable thrown = metadata.getSingleValue(LogContext.Key.LOG_CAUSE);
    return toLog4jLoggingEvent(logger, logData, message, toLog4jLevel(logData.getLevel()), thrown);
  }

  static LoggingEvent toLog4jLoggingEvent(Logger logger, RuntimeException error, LogData badData) {
    String message = formatBadLogData(error, badData);
    // Re-target this log message as a warning (or above) since it indicates a real bug.
    Level level = badData.getLevel().intValue() < WARNING.intValue() ? WARNING : badData.getLevel();
    return toLog4jLoggingEvent(logger, badData, message, toLog4jLevel(level), error);
  }

  private static LoggingEvent toLog4jLoggingEvent(
          Logger logger,
          LogData logData,
          String message,
          org.apache.log4j.Level level,
          Throwable thrown) {
    // The Nested Diagnostic Context (NDC) allows to include additional metadata into logs which
    // are written from the current thread.
    //
    // Example:
    //  NDC.push("user.id=" + userId);
    //  // do business logic that triggers logs
    //  NDC.pop();
    //  NDC.remove();
    //
    // By using '%x' in the ConversionPattern of an appender this data can be included in the logs.
    //
    // We could include this data here by doing 'NDC.get()', but we don't want to encourage people
    // using the log4j specific NDC. Instead this should be supported by a LoggingContext and usage
    // of Flogger tags.
    String nestedDiagnosticContext = "";

    // The Mapped Diagnostic Context (MDC) allows to include additional metadata into logs which
    // are written from the current thread.
    //
    // Example:
    //  MDC.put("user.id", userId);
    //  // do business logic that triggers logs
    //  MDC.clear();
    //
    // By using '%X{key}' in the ConversionPattern of an appender this data can be included in the
    // logs.
    //
    // We could include this data here by doing 'MDC.getContext()', but we don't want to encourage
    // people using the log4j specific MDC. Instead this should be supported by a LoggingContext and
    // usage of Flogger tags.
    Map<String, Object> mdcProperties = createContextMap(logData);

    LogSite logSite = logData.getLogSite();
    LocationInfo locationInfo =
            new LocationInfo(
                    logSite.getFileName(),
                    logSite.getClassName(),
                    logSite.getMethodName(),
                    Integer.toString(logSite.getLineNumber()));

    return new LoggingEvent(
            logData.getLoggerName(),
            logger,
            NANOSECONDS.toMillis(logData.getTimestampNanos()),
            level,
            message,
            Thread.currentThread().getName(),
            thrown != null ? new ThrowableInformation(thrown) : null,
            nestedDiagnosticContext,
            locationInfo,
            mdcProperties);
  }

  /** Converts java.util.logging.Level to org.apache.log4j.Level. */
  static org.apache.log4j.Level toLog4jLevel(Level level) {
    if (level.intValue() >= Level.SEVERE.intValue()) {
      return org.apache.log4j.Level.ERROR;
    } else if (level.intValue() >= Level.WARNING.intValue()) {
      return org.apache.log4j.Level.WARN;
    } else if (level.intValue() >= Level.INFO.intValue()) {
      return org.apache.log4j.Level.INFO;
    } else if (level.intValue() >= Level.FINE.intValue()) {
      return org.apache.log4j.Level.DEBUG;
    }
    return org.apache.log4j.Level.TRACE;
  }

  /**
   * Formats the log message in response to an exception during a previous logging attempt. A
   * synthetic error message is generated from the original log data and the given exception is set
   * as the cause. The level of this record is the maximum of WARNING or the original level.
   */
  private static String formatBadLogData(RuntimeException error, LogData badLogData) {
    StringBuilder errorMsg =
            new StringBuilder("LOGGING ERROR: ").append(error.getMessage()).append('\n');
    int length = errorMsg.length();
    try {
      appendLogData(badLogData, errorMsg);
    } catch (RuntimeException e) {
      // Reset partially written buffer when an error occurs.
      errorMsg.setLength(length);
      errorMsg.append("Cannot append LogData: ").append(e);
    }
    return errorMsg.toString();
  }

  /** Appends the given {@link LogData} to the given {@link StringBuilder}. */
  private static void appendLogData(LogData data, StringBuilder out) {
    out.append("  original message: ");
    if (data.getTemplateContext() == null) {
      out.append(data.getLiteralArgument());
    } else {
      // We know that there's at least one argument to display here.
      out.append(data.getTemplateContext().getMessage());
      out.append("\n  original arguments:");
      for (Object arg : data.getArguments()) {
        out.append("\n    ").append(MessageUtils.safeToString(arg));
      }
    }
    Metadata metadata = data.getMetadata();
    if (metadata.size() > 0) {
      out.append("\n  metadata:");
      for (int n = 0; n < metadata.size(); n++) {
        out.append("\n    ");
        out.append(metadata.getKey(n).getLabel()).append(": ").append(metadata.getValue(n));
      }
    }
    out.append("\n  level: ").append(data.getLevel());
    out.append("\n  timestamp (nanos): ").append(data.getTimestampNanos());
    out.append("\n  class: ").append(data.getLogSite().getClassName());
    out.append("\n  method: ").append(data.getLogSite().getMethodName());
    out.append("\n  line number: ").append(data.getLogSite().getLineNumber());
  }

  private static final MetadataHandler<MetadataKey.KeyValueHandler> HANDLER = MetadataHandler
          .builder((MetadataHandler.ValueHandler<Object, MetadataKey.KeyValueHandler>) (key, value, kvh) -> {
            if (key.getClass().equals(LogContext.Key.TAGS.getClass())) {
              processTags(key, value, kvh);
            } else {
              // In theory a user can define a custom tag and use it as a MetadataKey. Those keys shall be treated
              // in the same way as LogContext.Key.TAGS when used as a MetadataKey.
              // Might be removed if visibility of MetadataKey#clazz changes.
              if (value instanceof Tags) {
                processTags(key, value, kvh);
              } else {
                ValueQueue.appendValues(key.getLabel(), value, kvh);
              }
            }})
          .setDefaultRepeatedHandler((key, values, kvh) -> values.forEachRemaining(v -> kvh.handle(key.getLabel(), v)))
          .build();

  private static void processTags(MetadataKey<Object> key, Object value, MetadataKey.KeyValueHandler kvh) {
    ValueQueue valueQueue = ValueQueue.appendValueToNewQueue(value);
    // Unlike single metadata (which is usually formatted as a single value), tags are always formatted as a list.
    // Given the tags: tags -> foo=[bar], it will be formatted as tags=[foo=bar].
    ValueQueue.appendValues(
            key.getLabel(),
            valueQueue.size() == 1
                    ? StreamSupport.stream(valueQueue.spliterator(), false).collect(Collectors.toList())
                    : valueQueue, kvh);
  }

  /**
   * We do not support {@code MDC.getContext()} and {@code NDC.getStack()} and we do not make any attempt to merge
   * Log4j2 context data with Flogger's context data. Instead, users should use the {@link ScopedLoggingContext}.
   *
   * Flogger's {@link ScopedLoggingContext} allows to include additional metadata and tags into logs which are
   * written from current thread. This context data will be added to the log4j event.
   */
  private static Map<String, Object> createContextMap(LogData logData) {
    MetadataProcessor metadataProcessor = MetadataProcessor.forScopeAndLogSite(
            Platform.getInjectedMetadata(), logData.getMetadata());
    // Instantiate a {@code HashMap} which can hold metadataProcessor.keyCount() elements without requiring
    // a reallocating of elements to a new backing array
    Hashtable<String, Object> mdc = new Hashtable<>(metadataProcessor.keyCount() + 1, 1);
    metadataProcessor.process(HANDLER, (key, value) ->
            mdc.put(key, ValueQueue.maybeWrap(value, mdc.get(key))));

    return mdc;
  }
}
