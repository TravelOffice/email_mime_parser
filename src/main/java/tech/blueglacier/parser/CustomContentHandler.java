package tech.blueglacier.parser;

import tech.blueglacier.email.Email;
import tech.blueglacier.email.EmailMessageType;
import tech.blueglacier.email.EmailMessageType.EmailMessageTypeHierarchy;
import tech.blueglacier.email.MultipartType;
import org.apache.james.mime4j.MimeException;
import org.apache.james.mime4j.parser.AbstractContentHandler;
import org.apache.james.mime4j.stream.BodyDescriptor;
import org.apache.james.mime4j.stream.Field;

import java.io.IOException;
import java.io.InputStream;

public class CustomContentHandler extends AbstractContentHandler {

    private final Email email;

    public CustomContentHandler() {
        this.email = new Email();
    }

    public Email getEmail() {
        return email;
    }

    @Override
    public void field(Field field) {
        email.getHeader().addField(field);
    }

    @Override
    public void body(BodyDescriptor bd, InputStream is) {
        // Gracefully switching off the case of email attached within an email
        if (email.getMessageStack().peek().getEmailMessageTypeHierarchy() == EmailMessageTypeHierarchy.parent) {
            email.fillEmailContents(bd, is);
        }
    }

    @Override
    public void startMessage() {
        if (email.getMessageStack().empty()) {
            email.getMessageStack().push(new EmailMessageType(EmailMessageTypeHierarchy.parent));
        } else {
            email.getMessageStack().push(new EmailMessageType(EmailMessageTypeHierarchy.child));
        }
    }

    @Override
    public void endMessage() {
        if (email.getMessageStack().peek().getEmailMessageTypeHierarchy() == EmailMessageTypeHierarchy.parent) {
            email.reArrangeEmail();
        }
        email.getMessageStack().pop();
    }

    public void endMultipart() {
        email.getMultipartStack().pop();
    }

    public void startMultipart(BodyDescriptor bd) {
        email.getMultipartStack().push(new MultipartType(bd));
    }
}
