package com.railreach.service;

import com.railreach.dto.FeedAlert;
import com.railreach.dto.FeedAlertMailResult;
import com.railreach.dto.OperatorEnd;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class FeedAlertsTest {

    private static final int ALERT_DAYS = 14;
    private static final String RECIPIENT = "owner@example.org";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate IN_FIVE_DAYS = TODAY.plusDays(5);
    private static final LocalDate IN_A_YEAR = TODAY.plusYears(1);
    private static final int DAYS_SINCE_BOSNIA_ENDED = 300;

    @TempDir
    Path tempDir;

    private final TransitGraph graph = TransitGraph.withToday(TODAY);
    private final RecordingMailSender mailSender = new RecordingMailSender();

    /** Records the mails instead of sending them. */
    private static final class RecordingMailSender extends JavaMailSenderImpl {
        private final List<SimpleMailMessage> sent = new ArrayList<>();

        @Override
        public void send(SimpleMailMessage... simpleMessages) {
            sent.addAll(List.of(simpleMessages));
        }
    }

    @BeforeEach
    void setUp() {
        serve(feed("austria", IN_FIVE_DAYS), feed("germany", IN_A_YEAR));
    }

    @Test
    void oneMailListsEveryServedFeedWhoseTrainsEndWithinTheAlertDays() {
        LocalDate bosniaEnd = TODAY.minusDays(DAYS_SINCE_BOSNIA_ENDED);
        serve(feed("austria", IN_FIVE_DAYS), feed("bosnia", bosniaEnd), feed("germany", IN_A_YEAR));
        FeedAlerts alerts = alerts();
        alerts.setEnabled(true);

        alerts.sendDailyAlert(TODAY);

        assertThat(mailSender.sent).hasSize(1);
        SimpleMailMessage mail = mailSender.sent.getFirst();
        assertThat(mail.getTo()).containsExactly(RECIPIENT);
        assertThat(mail.getSubject()).contains("2 feed(s)");
        assertThat(mail.getText())
                .contains("bosnia: trains ended " + bosniaEnd + " (" + DAYS_SINCE_BOSNIA_ENDED + " days ago)")
                .contains("austria: trains end " + IN_FIVE_DAYS + " (in 5 days)")
                .doesNotContain("germany");
    }

    @Test
    void aMainOperatorThatStopsEarlyMakesTheFeedDue() {
        OperatorEnd early = new OperatorEnd("Early Rail", IN_FIVE_DAYS);
        serve(new FeedTimespan("austria", new FeedValidity(TODAY.minusYears(1), IN_A_YEAR), List.of(early)));
        FeedAlerts alerts = alerts();
        alerts.setEnabled(true);

        alerts.sendDailyAlert(TODAY);

        assertThat(mailSender.sent.getFirst().getText())
                .contains("austria: trains end " + IN_FIVE_DAYS + " (in 5 days); ending early: Early Rail, "
                        + "the feed runs until " + IN_A_YEAR);
    }

    @Test
    void nothingIsMailedWhileAlertsAreOff() {
        alerts().sendDailyAlert(TODAY);

        assertThat(mailSender.sent).isEmpty();
    }

    @Test
    void nothingIsMailedWhenNoFeedEndsWithinTheAlertDays() {
        FeedAlerts alerts = alerts();
        alerts.setEnabled(true);

        alerts.sendDailyAlert(IN_FIVE_DAYS.minusDays(ALERT_DAYS + 1));

        assertThat(mailSender.sent).isEmpty();
    }

    @Test
    void aPausedFeedIsLeftOutUntilItsTrainsEndOnAnotherDay() {
        FeedAlerts alerts = alerts();
        alerts.setEnabled(true);
        assertThat(alerts.pause("austria")).isPresent();

        alerts.sendDailyAlert(TODAY);
        assertThat(mailSender.sent).isEmpty();
        assertThat(alerts.status(TODAY).getFeeds()).extracting(FeedAlert::getSource, FeedAlert::isPaused)
                .containsExactly(tuple("austria", true));

        serve(feed("austria", IN_FIVE_DAYS.plusDays(1)));
        alerts.sendDailyAlert(TODAY);
        assertThat(mailSender.sent).hasSize(1);
        assertThat(mailSender.sent.getFirst().getText()).contains("austria");
    }

    @Test
    void aResumedFeedIsMailedAgain() {
        FeedAlerts alerts = alerts();
        alerts.setEnabled(true);
        alerts.pause("austria");

        alerts.resume("austria");
        alerts.sendDailyAlert(TODAY);

        assertThat(mailSender.sent).hasSize(1);
    }

    @Test
    void aFeedThatIsNotServedCannotBePaused() {
        assertThat(alerts().pause("narnia")).isEmpty();
    }

    @Test
    void theSwitchAndThePausesSurviveARestart() {
        FeedAlerts before = alerts();
        before.setEnabled(true);
        before.pause("austria");

        FeedAlerts after = alerts();

        assertThat(after.status(TODAY).isEnabled()).isTrue();
        assertThat(after.status(TODAY).getFeeds()).allMatch(FeedAlert::isPaused);
    }

    @Test
    void aTestMailIsSentWhileAlertsAreOff() {
        FeedAlertMailResult result = alerts().sendTest(TODAY);

        assertThat(result.isSent()).isTrue();
        assertThat(mailSender.sent.getFirst().getText()).contains("austria");
    }

    @Test
    void withoutAMailServerNothingIsSentAndTheTestSaysWhy() {
        FeedAlerts alerts = alerts(Optional.empty());

        FeedAlertMailResult result = alerts.sendTest(TODAY);

        assertThat(result.isSent()).isFalse();
        assertThat(result.getMessage()).contains("not configured");
        assertThat(alerts.status(TODAY).isMailConfigured()).isFalse();
    }

    private FeedAlerts alerts() {
        return alerts(Optional.of(mailSender));
    }

    /** A new instance reads the settings file, as after a restart. */
    private FeedAlerts alerts(Optional<JavaMailSender> sender) {
        FeedAlerts alerts = new FeedAlerts(graph, sender);
        ReflectionTestUtils.setField(alerts, "alertDays", ALERT_DAYS);
        ReflectionTestUtils.setField(alerts, "recipient", RECIPIENT);
        ReflectionTestUtils.setField(alerts, "sender", "");
        ReflectionTestUtils.setField(alerts, "settingsFile", tempDir.resolve("feed-alerts.json").toString());
        alerts.loadSettings();
        return alerts;
    }

    private static FeedTimespan feed(String source, LocalDate lastDay) {
        return new FeedTimespan(source, new FeedValidity(lastDay.minusYears(1), lastDay), List.of());
    }

    private void serve(FeedTimespan... feeds) {
        graph.restore(new BinaryCacheStore.BinaryCacheData(List.of(), Map.of(), List.of(), List.of(feeds),
                TimetableStore.empty()));
    }
}
