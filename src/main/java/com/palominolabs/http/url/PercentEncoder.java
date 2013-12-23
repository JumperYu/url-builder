/*
 * Copyright (c) 2012 Palomino Labs, Inc.
 */

package com.palominolabs.http.url;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.MalformedInputException;
import java.nio.charset.UnmappableCharacterException;
import java.util.BitSet;

import static java.lang.Character.isHighSurrogate;
import static java.lang.Character.isLowSurrogate;

/**
 * Encodes unsafe characters as a sequence of %XX hex-encoded bytes.
 *
 * This is typically done when encoding components of URLs. See {@link UrlPercentEncoders} for pre-configured
 * PercentEncoder instances.
 */
@NotThreadSafe
public final class PercentEncoder {

    /**
     * Amount to add to a lowercase ascii alpha char to make it an uppercase. Used for hex encoding.
     */
    private static final int UPPER_CASE_DIFF = 'a' - 'A';

    private final BitSet safeChars;
    private final CharsetEncoder encoder;
    /**
     * Pre-allocate a string handler to make the common case of encoding to a string faster
     */
    private final StringBuilderPercentEncoderHandler stringHandler = new StringBuilderPercentEncoderHandler();
    private final ByteBuffer byteBuffer;
    private final CharBuffer charBuffer;

    /**
     * @param safeChars      the set of chars to NOT encode, stored as a bitset with the int positions corresponding to
     *                       those chars set to true. Treated as read only.
     * @param charsetEncoder charset encoder to encode characters with. Make sure to not re-use CharsetEncoder instances
     *                       across threads.
     */
    public PercentEncoder(@Nonnull BitSet safeChars, @Nonnull CharsetEncoder charsetEncoder) {
        this.safeChars = safeChars;
        this.encoder = charsetEncoder;

        // why is this a float? sigh.
        int maxBytesPerChar = 1 + (int) encoder.maxBytesPerChar();
        // need to handle surrogate pairs, so need to be able to handle 2 chars worth of stuff at once
        int minEncodeLoopsPerBuf = 16;
        byteBuffer = ByteBuffer.allocate(maxBytesPerChar * 2 * minEncodeLoopsPerBuf);
        charBuffer = CharBuffer.allocate(2 * minEncodeLoopsPerBuf);
    }

    public void encode(@Nonnull CharSequence input, @Nonnull PercentEncoderHandler handler) throws
        MalformedInputException, UnmappableCharacterException {

        charBuffer.clear();

        for (int i = 0; i < input.length(); i++) {

            char c = input.charAt(i);

            if (safeChars.get(c)) {
                if (charBuffer.position() > 0) {
                    addEncodedChars(handler);
                }
                handler.onEncodedChar(c);
                continue;
            }

            // not a safe char
            charBuffer.append(c);
            if (isHighSurrogate(c)) {
                if (input.length() > i + 1) {
                    // get the low surrogate as well
                    char lowSurrogate = input.charAt(i + 1);
                    if (isLowSurrogate(lowSurrogate)) {
                        charBuffer.append(lowSurrogate);
                        i++;
                    } else {
                        throw new IllegalArgumentException(
                            "Invalid UTF-16: Char " + (i) + " is a high surrogate (\\u" + Integer
                                .toHexString(c) + "), but char " + (i + 1) + " is not a low surrogate (\\u" + Integer
                                .toHexString(lowSurrogate) + ")");
                    }
                } else {
                    throw new IllegalArgumentException(
                        "Invalid UTF-16: The last character in the input string was a high surrogate (\\u" + Integer
                            .toHexString(c) + ")");
                }
            }

            if (charBuffer.remaining() < 2) {
                // flush if we could fill up next loop
                addEncodedChars(handler);
            }
        }

        addEncodedChars(handler);
    }

    /**
     * @param input input string
     * @return the input string with every character that's not in safeChars turned into its byte representation via the
     * instance's encoder and then percent-encoded
     * @throws MalformedInputException      if encoder is configured to report errors and malformed input is detected
     * @throws UnmappableCharacterException if encoder is configured to report errors and an unmappable character is
     *                                      detected
     */
    @Nonnull
    public String encode(@Nonnull CharSequence input) throws MalformedInputException, UnmappableCharacterException {
        stringHandler.reset();
        stringHandler.ensureCapacity(input.length());
        encode(input, stringHandler);
        return stringHandler.getContents();
    }

    /**
     * Encode charBuffer to bytes as per charsetEncoder, then percent-encode those bytes into output.
     *
     * Side effects: charBuffer will be read from and cleared. byteBuffer will be cleared and written to.
     *
     * @param handler where the encoded versions of the contents of charBuffer will be written
     */
    private void addEncodedChars(PercentEncoderHandler handler) throws MalformedInputException,
        UnmappableCharacterException {
        // need to read from the char buffer, which was most recently written to
        charBuffer.flip();

        byteBuffer.clear();

        encoder.reset();
        CoderResult result = encoder.encode(charBuffer, byteBuffer, true);
        checkResult(result);
        result = encoder.flush(byteBuffer);
        checkResult(result);

        // read contents of bytebuffer
        byteBuffer.flip();

        while (byteBuffer.hasRemaining()) {
            byte b = byteBuffer.get();

            int msbits = (b >> 4) & 0xF;
            int lsbits = b & 0xF;

            char msbitsChar = Character.forDigit(msbits, 16);
            char lsbitsChar = Character.forDigit(lsbits, 16);

            handler.onEncodedChar('%');
            handler.onEncodedChar(capitalizeIfLetter(msbitsChar));
            handler.onEncodedChar(capitalizeIfLetter(lsbitsChar));
        }

        charBuffer.clear();
    }

    /**
     * @param result result to check
     * @throws IllegalStateException        if result is overflow
     * @throws MalformedInputException      if result represents malformed input
     * @throws UnmappableCharacterException if result represents an unmappable character
     */
    private static void checkResult(CoderResult result) throws MalformedInputException, UnmappableCharacterException {
        if (result.isOverflow()) {
            throw new IllegalStateException("Byte buffer overflow; this should not happen.");
        }
        if (result.isMalformed()) {
            throw new MalformedInputException(result.length());
        }
        if (result.isUnmappable()) {
            throw new UnmappableCharacterException(result.length());
        }
    }

    /**
     * @param c ascii lowercase alphabetic or numeric char
     * @return char uppercased if a letter
     */
    private static char capitalizeIfLetter(char c) {
        if (Character.isLetter(c)) {
            return (char) (c - UPPER_CASE_DIFF);
        }

        return c;
    }
}
