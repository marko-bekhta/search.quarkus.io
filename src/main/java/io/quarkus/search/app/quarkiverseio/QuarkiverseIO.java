package io.quarkus.search.app.quarkiverseio;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.ws.rs.core.UriBuilder;

import io.quarkus.search.app.entity.Guide;
import io.quarkus.search.app.entity.Language;
import io.quarkus.search.app.hibernate.InputProvider;
import io.quarkus.search.app.indexing.FailureCollector;
import io.quarkus.search.app.indexing.IndexableGuides;

import io.quarkus.logging.Log;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class QuarkiverseIO implements IndexableGuides, Closeable {

    public static final String QUARKIVERSE_ORIGIN = "quarkiverse-hub";

    private final URI quarkiverseDocsIndex;
    private final FailureCollector failureCollector;

    private final List<Guide> quarkiverseGuides = new ArrayList<>();
    private final boolean enabled;

    public QuarkiverseIO(QuarkiverseIOConfig config, FailureCollector failureCollector) {
        quarkiverseDocsIndex = config.webUri();
        enabled = config.enabled();
        this.failureCollector = failureCollector;
    }

    public void parseGuides() {
        Document index = null;
        try {
            index = Jsoup.connect(quarkiverseDocsIndex.toString()).get();
        } catch (IOException e) {
            failureCollector.critical(FailureCollector.Stage.PARSING, "Unable to fetch the Quarkiverse Docs index page.", e);
            // no point in doing anything else here:
            return;
        }

        // find links to quarkiverse extension docs:
        Elements quarkiverseGuideIndexLinks = index.select("ul.components li.component a.title");

        for (Element quarkiverseGuideIndexLink : quarkiverseGuideIndexLinks) {
            Guide guide = new Guide();
            guide.title.set(Language.ENGLISH, quarkiverseGuideIndexLink.text());

            Document extensionIndex = null;
            try {
                extensionIndex = readGuide(guide, quarkiverseGuideIndexLink.absUrl("href"));
            } catch (URISyntaxException | IOException e) {
                failureCollector.warning(FailureCollector.Stage.PARSING,
                        "Unable to fetch guide: " + quarkiverseGuideIndexLink.text(), e);
                continue;
            }

            quarkiverseGuides.add(guide);

            // find other sub-pages on the left side
            Map<URI, String> indexLinks = new HashMap<>();
            Elements extensionSubGuides = extensionIndex.select("nav.nav-menu .nav-item a");
            for (Element element : extensionSubGuides) {
                String href = element.absUrl("href");
                URI uri = UriBuilder.fromUri(href).replaceQuery(null).fragment(null).build();
                indexLinks.computeIfAbsent(uri, u -> element.text());
            }

            for (Map.Entry<URI, String> entry : indexLinks.entrySet()) {
                Guide sub = new Guide();
                sub.title.set(Language.ENGLISH, entry.getValue());
                try {
                    readGuide(sub, entry.getKey().toString());
                } catch (URISyntaxException | IOException e) {
                    failureCollector.warning(FailureCollector.Stage.PARSING,
                            "Unable to fetch guide: " + quarkiverseGuideIndexLink.text(), e);
                    continue;
                }
                quarkiverseGuides.add(sub);
            }
        }
    }

    private Document readGuide(Guide guide, String link) throws URISyntaxException, IOException {
        guide.url = new URI(link);
        guide.type = "reference";
        guide.origin = QUARKIVERSE_ORIGIN;

        Document extensionIndex = Jsoup.connect(link).get();
        Elements content = extensionIndex.select("div.content");

        String title = content.select("h1.page").text();
        if (!title.isBlank()) {
            guide.title.set(Language.ENGLISH, title);
        }
        guide.summary.set(Language.ENGLISH, content.select("div#preamble").text());
        // TODO: dump content.html() to a file? so that we don't keep all HTMLs in-memory...
        guide.htmlFullContentProvider.set(Language.ENGLISH, new StringInputProvider(link, content.html()));

        Log.debug("Parsed guide: " + guide.url);
        return extensionIndex;
    }

    public Stream<Guide> guides() {
        if (enabled) {
            parseGuides();
        }
        return quarkiverseGuides.stream();
    }

    @Override
    public void close() {
        quarkiverseGuides.clear();
    }

    private record StringInputProvider(String link, String content) implements InputProvider {

        @Override
        public InputStream open() throws IOException {
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String toString() {
            return "StringInputProvider{" +
                    "link='" + link + '\'' +
                    '}';
        }
    }
}
