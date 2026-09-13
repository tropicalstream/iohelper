package com.iohelper.card;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Run real spoken phrasings through the real command parser, off-device.
 *
 * A reminder that parses as a plain to-do never fires, and the only symptom is
 * silence at the moment it was supposed to matter - which is exactly how the
 * "at 11:20 AM" bug survived. Cheap to prove here, miserable to prove on a phone.
 *
 * Input is a TSV of "utterance<TAB>expected", where expected is one of
 * timer_at_clock | timer_duration | todo | none.
 */
public final class CommandsProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: CommandsProbe <cases.tsv>");
            System.exit(2);
        }
        List<String> lines = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);

        int pass = 0;
        int fail = 0;
        StringBuilder failures = new StringBuilder();
        for (String line : lines) {
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length < 2) {
                continue;
            }
            // Production strips the wake word before parsing (AssistantService
            // calls Wake.stripTrigger, then Commands.parse on the result), so a
            // corpus written the way people SPEAK - wake word last - has to be
            // stripped here too. Testing with it attached measures a string the
            // parser is never handed.
            String utterance = parts[0]
                    .replaceAll("(?i)[,\\s]*\\bjarvis\\b[.?!]*\\s*$", "")
                    .replaceAll("(?i)^\\s*\\bhey\\s+jarvis\\b[,\\s]*", "")
                    .trim();
            String expected = parts[1].trim();

            String got;
            String detail;
            // "compound" / "single" exercise the second-request detector that
            // decides whether the patterns run at all (Commands.compound),
            // rather than the parser.
            if ("compound".equals(expected) || "single".equals(expected)) {
                got = Commands.compound(utterance) ? "compound" : "single";
                detail = "-";
            } else if (expected.startsWith("hint:")) {
                // Whose record the words claim. "-" means they name a title
                // instead, and no artist check must be applied to it.
                String h = Commands.artistHint(utterance);
                got = "hint:" + (h == null ? "-" : h.toLowerCase());
                detail = "-";
            } else if ("descriptive".equals(expected) || "literal".equals(expected)) {
                // Does a play phrase DESCRIBE a release (so the resolver must
                // name it) or name one outright (literal search)? "their most
                // popular album" being taken literally is how a different
                // album got played.
                Commands.Cmd c = Commands.parse(utterance);
                boolean play = c != null && c.descr != null
                        && ("media.play".equals(c.kind) || "sonos.play".equals(c.kind));
                got = play && Commands.isDescriptive(c.descr, c.contentType) ? "descriptive" : "literal";
                detail = c == null ? "-" : "kind=" + c.kind + " descr=\"" + c.descr + "\"";
            } else try {
                Commands.Cmd c = Commands.parse(utterance);
                if (c == null) {
                    got = "none";
                    detail = "-";
                } else if ("timer".equals(c.kind)) {
                    got = c.due != null ? "timer_at_clock" : "timer_duration";
                    detail = "secs=" + c.seconds + " due=" + c.due + " label=\"" + c.text + "\"";
                } else if ("media.play".equals(c.kind)) {
                    got = c.due == null ? "media_play" : "media_play_" + c.due;
                    detail = "query=\"" + c.text + "\"";
                } else if ("media.control".equals(c.kind)) {
                    got = "media_control";
                    detail = "action=\"" + c.text + "\"";
                } else if (c.kind.startsWith("sonos.")) {
                    got = "media_control";      // a Sonos command is still transport
                    detail = "sonos action=\"" + c.text + "\"";
                } else if ("media.now".equals(c.kind)) {
                    got = "media_now";
                    detail = "-";
                } else if ("nav.start".equals(c.kind)) {
                    got = "nav.start:" + c.due;      // due carries the travel mode
                    detail = "dest=\"" + c.text + "\"";
                } else {
                    got = c.kind;
                    detail = "label=\"" + c.text + "\"";
                }
            } catch (Throwable t) {
                got = "THREW";
                detail = String.valueOf(t);
            }

            if (got.equals(expected)) {
                pass++;
            } else {
                fail++;
                failures.append(String.format("  want %-15s got %-15s  %s%n      %s%n",
                        expected, got, utterance, detail));
            }
        }
        System.out.println("passed " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.out.println("\nmismatches:");
            System.out.print(failures);
        }
        System.exit(fail == 0 ? 0 : 1);
    }
}
