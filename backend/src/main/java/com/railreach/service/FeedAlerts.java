package com.railreach.service;

import com.railreach.dto.FeedAlert;
import com.railreach.dto.FeedAlertMailResult;
import com.railreach.dto.FeedAlertStatus;
import com.railreach.dto.OperatorEnd;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * One mail a day about the served feeds whose trains end within {@code railreach.feeds.alerts.days} (F29). It reads
 * the served data ({@link TransitGraph#feedTimespans}), not the zips, so it works on a server without them. Whether
 * alerts are on and which feeds are paused is kept in a small JSON file, so both survive a restart. A pause holds
 * until the feed's trains end on another day, i.e. until new data for it is served.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FeedAlerts {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TransitGraph graph;
    // Only there when spring.mail.host is set
    private final Optional<JavaMailSender> mailSender;

    @Value("${railreach.feeds.alerts.days:14}")
    private int alertDays;

    @Value("${railreach.feeds.alerts.to:}")
    private String recipient;

    @Value("${railreach.feeds.alerts.from:}")
    private String sender;

    @Value("${railreach.feeds.alerts.settings-file:data/feed-alerts.json}")
    private String settingsFile;

    private Settings settings = Settings.OFF;

    /** paused: source -> the day its trains ended when it was paused. */
    record Settings(boolean enabled, Map<String, LocalDate> paused) {
        static final Settings OFF = new Settings(false, Map.of());

        Settings {
            paused = paused == null ? Map.of() : Map.copyOf(paused);
        }
    }

    @PostConstruct
    synchronized void loadSettings() {
        Path file = settingsPath();
        if (!Files.exists(file)) return;
        try {
            settings = JSON.readValue(file.toFile(), Settings.class);
        } catch (JacksonException e) {
            log.warn("Feed alert settings {} not readable; alerts are off", file, e);
        }
    }

    public FeedAlertStatus status() {
        return status(LocalDate.now());
    }

    synchronized FeedAlertStatus status(LocalDate today) {
        return FeedAlertStatus.builder()
                .enabled(settings.enabled())
                .mailConfigured(mailConfigured())
                .recipient(recipient.isBlank() ? null : recipient)
                .alertDays(alertDays)
                .feeds(due(today))
                .build();
    }

    public synchronized FeedAlertStatus setEnabled(boolean enabled) {
        save(new Settings(enabled, settings.paused()));
        log.info("Feed alert mail turned {}", enabled ? "on" : "off");
        return status();
    }

    /** Leaves the feed out of the alert mail until its trains end on another day. Empty if it is not served. */
    public synchronized Optional<FeedAlertStatus> pause(String source) {
        Optional<FeedTimespan> feed = graph.feedTimespans().stream()
                .filter(timespan -> timespan.source().equals(source))
                .findFirst();
        feed.ifPresent(timespan -> {
            Map<String, LocalDate> paused = new TreeMap<>(settings.paused());
            paused.put(source, timespan.trainsEnd());
            save(new Settings(settings.enabled(), paused));
        });
        return feed.map(timespan -> status());
    }

    public synchronized FeedAlertStatus resume(String source) {
        Map<String, LocalDate> paused = new TreeMap<>(settings.paused());
        paused.remove(source);
        save(new Settings(settings.enabled(), paused));
        return status();
    }

    @Scheduled(cron = "${railreach.feeds.alerts.cron:0 0 8 * * *}")
    public void sendDailyAlert() {
        sendDailyAlert(LocalDate.now());
    }

    synchronized void sendDailyAlert(LocalDate today) {
        if (!settings.enabled()) return;
        List<FeedAlert> feeds = unpaused(today);
        if (feeds.isEmpty()) {
            log.info("Feed alert: no unpaused feed ends within {} days", alertDays);
            return;
        }
        FeedAlertMailResult result = send(feeds);
        if (!result.isSent()) log.warn("Feed alert not mailed: {}", result.getMessage());
    }

    /** Mails what the daily alert would, now and whether alerts are on or off, so the mail setup can be checked. */
    public FeedAlertMailResult sendTest() {
        return sendTest(LocalDate.now());
    }

    synchronized FeedAlertMailResult sendTest(LocalDate today) {
        return send(unpaused(today));
    }

    private List<FeedAlert> due(LocalDate today) {
        LocalDate limit = today.plusDays(alertDays);
        return graph.feedTimespans().stream()
                .filter(timespan -> !timespan.trainsEnd().isAfter(limit))
                .sorted(Comparator.comparing(FeedTimespan::trainsEnd).thenComparing(FeedTimespan::source))
                .map(timespan -> FeedAlert.builder()
                        .source(timespan.source())
                        .lastServiceDate(timespan.validity().lastDay())
                        .trainsEnd(timespan.trainsEnd())
                        .daysLeft(ChronoUnit.DAYS.between(today, timespan.trainsEnd()))
                        .earlyEndingOperators(timespan.earlyEndingOperators())
                        .paused(timespan.trainsEnd().equals(settings.paused().get(timespan.source())))
                        .build())
                .toList();
    }

    private List<FeedAlert> unpaused(LocalDate today) {
        return due(today).stream().filter(alert -> !alert.isPaused()).toList();
    }

    private FeedAlertMailResult send(List<FeedAlert> feeds) {
        if (!mailConfigured()) {
            return new FeedAlertMailResult(false,
                    "Mail is not configured: set spring.mail.host and railreach.feeds.alerts.to");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        if (!sender.isBlank()) message.setFrom(sender);
        message.setTo(recipient);
        message.setSubject(feeds.isEmpty()
                ? String.format("RailReach: no feed ends within %d days", alertDays)
                : String.format("RailReach: %d feed(s) end within %d days", feeds.size(), alertDays));
        message.setText(text(feeds));
        try {
            mailSender.orElseThrow().send(message);
        } catch (MailException e) {
            log.warn("Feed alert mail to {} failed", recipient, e);
            return new FeedAlertMailResult(false, "Sending failed: " + e.getMessage());
        }
        log.info("Feed alert mailed to {}: {} feed(s)", recipient, feeds.size());
        return new FeedAlertMailResult(true, "Sent to " + recipient);
    }

    private String text(List<FeedAlert> feeds) {
        if (feeds.isEmpty()) {
            return String.format("No served feed, apart from paused ones, ends within %d days.%n", alertDays);
        }
        String lines = feeds.stream().map(FeedAlerts::line).collect(Collectors.joining(System.lineSeparator()));
        return String.format("These served feeds end within %d days:%n%n%s%n%n"
                + "Download new data, rebuild the store and upload it, or pause a feed in the admin panel "
                + "(Alerts tab).%n", alertDays, lines);
    }

    private static String line(FeedAlert alert) {
        long days = alert.getDaysLeft();
        String when = days < 0
                ? String.format("trains ended %s (%d days ago)", alert.getTrainsEnd(), -days)
                : String.format("trains end %s (in %d days)", alert.getTrainsEnd(), days);
        if (alert.getEarlyEndingOperators().isEmpty()) return String.format("- %s: %s", alert.getSource(), when);
        String operators = alert.getEarlyEndingOperators().stream()
                .map(OperatorEnd::getOperator)
                .collect(Collectors.joining(", "));
        return String.format("- %s: %s; ending early: %s, the feed runs until %s", alert.getSource(), when,
                operators, alert.getLastServiceDate());
    }

    private boolean mailConfigured() {
        return mailSender.isPresent() && !recipient.isBlank();
    }

    private void save(Settings updated) {
        Path file = settingsPath();
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        JSON.writeValue(file.toFile(), updated);
        settings = updated;
    }

    private Path settingsPath() {
        return Path.of(settingsFile).toAbsolutePath();
    }
}
