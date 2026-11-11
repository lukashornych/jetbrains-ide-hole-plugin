package io.github.lukashornych.hole.idea.session;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OSC 52 recognition in a stream of terminal output chunks.
 */
class Osc52ParserTest {

    private static final String ESC = "\033";
    private static final String BEL = "\007";
    private static final String ST = ESC + "\\";

    private final Recorder recorder = new Recorder();

    @Test
    void reportsTextTerminatedByBel() {
        feed(new Osc52Parser(this.recorder), "before" + set("c", "hello") + BEL + "after");
        assertEquals(List.of("hello"), this.recorder.copies);
    }

    @Test
    void reportsTextTerminatedByStringTerminator() {
        feed(new Osc52Parser(this.recorder), set("c", "hello") + ST);
        assertEquals(List.of("hello"), this.recorder.copies);
    }

    @Test
    void acceptsAnyTargets() {
        final Osc52Parser parser = new Osc52Parser(this.recorder);
        feed(parser, set("", "empty") + BEL + set("pc", "two") + BEL + set("0", "cut") + ST);
        assertEquals(List.of("empty", "two", "cut"), this.recorder.copies);
    }

    @Test
    void survivesASplitAtEveryPosition() {
        final String input = "x" + ESC + "]0;title" + BEL + set("c", "žluťoučký kůň 🐎") + ST + "y";
        for (int split = 0; split <= input.length(); split++) {
            final Recorder recorder = new Recorder();
            final Osc52Parser parser = new Osc52Parser(recorder);
            feed(parser, input.substring(0, split));
            feed(parser, input.substring(split));
            assertEquals(List.of("žluťoučký kůň 🐎"), recorder.copies, "split at " + split);
        }
    }

    @Test
    void survivesOneCharacterPerChunk() {
        final String input = set("c", "one by one") + BEL;
        final Osc52Parser parser = new Osc52Parser(this.recorder);
        for (char c : input.toCharArray()) {
            feed(parser, String.valueOf(c));
        }
        assertEquals(List.of("one by one"), this.recorder.copies);
    }

    @Test
    void roundTripsUtf8() {
        feed(new Osc52Parser(this.recorder), set("c", "Příliš žluťoučký kůň úpěl ďábelské ódy 😀\n") + BEL);
        assertEquals(List.of("Příliš žluťoučký kůň úpěl ďábelské ódy 😀\n"), this.recorder.copies);
    }

    @Test
    void toleratesLineBreaksInThePayload() {
        final String wrapped = Base64.getMimeEncoder().encodeToString("x".repeat(200).getBytes(StandardCharsets.UTF_8));
        feed(new Osc52Parser(this.recorder), ESC + "]52;c;" + wrapped + BEL);
        assertEquals(List.of("x".repeat(200)), this.recorder.copies);
    }

    @Test
    void ignoresAQuery() {
        final Osc52Parser parser = new Osc52Parser(this.recorder);
        feed(parser, ESC + "]52;c;?" + BEL + ESC + "]52;c;?" + ST);
        assertEquals(List.of(), this.recorder.copies);
        assertEquals(0, this.recorder.tooLarge);
    }

    @Test
    void reportsOnlyOsc52AmongOtherSequences() {
        final Osc52Parser parser = new Osc52Parser(this.recorder);
        feed(parser, "a" + ESC + "]0;title" + BEL + ESC + "]8;;https://example.com" + ST + "link"
            + ESC + "]8;;" + ST + ESC + "[1m" + set("c", "copied") + BEL + ESC + "]2;other" + ST + "z");
        assertEquals(List.of("copied"), this.recorder.copies);
    }

    @Test
    void doesNotBufferOtherSequences() {
        final Osc52Parser parser = new Osc52Parser(this.recorder, 16);
        feed(parser, ESC + "]0;" + "t".repeat(1000) + BEL + set("c", "ok") + BEL);
        assertEquals(List.of("ok"), this.recorder.copies);
        assertEquals(0, this.recorder.tooLarge);
    }

    @Test
    void ignoresInvalidBase64() {
        final Osc52Parser parser = new Osc52Parser(this.recorder);
        feed(parser, ESC + "]52;c;ab!cd" + BEL + ESC + "]52;c;abc" + BEL + ESC + "]52;c;@@@@" + BEL);
        assertEquals(List.of(), this.recorder.copies);
    }

    @Test
    void acceptsUnpaddedBase64() {
        feed(new Osc52Parser(this.recorder), ESC + "]52;c;YWI" + BEL);
        assertEquals(List.of("ab"), this.recorder.copies);
    }

    @Test
    void ignoresPaddingInsideThePayload() {
        feed(new Osc52Parser(this.recorder), ESC + "]52;c;YQ==YQ==" + BEL);
        assertEquals(List.of(), this.recorder.copies);
    }

