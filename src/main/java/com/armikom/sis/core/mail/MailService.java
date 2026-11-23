package com.armikom.sis.core.mail;

import com.armikom.sis.config.AppProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plain-text mail in the background: EWS when {@code EWS_URL} is set, SMTP when {@code SMTP_HOST} is set,
 * otherwise the message is only logged (development).
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final EwsMailer ews;
    private final String smtpHost;
    private final String smtpUser;
    private final String from;
    private final ExecutorService background = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "mail");
        t.setDaemon(true);
        return t;
    });

    public MailService(ObjectProvider<JavaMailSender> mailSender, EwsMailer ews, AppProperties props,
                       @Value("${spring.mail.host:}") String smtpHost,
                       @Value("${spring.mail.username:}") String smtpUser) {
        this.mailSender = mailSender;
        this.ews = ews;
        this.smtpHost = smtpHost == null ? "" : smtpHost;
        this.smtpUser = smtpUser == null ? "" : smtpUser;
        this.from = props.mail() != null && props.mail().from() != null && !props.mail().from().isBlank()
                ? props.mail().from() : this.smtpUser;
    }

    /** True when mail really leaves the server (EWS or SMTP). */
    public boolean isConfigured() {
        return ews.isConfigured() || (!smtpHost.isBlank() && mailSender.getIfAvailable() != null);
    }

    /**
     * Queues the message. {@code logLine} is what is written to the log instead when mail is not configured
     * (for login links: the link itself, so development works without a mail server).
     */
    public void sendAsync(String to, String subject, String text, String logLine) {
        background.submit(() -> send(to, subject, text, logLine));
    }

    /**
     * Queues a message with an HTML body and a plain-text alternative. EWS sends the text only; without a mail
     * server the subject and recipient are logged.
     */
    public void sendHtmlAsync(String to, String subject, String text, String html) {
        background.submit(() -> {
            try {
                if (ews.isConfigured()) {
                    ews.send(to, subject, text);
                    return;
                }
                JavaMailSender sender = mailSender.getIfAvailable();
                if (sender == null || smtpHost.isBlank()) {
                    log.info("Mail is not configured. {} for {}", subject, to);
                    return;
                }
                MimeMessage msg = sender.createMimeMessage();
                MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
                h.setFrom(from);
                h.setTo(to);
                h.setSubject(subject);
                h.setText(text, html);
                sender.send(msg);
            } catch (Exception e) {
                log.error("Could not send mail to {}", to, e);
            }
        });
    }

    void send(String to, String subject, String text, String logLine) {
        try {
            if (ews.isConfigured()) {
                ews.send(to, subject, text);
                return;
            }
            JavaMailSender sender = mailSender.getIfAvailable();
            if (sender == null || smtpHost.isBlank()) {
                log.info("Mail is not configured. {} for {}: {}", subject, to, logLine);
                return;
            }
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(text);
            sender.send(msg);
        } catch (Exception e) {
            log.error("Could not send mail to {}", to, e);
        }
    }

    @PreDestroy
    void shutdown() {
        background.shutdown();
    }
}
