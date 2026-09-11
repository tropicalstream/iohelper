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
            try {
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
