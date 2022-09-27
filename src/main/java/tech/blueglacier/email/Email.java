package tech.blueglacier.email;

import com.google.common.net.MediaType;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.james.mime4j.codec.DecodeMonitor;
import org.apache.james.mime4j.dom.Header;
import org.apache.james.mime4j.message.HeaderImpl;
import org.apache.james.mime4j.message.MaximalBodyDescriptor;
import org.apache.james.mime4j.stream.BodyDescriptor;
import org.apache.james.mime4j.stream.Field;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.blueglacier.configuration.AppConfig;
import tech.blueglacier.util.Common;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * Contains core logic to recreate a tech.blueglacier.email as seen and perceived by a general user.
 */
public class Email {

    private final Header header;
    private final ArrayList<Attachment> attachments;
    private Attachment plainTextEmailBody;
    private Attachment htmlEmailBody;
    private Attachment calendarBody;
    private boolean attachmentReplacedInHtmlBody;
    private final Stack<MultipartType> multipartStack;

    //Added to distinguish between tech.blueglacier.email attached within another tech.blueglacier.email case
    private final Stack<EmailMessageType> emailMessageStack;
    private int decodedEmailSize;
    private int emailSize;

    public int getEmailSize() {
        return emailSize;
    }

    public int getDecodedEmailSize() {
        return decodedEmailSize;
    }

    final Logger LOGGER = LoggerFactory.getLogger(Email.class);

    public Email() {
        this.header = new HeaderImpl();
        this.attachments = new ArrayList<>();
        this.attachmentReplacedInHtmlBody = false;
        this.multipartStack = new Stack<>();
        this.emailMessageStack = new Stack<>();
        this.decodedEmailSize = 0;
        this.emailSize = 0;
    }

    public Header getHeader() {
        return header;
    }

    public Attachment getPlainTextEmailBody() {
        return plainTextEmailBody;
    }

    public void fillEmailContents(BodyDescriptor bd, InputStream is) {
        try {
            if (addPlainTextEmailBody(bd, is)) {
                return;
            }
            if (addHTMLEmailBody(bd, is)) {
                return;
            }
            if (addCalendar(bd, is)) {
                return;
            }
            addAttachments(bd, is);
        } catch (IOException e) {
            LOGGER.error("fillEmailContents error " + e.getMessage());
        }
    }

    private boolean addCalendar(BodyDescriptor bd, InputStream is) {
        boolean isBodySet = false;
        if (calendarBody == null) {
            if (isCalendarBody(bd)) {
                calendarBody = new CalendarBody(bd, is);
                isBodySet = true;
            }
        }

        return isBodySet;
    }

    private boolean shouldIgnore(BodyDescriptor bd, InputStream is) {
        String attachmentName = Common.getAttachmentName(bd);
        return (attachmentName == null);
    }

    public Stack<MultipartType> getMultipartStack() {
        return multipartStack;
    }

    public Stack<EmailMessageType> getMessageStack() {
        return emailMessageStack;
    }

    public String getEmailSubject() {
        Field subjectField = header.getField("Subject");
        if (subjectField != null) {
            CustomUnstructuredFieldImpl decodedSubjectField = new CustomUnstructuredFieldImpl(subjectField, DecodeMonitor.SILENT);
            return decodedSubjectField.getValue();
        }
        return null;
    }

    public String getToEmailHeaderValue() {
        Field to = header.getField("To");
        if (to != null) {
            return to.getBody();
        }
        return null;
    }

    public String getCCEmailHeaderValue() {
        Field cc = header.getField("Cc");
        if (cc != null) {
            return cc.getBody();
        }
        return null;
    }

    public String getFromEmailHeaderValue() {
        Field from = header.getField("From");
        if (from != null) {
            return from.getBody();
        }
        return null;
    }

    public String getBccEmailHeaderValue() {
        Field from = header.getField("Bcc");
        if (from != null) {
            return from.getBody();
        }
        return null;
    }

    private void addAttachments(BodyDescriptor bd, InputStream is) {
        attachments.add(new EmailAttachment(bd, is));
    }