    @Test
    void ignoresInvalidUtf8() {
        final String payload = Base64.getEncoder().encodeToString(new byte[]{(byte) 0xC3, (byte) 0x28});
        feed(new Osc52Parser(this.recorder), ESC + "]52;c;" + payload + BEL);
        assertEquals(List.of(), this.recorder.copies);
    }

    @Test
    void ignoresAnEmptyPayload() {
        final Osc52Parser parser = new Osc52Parser(this.recorder);
        feed(parser, ESC + "]52;c;" + BEL + ESC + "]52;;" + ST);
        assertEquals(List.of(), this.recorder.copies);
    }

    @Test
    void dropsAPayloadOverTheLimitAndRecovers() {
        final Osc52Parser parser = new Osc52Parser(this.recorder, 16);
        feed(parser, set("c", "x".repeat(100)) + BEL);
        assertEquals(List.of(), this.recorder.copies);
        assertEquals(1, this.recorder.tooLarge);

        feed(parser, set("c", "small") + ST);
        assertEquals(List.of("small"), this.recorder.copies);
        assertEquals(1, this.recorder.tooLarge);
    }

    @Test
    void acceptsAPayloadExactlyAtTheLimit() {
        final Osc52Parser parser = new Osc52Parser(this.recorder, 8);
        feed(parser, set("c", "123456") + BEL);
        assertEquals(List.of("123456"), this.recorder.copies);
    }

    @Test
    void abortsOnAnotherEscapeSequence() {
        final Osc52Parser parser = new Osc52Parser(this.recorder);
        feed(parser, ESC + "]52;c;YWJj" + ESC + "[0m" + "text" + set("c", "next") + BEL);
        assertEquals(List.of("next"), this.recorder.copies);
    }

    @Test
    void abortsOnCancel() {
        final Osc52Parser parser = new Osc52Parser(this.recorder);
        feed(parser, ESC + "]52;c;YWJj\030" + BEL + ESC + "]52;c;YWJj\032" + BEL);
        assertEquals(List.of(), this.recorder.copies);
    }

    @Test
    void anOverflowAbortedByAnEscapeIsNotReported() {
        final Osc52Parser parser = new Osc52Parser(this.recorder, 4);
        feed(parser, ESC + "]52;c;YWJjZGVm" + ESC + "[0m" + set("c", "ok") + BEL);
        assertEquals(List.of("ok"), this.recorder.copies);
        assertEquals(0, this.recorder.tooLarge);
    }

    @Test
    void handlesAnOscWithoutParameters() {
        final Osc52Parser parser = new Osc52Parser(this.recorder);
        feed(parser, ESC + "]104" + BEL + ESC + "]52" + BEL + set("c", "after") + BEL);
        assertEquals(List.of("after"), this.recorder.copies);
    }

    @Test
    void anUnterminatedOscIsAbortedByTheNextOne() {
        final Osc52Parser parser = new Osc52Parser(this.recorder);
        feed(parser, ESC + "]0;unterminated title" + set("c", "copied") + BEL);
        assertEquals(List.of("copied"), this.recorder.copies);
    }

    @Test
    void ignoresOtherNumbersStartingWith52() {
        final Osc52Parser parser = new Osc52Parser(this.recorder);
        feed(parser, ESC + "]520;c;YWJj" + BEL + ESC + "]5;c;YWJj" + BEL);
        assertEquals(List.of(), this.recorder.copies);
    }

    @Test
    void aFreshParserIgnoresTheRestOfAHalfReadSequence() {
        final String sequence = set("c", "old process") + BEL;
        final Osc52Parser old = new Osc52Parser(this.recorder);
        feed(old, sequence.substring(0, sequence.length() / 2));

        final Osc52Parser fresh = new Osc52Parser(this.recorder);
        feed(fresh, sequence.substring(sequence.length() / 2));
        assertEquals(List.of(), this.recorder.copies);
    }

    @NotNull
    private static String set(@NotNull String targets, @NotNull String text) {
        return ESC + "]52;" + targets + ";" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void feed(@NotNull Osc52Parser parser, @NotNull String chunk) {
        // offset inside a larger buffer, like the connector's read buffer
        final char[] buffer = ("##" + chunk + "##").toCharArray();
        parser.feed(buffer, 2, chunk.length());
    }

    private static final class Recorder implements Osc52Parser.Listener {
        final List<String> copies = new ArrayList<>();
        int tooLarge;

        @Override
        public void copied(@NotNull String text) {
            this.copies.add(text);
        }

        @Override
        public void tooLarge() {
            this.tooLarge++;
        }
    }
}
