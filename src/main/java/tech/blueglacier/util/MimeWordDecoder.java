package tech.blueglacier.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.james.mime4j.codec.Base64InputStream;
import org.apache.james.mime4j.codec.DecodeMonitor;
import org.apache.james.mime4j.codec.DecoderUtil;
import org.apache.james.mime4j.codec.QuotedPrintableInputStream;
import org.apache.james.mime4j.util.CharsetUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MimeWordDecoder{
	
	private static final Log log = LogFactory.getLog(DecoderUtil.class);
	
    private static final Pattern PATTERN_ENCODED_WORD = Pattern.compile(
            "(.*?)=\\?([^\\?]+?)\\?(\\w)\\?([^\\?]+?)\\?=", Pattern.DOTALL);
	
    /**
     * Decodes a string containing encoded words as defined by RFC 2047. Encoded
     * words have the form =?charset?enc?encoded-text?= where enc is either 'Q'
     * or 'q' for quoted-printable and 'B' or 'b' for base64.
     *
     * @param body the string to decode
     * @param monitor the DecodeMonitor to be used.
     * @return the decoded string.
     * @throws IllegalArgumentException only if the DecodeMonitor strategy throws it (Strict parsing)
     */
    public static String decodeEncodedWords(String body, DecodeMonitor monitor) throws IllegalArgumentException {
    	if(body != null && !body.isEmpty()){
    		int tailIndex = 0;
    		boolean lastMatchValid = false;

    		StringBuilder sb = new StringBuilder();

    		for (Matcher matcher = PATTERN_ENCODED_WORD.matcher(body); matcher.find();) {
    			String separator = matcher.group(1);
    			String mimeCharset = matcher.group(2);
    			String encoding = matcher.group(3);
    			String encodedText = matcher.group(4);

    			mimeCharset = Common.getFallbackCharset(mimeCharset);

    			String decoded;
    			decoded = tryDecodeEncodedWord(mimeCharset, encoding, encodedText, monitor);
    			if (decoded == null) {
    				sb.append(matcher.group(0));
    			} else {
    				if (!lastMatchValid || !CharsetUtil.isWhitespace(separator)) {
    					sb.append(separator);
    				}
    				sb.append(decoded);
    			}

    			tailIndex = matcher.end();
    			lastMatchValid = decoded != null;
    		}

    		if (tailIndex == 0) {
    			return body;
    		} else {
    			sb.append(body.substring(tailIndex));
    			return sb.toString();
    		}
    	}
    	return body;
    }
    
    // return null on error
    private static String tryDecodeEncodedWord(final String mimeCharset,
            final String encoding, final String encodedText, final DecodeMonitor monitor) {
        Charset charset = CharsetUtil.lookup(mimeCharset);
        if (charset == null) {
            monitor(monitor, mimeCharset, encoding, encodedText, "leaving word encoded",
                    "Mime charser '", mimeCharset, "' doesn't have a corresponding Java charset");
            return null;
        }

        if (encodedText.length() == 0) {
            monitor(monitor, mimeCharset, encoding, encodedText, "leaving word encoded",
                    "Missing encoded text in encoded word");
            return null;
        }

        try {
            if (encoding.equalsIgnoreCase("Q")) {
                return MimeWordDecoder.decodeQ(encodedText, charset.name(), monitor);
            } else if (encoding.equalsIgnoreCase("B")) {
                return MimeWordDecoder.decodeB(encodedText, charset.name(), monitor);
            } else {
                monitor(monitor, mimeCharset, encoding, encodedText, "leaving word encoded",
                        "Warning: Unknown encoding in encoded word");
                return null;
            }
        } catch (UnsupportedEncodingException e) {
            // should not happen because of isDecodingSupported check above
            monitor(monitor, mimeCharset, encoding, encodedText, "leaving word encoded",
                    "Unsupported encoding (", e.getMessage(), ") in encoded word");
            return null;
        } catch (RuntimeException e) {
            monitor(monitor, mimeCharset, encoding, encodedText, "leaving word encoded",
                    "Could not decode (", e.getMessage(), ") encoded word");
            return null;
        }
    }
    
    private static void monitor(DecodeMonitor monitor, String mimeCharset, String encoding,
            String encodedText, String dropDesc, String... strings) throws IllegalArgumentException {
        if (monitor.isListening()) {
            String encodedWord = recombine(mimeCharset, encoding, encodedText);
            StringBuilder text = new StringBuilder();
            for (String str : strings) {
                text.append(str);
            }
            text.append(" (");
            text.append(encodedWord);
            text.append(")");
            String exceptionDesc = text.toString();
            if (monitor.warn(exceptionDesc, dropDesc))
                throw new IllegalArgumentException(text.toString());
        }
    }
    
    private static String recombine(final String mimeCharset,
            final String encoding, final String encodedText) {
        return "=?" + mimeCharset + "?" + encoding + "?" + encodedText + "?=";
    }
    
    /**
     * Decodes an encoded text encoded with the 'Q' encoding (described in
     * RFC 2047) found in a header field body.
     *
     * @param encodedText the encoded text to decode.
     * @param charset the Java charset to use.
     * @return the decoded string.
     * @throws UnsupportedEncodingException if the given Java charset isn't
     *         supported.
     */
    static String decodeQ(String encodedText, String charset, DecodeMonitor monitor)
            throws UnsupportedEncodingException {
        encodedText = replaceUnderscores(encodedText);

        byte[] decodedBytes = decodeQuotedPrintable(encodedText, monitor);
        return new String(decodedBytes, charset);
    }
    
    /**
     * Decodes an encoded text encoded with the 'B' encoding (described in
     * RFC 2047) found in a header field body.
     *
     * @param encodedText the encoded text to decode.
     * @param charset the Java charset to use.
     * @param monitor
     * @return the decoded string.
     * @throws UnsupportedEncodingException if the given Java charset isn't
     *         supported.
     */
    static String decodeB(String encodedText, String charset, DecodeMonitor monitor)
            throws UnsupportedEncodingException {
        byte[] decodedBytes = decodeBase64(encodedText, monitor);
        return new String(decodedBytes, charset);
    }
    
    /**
     * Decodes a string containing base64 encoded data.
     *
     * @param s the string to decode.
     * @param monitor
     * @return the decoded bytes.
     */
    private static byte[] decodeBase64(String s, DecodeMonitor monitor) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);

            Base64InputStream is = new Base64InputStream(
                                        new ByteArrayInputStream(bytes), monitor);

            int b;
            while ((b = is.read()) != -1) {
                baos.write(b);
            }
        } catch (IOException e) {
            // This should never happen!
            throw new IllegalStateException(e);
        }

        return baos.toByteArray();
    }
    
    // Replace _ with =20
    private static String replaceUnderscores(String str) {
        // probably faster than String#replace(CharSequence, CharSequence)

        StringBuilder sb = new StringBuilder(128);

        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '_') {
                sb.append("=20");
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }
    
    /**
     * Decodes a string containing quoted-printable encoded data.
     *
     * @param s the string to decode.
     * @return the decoded bytes.
     */
    private static byte[] decodeQuotedPrintable(String s, DecodeMonitor monitor) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);

            QuotedPrintableInputStream is = new QuotedPrintableInputStream(
                                               new ByteArrayInputStream(bytes), monitor);

            int b;
            while ((b = is.read()) != -1) {
                baos.write(b);
            }
        } catch (IOException e) {
            // This should never happen!
            throw new IllegalStateException(e);
        }

        return baos.toByteArray();
    }
    
	}
