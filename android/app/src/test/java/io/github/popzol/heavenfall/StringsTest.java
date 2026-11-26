package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import io.github.popzol.heavenfall.HomeModel.Job;
import io.github.popzol.heavenfall.HomeModel.Library;
import io.github.popzol.heavenfall.HomeModel.Source;
import io.github.popzol.heavenfall.HomeModel.Storage;

public class StringsTest {
    private static Map<String, String> strings(String relative) throws Exception {
        File f = new File(relative);
        if (!f.exists()) f = new File("app", relative);
        if (!f.exists()) f = new File("android/app", relative);
        NodeList list = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
                .getElementsByTagName("string");
        Map<String, String> map = new TreeMap<>();
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            assertTrue("a string twice: " + e.getAttribute("name"), map.put(e.getAttribute("name"), e.getTextContent()) == null);
        }
        return map;
    }

    private static Set<String> placeholders(String text) {
        Set<String> set = new TreeSet<>();
        Matcher m = Pattern.compile("%(\\d+)\\$[sd]").matcher(text);
        while (m.find()) set.add(m.group(1) + (text.charAt(m.end() - 1)));
        return set;
    }

    private static Set<String> keysTheCodeCanReturn() {
        Set<String> keys = new TreeSet<>();
        Tuning tuning = Support.tuning();
        HomeModel model = new HomeModel(tuning);
        String[] latest = {null, "v0.2.3", "v0.3"};
        Installed none = null;
        Installed plain = new Installed("beta", "0.2.3", "s", Support.sha('a'), false, Support.sha('b'), "t", Support.sha('b'));
        Installed withXx = new Installed("beta", "0.2.3", "s", Support.sha('a'), true, Support.sha('b'), "t", Support.sha('b'));
        for (Storage st : Storage.values())
            for (Source game : Source.values())
                for (Source update : Source.values())
                    for (Source xx : Source.values())
                        for (Installed inst : new Installed[] {none, plain, withXx})
                            for (Library lib : Library.values())
                                for (Job job : Job.values())
                                    for (String mod : latest)
                                        for (String app : new String[] {null, "v1.1"})
                                            for (int low = 0; low < 2; low++) {
                                                HomeModel.Input in = new HomeModel.Input();
                                                in.storage = st;
                                                in.game = game;
                                                in.update = update;
                                                in.xx = xx;
                                                in.installed = inst;
                                                in.library = lib;
                                                in.libraryId = low == 0 ? "" : "x";
                                                in.job = job;
                                                in.appVersion = "1.0";
                                                in.latestMod = mod;
                                                in.latestApp = app;
                                                in.releaseHasXxSet = true;
                                                in.checking = low == 1;
                                                in.freeBytes = low == 1 ? 1 : -1;
                                                in.batteryPercent = low == 1 ? 1 : -1;
                                                in.availableMemoryMb = low == 1 ? 1 : -1;
                                                collect(keys, model.compute(in));
                                            }
        for (HomeModel.More m : HomeModel.More.values()) keys.add(m.labelKey());
        for (ReleaseRules.Refusal r : ReleaseRules.Refusal.values()) {
            keys.add(r.reasonKey());
            keys.add(r.adviceKey());
        }
        for (UpdatePlan.Refusal r : UpdatePlan.Refusal.values()) {
            keys.add(r.reasonKey());
            keys.add(r.adviceKey());
        }
        for (UpdatePlan.StepKind k : UpdatePlan.StepKind.values()) keys.add(k.labelKey());
        // every key written as a literal in the sources
        File dir = new File("src/main/java/io/github/popzol/heavenfall");
        if (!dir.exists()) dir = new File("app/src/main/java/io/github/popzol/heavenfall");
        if (!dir.exists()) dir = new File("android/app/src/main/java/io/github/popzol/heavenfall");
        File[] files = dir.listFiles();
        assertTrue(files != null && files.length > 0);
        Pattern literal = Pattern.compile("\"(hf_[a-z0-9_]*[a-z0-9])\"");
        for (File f : files) {
            if (!f.isFile()) continue;   // the Android classes sit in the sub-folder app/ and name no key themselves
            try {
                Matcher m = literal.matcher(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                while (m.find()) keys.add(m.group(1));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return keys;
    }

    private static void collect(Set<String> keys, HomeModel.Output out) {
        add(keys, out.state);
        add(keys, out.button);
        for (HomeModel.Block b : out.blocks) {
            add(keys, b.reason);
            add(keys, b.advice);
        }
        for (HomeModel.Block b : out.problems) {
            add(keys, b.reason);
            add(keys, b.advice);
        }
        for (HomeModel.Check c : out.checks) {
            add(keys, c.label);
            if (c.problem != null) {
                add(keys, c.problem.reason);
                add(keys, c.problem.advice);
            }
        }
        for (Text t : out.notes) add(keys, t);
    }

    private static void add(Set<String> keys, Text t) {
        if (t != null) keys.add(t.key);
    }

    /** A key the model returns without a string in a language shows the player an empty line (or crashes). */
    @Test
    public void everyKeyHasBothLanguages() throws Exception {
        Map<String, String> en = strings("src/main/res/values/strings_heavenfall.xml");
        Map<String, String> es = strings("src/main/res/values-es/strings_heavenfall.xml");
        Set<String> missing = new TreeSet<>();
        for (String k : keysTheCodeCanReturn()) {
            if (!en.containsKey(k)) missing.add("en:" + k);
            if (!es.containsKey(k)) missing.add("es:" + k);
        }
        assertEquals("keys without a string", new TreeSet<String>(), missing);
    }

    /** A string nobody asks for is left behind by a renamed key. */
    @Test
    public void noStringWithoutAKey() throws Exception {
        Set<String> used = keysTheCodeCanReturn();
        Set<String> extra = new TreeSet<>(strings("src/main/res/values/strings_heavenfall.xml").keySet());
        extra.removeAll(used);
        assertEquals("strings no key points to", new TreeSet<String>(), extra);
    }

    /** The two languages have the same strings, each with the same arguments in the same places. */
    @Test
    public void languagesAgree() throws Exception {
        Map<String, String> en = strings("src/main/res/values/strings_heavenfall.xml");
        Map<String, String> es = strings("src/main/res/values-es/strings_heavenfall.xml");
        assertEquals(en.keySet(), es.keySet());
        for (String k : en.keySet()) {
            assertEquals("arguments of " + k, placeholders(en.get(k)), placeholders(es.get(k)));
            for (String text : new String[] {en.get(k), es.get(k)}) {
                assertTrue("an apostrophe must be escaped in " + k, !Pattern.compile("(?<!\\\\)'").matcher(text).find());
                assertTrue("empty text " + k, !text.trim().isEmpty());
            }
        }
    }

    /** The arguments the model passes fill every placeholder of the string (and no more). */
    @Test
    public void argumentsMatchPlaceholders() throws Exception {
        Map<String, String> en = strings("src/main/res/values/strings_heavenfall.xml");
        Tuning tuning = Support.tuning();
        HomeModel model = new HomeModel(tuning);
        HomeModel.Input in = new HomeModel.Input();
        in.game = Source.GOOD;
        in.update = Source.GOOD;
        in.appVersion = "1.0";
        in.latestMod = "v0.3";
        in.latestApp = "v1.1";
        in.freeBytes = 1;
        in.batteryPercent = 1;
        in.availableMemoryMb = 1;
        in.installed = new Installed("beta", "0.2", "s", Support.sha('a'), false, Support.sha('b'), "t", Support.sha('b'));
        in.library = Library.OTHER_TOOLS;
        in.libraryId = "x";
        HomeModel.Output out = model.compute(in);
        checkArgs(en, out.state);
        checkArgs(en, out.button);
        for (HomeModel.Block b : out.blocks) {
            checkArgs(en, b.reason);
            checkArgs(en, b.advice);
        }
        in.installed = null;
        in.latestMod = null;
        out = model.compute(in);
        for (HomeModel.Block b : out.blocks) checkArgs(en, b.reason);
        in.latestMod = "v0.3";
        in.library = Library.OTHER_TOOLS;
        in.installed = new Installed("beta", "0.3", "s", Support.sha('a'), false, Support.sha('b'), "t", Support.sha('b'));
        in.latestApp = null;
        checkArgs(en, model.compute(in).state);
    }

    private static void checkArgs(Map<String, String> en, Text t) {
        assertEquals("arguments of " + t.key, placeholders(en.get(t.key)).size(), t.args.size());
    }
}