    private void addAttachments(Attachment attachment) {
        attachments.add(attachment);
    }

    private boolean addHTMLEmailBody(BodyDescriptor bd, InputStream is) throws IOException {
        boolean isBodySet = false;
        if (htmlEmailBody == null) {
            if (isHTMLBody(bd)) {
                htmlEmailBody = new HtmlEmailBody(bd, is);
                isBodySet = true;
            }
        } else {
            if (isHTMLBody(bd)) {
                if (multipartStack.peek().getBodyDescriptor().getMimeType().equalsIgnoreCase("multipart/mixed")) {
                    InputStream mainInputStream;
                    mainInputStream = concatInputStream(is, htmlEmailBody.getIs());
                    htmlEmailBody.setIs(mainInputStream);
                } else {
                    addAttachments(new HtmlEmailBody(bd, is));
                }
                isBodySet = true;
            }
        }
        return isBodySet;
    }

    private boolean isHTMLBody(BodyDescriptor emailHTMLBodyDescriptor) {
        String bodyName = Common.getAttachmentName(emailHTMLBodyDescriptor);
        return (emailHTMLBodyDescriptor.getMimeType().equalsIgnoreCase("text/html") && bodyName == null);
    }

    private boolean isCalendarBody(BodyDescriptor emailCalendarBodyDescriptor) {
        String bodyName = Common.getAttachmentName(emailCalendarBodyDescriptor);
        return (emailCalendarBodyDescriptor.getMimeType().equalsIgnoreCase("text/calendar") && bodyName == null);
    }

    private boolean addPlainTextEmailBody(BodyDescriptor bd, InputStream is) {
        boolean isBodySet = false;
        if (plainTextEmailBody == null) {
            if (isPlainTextBody(bd)) {
                plainTextEmailBody = new PlainTextEmailBody(bd, is);
                isBodySet = true;
            }
        } else {
            if (isPlainTextBody(bd)) {
                if (multipartStack.peek().getBodyDescriptor().getMimeType().equalsIgnoreCase("multipart/mixed")) {
                    InputStream mainInputStream;
                    mainInputStream = concatInputStream(is, plainTextEmailBody.getIs());
                    plainTextEmailBody.setIs(mainInputStream);
                } else {
                    addAttachments(new PlainTextEmailBody(bd, is));
                }
                isBodySet = true;
            }
        }
        return isBodySet;
    }

    private void addPlainTextFromHtml() {
        try {
            if (htmlEmailBody != null && plainTextEmailBody == null) {
                Document doc = Jsoup.parse(htmlEmailBody.getIs(), null, "");
                InputStream is = new ByteArrayInputStream(doc.text().getBytes());
                plainTextEmailBody = new PlainTextEmailBody(htmlEmailBody.bd, is);
            }
        } catch (IOException e) {
            LOGGER.error("addPlainTextFromHtml error " + e.getMessage());
        }

    }

    private boolean isPlainTextBody(BodyDescriptor emailPlainBodyDescriptor) {
        String bodyName = Common.getAttachmentName(emailPlainBodyDescriptor);
        return (emailPlainBodyDescriptor.getMimeType().equalsIgnoreCase("text/plain") && bodyName == null);
    }

    public List<Attachment> getAttachments() {
        return attachments;
    }

    public Attachment getHTMLEmailBody() {
        return htmlEmailBody;
    }

    public Attachment getCalendarBody() {
        return calendarBody;
    }

    public void reArrangeEmail() {
        decodedEmailSize = setEmailSize();
        addPlainTextFromHtml();
        replaceInlineImageAttachmentsInHtmlBody();
        removeUnidentifiedMimePartsForAttachment();
        emailSize = setEmailSize();
    }

    private int setEmailSize() {
        int emailSize = 0;

        if (getHTMLEmailBody() != null) {
            emailSize += getHTMLEmailBody().getAttachmentSize();
        }
        if (getPlainTextEmailBody() != null) {
            emailSize += getPlainTextEmailBody().getAttachmentSize();
        }

        if (getCalendarBody() != null) {
            emailSize += getCalendarBody().getAttachmentSize();
        }

        for (Attachment attachment : getAttachments()) {
            emailSize += attachment.getAttachmentSize();
        }
        return emailSize;
    }

