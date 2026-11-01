package com.example.logproducer;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.logstash.logback.argument.StructuredArguments;

@Component
public class SampleLogGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SampleLogGenerator.class);

    private static final List<LogExample> LOG_EXAMPLES = List.of(
            new LogExample("APPLICATION_STARTED", "Application is ready", "ok", LogLevel.INFO),
            new LogExample("HTTP_REQUEST", "Handled incoming HTTP request", "ok", LogLevel.INFO),
            new LogExample("USER_LOGIN", "User login completed", "ok", LogLevel.INFO),
            new LogExample("PAYMENT_PROCESSED", "Payment processed", "ok", LogLevel.INFO),
            new LogExample("DATABASE_QUERY", "Database query completed", "ok", LogLevel.INFO),
            new LogExample("CACHE_MISS", "Cache miss; loading from database", "miss", LogLevel.INFO),
            new LogExample("VALIDATION_WARNING", "Request validation failed", "warning", LogLevel.WARN),
            new LogExample("RATE_LIMIT_WARNING", "Request rate limit nearly reached", "warning", LogLevel.WARN),
            new LogExample("EXTERNAL_SERVICE_ERROR", "External service request failed", "error", LogLevel.ERROR),
            new LogExample("RETRY_ATTEMPT", "Retrying failed operation", "retrying", LogLevel.WARN));

    private final AtomicLong eventCounter = new AtomicLong();

    @Scheduled(fixedRate = 10_000)
    public void generateLogEvent() {
        for (LogExample example : LOG_EXAMPLES) {
            long eventId = eventCounter.incrementAndGet();
            switch (example.level()) {
                case INFO -> logger.info(
                        example.message(),
                        StructuredArguments.keyValue("eventId", eventId),
                        StructuredArguments.keyValue("type", example.type()),
                        StructuredArguments.keyValue("status", example.status()));
                case WARN -> logger.warn(
                        example.message(),
                        StructuredArguments.keyValue("eventId", eventId),
                        StructuredArguments.keyValue("type", example.type()),
                        StructuredArguments.keyValue("status", example.status()));
                case ERROR -> logger.error(
                        example.message(),
                        StructuredArguments.keyValue("eventId", eventId),
                        StructuredArguments.keyValue("type", example.type()),
                        StructuredArguments.keyValue("status", example.status()));
            }
        }
    }

    private enum LogLevel {
        INFO,
        WARN,
        ERROR
    }

    private record LogExample(String type, String message, String status, LogLevel level) {
    }
}
