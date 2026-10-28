package com.admin;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.util.FileSize;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class LoggingConfigurationTest {
    private Path logDir;

    private LoggerContext configure(boolean smallFiles) throws Exception {
        // Logback 1.2 treats the whole path as a regex; Windows sandbox temp paths
        // contain braces. Use a project-local directory without regex metacharacters.
        logDir = Files.createTempDirectory(Path.of("target"), "logback-smoke-").toAbsolutePath();
        String xml = Files.readString(Path.of("src/main/resources/logback-spring.xml"));
        // Replace only Spring's directory lookup for this isolated Logback context.
        xml = xml.replaceAll("<springProperty[^>]*/>", "")
                .replace("scan=\"true\"", "scan=\"false\"");
        if (smallFiles) xml = xml.replace("100MB", "4KB");
        LoggerContext context = new LoggerContext();
        context.putProperty("logDir", logDir.toString().replace('\\', '/'));
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        context.getLogger("ROOT").detachAppender("console");
        assertTrue(context.getStatusManager().getCopyOfStatusList().stream()
                .noneMatch(s -> s.getLevel() == Status.ERROR),
                () -> context.getStatusManager().getCopyOfStatusList().toString());
        return context;
    }

    @Test
    void productionPolicyStartsWithRequestedLimits() throws Exception {
        LoggerContext context = configure(false);
        try {
            RollingFileAppender<ILoggingEvent> appender =
                    (RollingFileAppender<ILoggingEvent>) context.getLogger("ROOT").getAppender("logFile");
            assertTrue(appender.isStarted());
            SizeAndTimeBasedRollingPolicy<?> policy =
                    (SizeAndTimeBasedRollingPolicy<?>) appender.getRollingPolicy();
            assertEquals(30, policy.getMaxHistory());
            assertTrue(policy.isCleanHistoryOnStart());
            assertEquals(FileSize.valueOf("100MB").getSize(),
                    ((FileSize) ReflectionTestUtils.getField(policy, "maxFileSize")).getSize());
            assertEquals(FileSize.valueOf("1GB").getSize(),
                    ((FileSize) ReflectionTestUtils.getField(policy, "totalSizeCap")).getSize());
            assertFalse(context.getLogger("com.admin.controller.FlowController").isDebugEnabled());
        } finally { context.stop(); }
    }

    @Test
    void sizeRolloverCompressesArchivesWithoutLosingMessages() throws Exception {
        LoggerContext context = configure(true);
        var logger = context.getLogger("com.admin.logging.smoke");
        logger.debug("DEBUG_SHOULD_NOT_BE_WRITTEN");
        for (int i = 0; i < 128; i++) logger.info("EVENT_{} {}", i, "x".repeat(512));
        // Compression tasks run concurrently. Logback 1.2's stop only awaits its
        // most recent Future, so first let every archive finish before shutdown.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!allMessagesPresent() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(allMessagesPresent(), () -> context.getStatusManager().getCopyOfStatusList().toString());
        context.stop();
        StringBuilder content = new StringBuilder();
        int archives = 0;
        try (var files = Files.list(logDir)) {
            for (Path file : files.toList()) {
                if (file.toString().endsWith(".gz")) {
                    archives++;
                    try (var in = new GZIPInputStream(Files.newInputStream(file))) {
                        content.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                } else content.append(Files.readString(file));
            }
        }
        assertTrue(archives > 0, "File-size limit should trigger real gzip rollover");
        assertTrue(Files.exists(logDir.resolve("application.log")));
        assertFalse(content.toString().contains("DEBUG_SHOULD_NOT_BE_WRITTEN"));
        for (int i = 0; i < 128; i++) assertTrue(content.toString().contains("EVENT_" + i + " "));
    }

    private boolean allMessagesPresent() throws Exception {
        StringBuilder content = new StringBuilder();
        try (var files = Files.list(logDir)) {
            for (Path file : files.toList()) {
                if (file.toString().endsWith(".gz")) {
                    try (var in = new GZIPInputStream(Files.newInputStream(file))) {
                        content.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    } catch (java.io.IOException writingArchive) { return false; }
                } else if (file.getFileName().toString().equals("application.log")) {
                    content.append(Files.readString(file));
                }
            }
        }
        for (int i = 0; i < 128; i++) if (!content.toString().contains("EVENT_" + i + " ")) return false;
        return true;
    }
}
