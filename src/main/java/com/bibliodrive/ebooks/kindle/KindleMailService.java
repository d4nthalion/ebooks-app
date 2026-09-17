package com.bibliodrive.ebooks.kindle;

import com.bibliodrive.ebooks.catalog.Book;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * Envia un libro como adjunto a una direccion de Kindle (Send to Kindle by email).
 * Amazon solo acepta el adjunto si el remitente esta en la lista de correos
 * aprobados de la cuenta de Amazon del destinatario.
 */
@Service
public class KindleMailService {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public KindleMailService(JavaMailSender mailSender, @Value("${app.mail.from}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    public void sendToKindle(String kindleEmail, Book book, File file) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true);
        helper.setFrom(fromAddress);
        helper.setTo(kindleEmail);
        helper.setSubject(book.title());
        helper.setText("Enviado desde Biblioteca.");
        helper.addAttachment(book.fileName(), file);
        mailSender.send(message);
    }
}