    private void removeUnidentifiedMimePartsForAttachment() {
        List<Attachment> removeList = new ArrayList<>();
        for (Attachment attachment : attachments) {
            if (shouldIgnore(attachment.bd, attachment.getIs())) {
                removeList.add(attachment);
            }
        }
        removeAttachments(removeList);
    }

    private void replaceInlineImageAttachmentsInHtmlBody() {
        if (htmlEmailBody != null) {
            String strHTMLBody = getHtmlBodyString();

            List<Attachment> removalList = new ArrayList<>();

            for (Attachment attachment : attachments) {
                if (isImage(attachment)) {
                    String imageMimeType = getImageMimeType(attachment);
                    String contentId = getAttachmentContentID(attachment);
                    strHTMLBody = replaceAttachmentInHtmlBody(strHTMLBody, removalList, attachment, contentId, imageMimeType);
                }
            }

            removeAttachments(removalList);
            resetRecreatedHtmlBody(strHTMLBody);
        }
    }

    private String replaceAttachmentInHtmlBody(String strHTMLBody,
                                               List<Attachment> removalList, Attachment attachment,
                                               String contentId, String imageMimeType) {
        if (strHTMLBody.contains("cid:" + contentId)) {
            String base64EncodedAttachment;
            try {
                base64EncodedAttachment = Base64.encodeBase64String(IOUtils.toByteArray(attachment.getIs()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            strHTMLBody = strHTMLBody.replace("cid:" + contentId, "data:" + imageMimeType + ";base64," + base64EncodedAttachment);
            removalList.add(attachment);
            attachmentReplacedInHtmlBody = true;
        }
        return strHTMLBody;
    }

    private boolean isImage(Attachment attachment) {
        return (attachment.getBd().getMediaType().equalsIgnoreCase("image")) || AppConfig.getInstance().isImageFormat(attachment.getAttachmentName());
    }

    private String getImageMimeType(Attachment attachment) {
        String imageMimeType = attachment.getBd().getMimeType();
        if (!isValidImageMimeType(imageMimeType)) {
            imageMimeType = StringUtils.EMPTY;
        }
        return imageMimeType;
    }

    private boolean isValidImageMimeType(String imageMimeType) {
        // Here 'MediaType' of Google Guava library is 'MimeType' of Apache James mime4j
        MediaType mediaType = null;
        try {
            mediaType = MediaType.parse(imageMimeType);
        } catch (IllegalArgumentException e) {
            LOGGER.error(e.getMessage());
        }
        return (mediaType != null);
    }

    public boolean isAttachmentReplacedInHtmlBody() {
        return attachmentReplacedInHtmlBody;
    }

    private void resetRecreatedHtmlBody(String strHTMLBody) {
        try {
            htmlEmailBody.setIs(new ByteArrayInputStream(strHTMLBody.getBytes(getCharSet())));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private void removeAttachments(List<Attachment> removalList) {
        attachments.removeAll(removalList);
    }

    private String getAttachmentContentID(Attachment attachment) {
        String contentId = ((MaximalBodyDescriptor) attachment.getBd()).getContentId();
        contentId = stripContentID(contentId);
        return contentId;
    }

    private String stripContentID(String contentId) {
        contentId = StringUtils.stripStart(contentId, "<");
        contentId = StringUtils.stripEnd(contentId, ">");
        return contentId;
    }

    private String getHtmlBodyString() {
        String strHTMLBody;
        try {
            String charSet = getCharSet();
            strHTMLBody = convertStreamToString(htmlEmailBody.getIs(), charSet);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return strHTMLBody;
    }

    private String getCharSet() {
        return Common.getFallbackCharset(htmlEmailBody.getBd().getCharset());
    }


    private String convertStreamToString(InputStream is, String charSet) throws IOException {
        if (is != null) {
            return IOUtils.toString(is, charSet);
        } else {
            return "";
        }
    }

    private InputStream concatInputStream(InputStream source, InputStream destination) {
        return new SequenceInputStream(destination, source);
    }
}
