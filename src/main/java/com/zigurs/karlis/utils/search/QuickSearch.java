/*
 * Copyright 2016 Karlis Zigurs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zigurs.karlis.utils.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.zigurs.karlis.utils.search.QuickSearch.CANDIDATE_ACCUMULATION_POLICY.INTERSECTION;
import static com.zigurs.karlis.utils.search.QuickSearch.CANDIDATE_ACCUMULATION_POLICY.UNION;
import static com.zigurs.karlis.utils.search.QuickSearch.UNMATCHED_POLICY.BACKTRACKING;

/**
 * Simple and lightweight in-memory quick search provider.
 * <p>
 * Fit for low latency querying of small to medium sized datasets (limited by memory) to enable users
 * immediately see the top hits for their partially entered search string. Based on production experience
 * this approach is well perceived by users and their ability to see the top hits immediately allows
 * them to adjust their queries on the fly getting to the desired result faster.
 * <p>
 * By implementing this functionality directly in the app or corresponding backend the overall complexity of the
 * project can be significantly reduced - there is no need to care about maintaining search infrastructure, servers,
 * software or APIs.
 * <p>
 * Example uses can include:
 * <ul>
 * <li>Selecting from a list of existing contacts</li>
 * <li>Looking for a particular city (associating it with known aliases, landmarks, state, etc)</li>
 * <li>Searching for an item in an online (book) shop</li>
 * <li>Used in background to highlight items that match the (partial) keywords entered. A.la. OSX System Preferences search</li>
 * <li>Navigating large navigation trees, in example all sporting events for a year</li>
 * </ul>
 * <p>
 * Typical use case would be including it in ether application or a web server, maintaining the
 * data set (ether via provided add and remove methods or by clearing and repopulating the search contents
 * completely) and exposing an API to user that accepts a free-form input and returns corresponding matching items.
 * <p>
 * Each entry is associated with a number of keywords that are not exposed to user, therefore it is possible to add
 * name aliases or item class descriptions to keywords. Same applies to letting users discover items by unique identifiers
 * or alternate spellings.
 * <p>
 * An example contacts list is provided as example (entry followed by assigned keywords):
 * <table summary="">
 * <tr><th>Item</th><th>Supplied keywords</th></tr>
 * <tr><td>"Jane Doe, 1234"</td><td>"Jane Doe Marketing Manager SEO Community MySpace 1234"</td></tr>
 * <tr><td>"Alice Stuggard, 9473"</td><td>"Alice Stuggard Tech Cryptography Manager RSA 9473"</td></tr>
 * <tr><td>"Robert Howard, 6866"</td><td>"Robert Bob Howard Tech Necromancy Summoning Undead Cryptography BOFH RSA DOD Laundry 6866"</td></tr>
 * <tr><td>"Eve Moneypenny, 9223"</td><td>"Eve Moneypenny Accounting Manager Q OSA 9223"</td></tr>
 * </table>
 * <p>
 * In the example above if the user enters <code><strong>"Mana"</strong></code> he will be served a list of Jane,
 * Alice and Eve as their keyword <code><strong>"Manager"</strong></code> is matched by
 * <code><strong>"Mana"</strong></code>. Now user should see that the result set is sufficiently narrow and
 * can tailor his search further by continuing on to type <code><strong>"Mana a"</strong></code> - which will lead
 * to Alice and Eve being promoted to top of results. Alice because of her name match and Eve because of her department.
 * <code><strong>"Mana acc"</strong></code> will narrow the results to Eve only as she is only one in the search set
 * that can match both <code><strong>*mana*</strong></code> and <code><strong>*acc*</strong></code>.
 * <p>
 * Example use:
 * <p>
 * <code>QuickSearch&lt;String&gt; qs = new QuickSearch&lt;&gt;();<br>
 * qs.addItem("Villain", "Roy Batty Lord Voldemort Colonel Kurtz");<br>
 * qs.addItem("Hero", "Walt Kowalksi Jake Blues Shaun");<br>
 * System.out.println(qs.findItem("walk")); // finds "Hero"</code>
 * <p>
 * Concurrency - This class is thread safe (public functions are synchronised). Implementation is completely passive
 * and can be deployed horizontally as identical datasets will produce identical search results.
 *
 * @author Karlis Zigurs, 2016
 */
public class QuickSearch<T> {

    /**
     * Matching policy to apply to unmatched keywords. In case of EXACT only
     * exact supplied keyword matches will be considered, in case of BACKTRACKING
     * any keywords with no matches will be incrementally shortened until first
     * candidate match is found (e.g. supplied 'terminal' will be shortened until it
     * reaches 'ter' where it can match against 'terra').
     */
    public enum UNMATCHED_POLICY {
        EXACT, BACKTRACKING
    }

    /**
     * If multiple keywords are supplied select strategy to accumulate result set.
     * <p>
     * UNION will consider all items found for each keyword in the result,
     * INTERSECTION will consider only items that are matched by all the supplied
     * keywords.
     * <p>
     * INTERSECTION is significantly more performant as it discards
     * candidates as early as possible.
     */
    public enum CANDIDATE_ACCUMULATION_POLICY {
        UNION, INTERSECTION
    }

    /**
     * Function to 'clean up' supplied keyword and user input strings. We assume that the input is
     * going to be ether free form or malformed, therefore this allows to apply required actions to generate
     * a 'clean' set of keywords from the input string.
     * <p>
     * In example both "one two,three-four" and "one$two%three^four" as inputs will produce
     * set of 4 strings [one,two,three,four] on the output.
     */
    public static final Function<String, Set<String>> DEFAULT_KEYWORDS_EXTRACTOR =
            (s) -> Arrays.stream(s.replaceAll("[^\\w]+", " ").split("[\\s]+")).collect(Collectors.toSet());

    /**
     * Function to sanitize search keywords before using them internally. Applied to both keywords
     * supplied with items and to user input before performing search.
     * <p>
     * Rationale is to allow somewhat relaxed free-form text input (e.g. phone devices automatically capitalising
     * entered keywords) and extra capability to remap special characters to their latin alphabet equivalents.
     * <p>
     * The normalized representation has no specific requirements, this is just a convenience method.
     * Simply returning the supplied string will mean that the search results contain only exact (and case
     * sensitive) matches. It is also possible to return empty strings here, in which case the supplied
     * keyword will be ignored.
     * <p>
     * Example transformations:
     * <table summary="">
     * <tr><th>Original</th><th>Transformed</th><th>Reason</th></tr>
     * <tr><td><code>"New York"</code></td><td><code>"new york"</code></td><td>remove upper case</td></tr>
     * <tr><td><code>"Pythøn"</code></td><td><code>"python"</code></td><td>replace special characters</td></tr>
     * <tr><td><code>"HERMSGERVØRDENBRØTBØRDA"</code></td><td><code>"hermsgervordenbrotborda"</code></td><td>it could happen...</td></tr>
     * <tr><td><code>"Россия"</code></td><td><code>"rossiya"</code></td><td>translate cyrilic alphabet to latin</td></tr>
     * </table>
     * <p>
     * Default implementation assumes that String.toLowerCase().trim() is sufficient.
     */
    public static final Function<String, String> DEFAULT_KEYWORD_NORMALIZER = (s) -> s.toLowerCase().trim();

    /**
     * Function scoring user supplied input against corresponding keywords associated with search items.
     * <p>
     * An example invocations might request to compare <code><strong>"swe"</strong></code> against
     * <code><strong>"sweater"</strong></code> or <code><strong>"count"</strong></code> aganst
     * <code><strong>"accounting"</strong></code>.
     * <p>
     * Default implementation returns the ratio between search term and keyword lengths with additional boost
     * if the search term matches beginning of the keyword.
     * <p>
     * In example, while matching user input against known keyword "password", the following will be calculated:
     * <ul>
     * <li>Input "pa" -&gt; low match (0.25), but boosted (+1) due to matching start of the keyword.</li>
     * <li>Input "swo" -&gt; low match (0.37), not boosted</li>
     * <li>Input "assword" -&gt; high match (0.87), not boosted</li>
     * <li>Input "password" -&gt; high match (1), also boosted by matching the beginning of the line (+1)</li>
     * </ul>
     * <p>
     * All keywords supplied by user are scored against all matching keywords associated with a searchable item.
     * Items rank in the results is determined by the sum of all score results.
     */
    public static final BiFunction<String, String, Double> DEFAULT_MATCH_SCORER = (keywordMatch, keyword) -> {
        double matchScore = (double) keywordMatch.length() / (double) keyword.length(); // reaches maximum if lengths match (both are identical)

        // bonus boost for start of term
        if (keyword.startsWith(keywordMatch))
            matchScore += 1.0;

        return matchScore;
    };

    /**
     * Container for augumented search results (including item scores and
     * intersect of keywords for all items).
     *
     * @param <T> wrapped response item type
     */
    public static final class Response<T> {

        public static final class Item<T> {
            @NotNull
            private final T item;
            @NotNull
            private final Set<String> itemKeywords;

            private final double score;

            public Item(@NotNull T item, @NotNull Collection<String> itemKeywords, double score) {
                this.item = item;
                this.itemKeywords = new HashSet<>(itemKeywords);
                this.score = score;
            }

            @NotNull
            public T getItem() {
                return item;
            }

            @NotNull
            public Set<String> getItemKeywords() {
                return itemKeywords;
            }

            public double getScore() {
                return score;
            }
        }

        @NotNull
        private final String searchString;

        @NotNull
        private final List<Item<T>> responseItems;

        public Response(@NotNull String searchString, @NotNull List<Item<T>> responseItems) {
            this.searchString = searchString;
            this.responseItems = responseItems;
        }

        @NotNull
        public String getSearchString() {
            return searchString;
        }

        @NotNull
        public List<Item<T>> getResponseItems() {
            return responseItems;
        }
    }

    private final BinaryOperator<ScoreWrapper<T>> scoreMerger = (s1, s2) -> s1.incrementScoreBy(s2.getScore());

    /*
     * Variables
     */

    @NotNull
    private final BiFunction<String, String, Double> keywordMatchScorer;
    @NotNull
    private final Function<String, String> keywordNormalizer;
    @NotNull
    private final Function<String, Set<String>> keywordsExtractor;
    @NotNull
    private final UNMATCHED_POLICY unmatchedPolicy;
    @NotNull
    private final CANDIDATE_ACCUMULATION_POLICY candidateAccumulationPolicy;

    /**
     * Default for minimum keyword length. Any keywords shorter than this will be ignored internally.
     */
    public static final int DEFAULT_MINIMUM_KEYWORD_LENGTH = 2;
    private final int minimumKeywordLength;

    private final Map<String, Set<String>> substringsToKeywordsMap = new HashMap<>(); // links to
    private final Map<String, Set<HashWrapper<T>>> keywordsToItemsMap = new HashMap<>(); // links to
    private final Map<HashWrapper<T>, Set<String>> itemKeywordsMap = new HashMap<>();

    /*
     * Constructors
     */

    /**
     * Constructs a QuickSearch instance using defaults for keywords extractor, normaliser, match scorer and
     * minimum keyword length.
     */
    public QuickSearch() {
        this(DEFAULT_KEYWORDS_EXTRACTOR,
                DEFAULT_KEYWORD_NORMALIZER,
                DEFAULT_MATCH_SCORER,
                DEFAULT_MINIMUM_KEYWORD_LENGTH);
    }

    /**
     * Constructs a QuickSearch instance with the provided keyword processing implementations and specified minimum
     * keyword length.
     *
     * @param keywordsExtractor    Extractor function.
     * @param keywordNormalizer    Normalizer function.
     * @param keywordMatchScorer   Scorer function.
     * @param minimumKeywordLength Minimum length for keywords internally. Any keywords shorter than specified will be ignored. Should be at least 1
     */
    public QuickSearch(@Nullable Function<String, Set<String>> keywordsExtractor,
                       @Nullable Function<String, String> keywordNormalizer,
                       @Nullable BiFunction<String, String, Double> keywordMatchScorer,
                       int minimumKeywordLength) throws IllegalArgumentException {
        this(keywordsExtractor,
                keywordNormalizer,
                keywordMatchScorer,
                minimumKeywordLength,
                BACKTRACKING,
                UNION);
    }

    /**
     * Constructs a QuickSearch instance with the provided keyword processing implementations specified minimum
     * keyword length and specified unmatched and accumulation policies.
     *
     * @param keywordsExtractor           Extractor function.
     * @param keywordNormalizer           Normalizer function.
     * @param keywordMatchScorer          Scorer function.
     * @param minimumKeywordLength        Minimum length for keywords internally. Any keywords shorter than specified will be ignored. Should be at least 1
     * @param unmatchedPolicy             Policy to apply to supplied keywords without direct match
     * @param candidateAccumulationPolicy Policy to generate broad or exact results set
     */
    public QuickSearch(@Nullable Function<String, Set<String>> keywordsExtractor,
                       @Nullable Function<String, String> keywordNormalizer,
                       @Nullable BiFunction<String, String, Double> keywordMatchScorer,
                       int minimumKeywordLength,
                       @Nullable UNMATCHED_POLICY unmatchedPolicy,
                       @Nullable CANDIDATE_ACCUMULATION_POLICY candidateAccumulationPolicy) throws IllegalArgumentException {
        if (keywordsExtractor == null
                || keywordNormalizer == null
                || keywordMatchScorer == null
                || minimumKeywordLength < 1
                || unmatchedPolicy == null
                || candidateAccumulationPolicy == null)
            throw new IllegalArgumentException("Invalid configuration arguments supplied");

        this.keywordsExtractor = keywordsExtractor;
        this.keywordNormalizer = keywordNormalizer;
        this.keywordMatchScorer = keywordMatchScorer;
        this.minimumKeywordLength = minimumKeywordLength;

        this.unmatchedPolicy = unmatchedPolicy;
        this.candidateAccumulationPolicy = candidateAccumulationPolicy;
    }

    /*
     * Public interface
     */

    /**
     * Add an item with corresponding keywords, e.g. an online store item Shoe with
     * keywords <code><strong>"Shoe Red 10 Converse cheap free"</strong></code>.
     * <p>
     * You can expand the keywords stored against an item by adding it again with extra keywords.
     * If the item is already in the database any new keywords will be mapped to it.
     *
     * @param item     Item to return for search results
     * @param keywords Arbitrary list of keywords separated by space, comma, special characters, freeform text...
     * @return True if the item was added, false if no keywords to map against the item were found (therefore item was not added)
     */
    public synchronized boolean addItem(@Nullable T item, @Nullable String keywords) {
        return !(item == null || keywords == null) && addItemImpl(new HashWrapper<>(item), prepareKeywords(keywords, true));
    }

    /**
     * Remove previously added item. Calling this method removes the item and its mapping of keywords from the database.
     *
     * @param item Item to remove
     * @return True if the item was removed, false if no such item was found
     */
    public synchronized boolean removeItem(@Nullable T item) {
        return item != null && removeItemImpl(new HashWrapper<>(item));
    }

    /**
     * Find top matching item for the supplied search string
     *
     * @param searchString Raw search string
     * @return Optional containing (or not) the top scoring item
     */
    @NotNull
    public synchronized Optional<T> findItem(@Nullable String searchString) {
        if (searchString == null)
            return Optional.empty();

        return findItems(searchString, 1).stream().findFirst();
    }

    /**
     * Find top n items matching the supplied search string. Supplied string will be processed by
     * keyword extracting and normalizing functions before used for search, and any
     * extracted search keywords shorten than the specified minimum keyword length will be ignored.
     *
     * @param searchString     Raw search string, e.g. "new york pizza"
     * @param numberOfTopItems Number of items the returned result should be limited to
     * @return List of 0 to numberOfTopItems elements
     */
    @NotNull
    public synchronized List<T> findItems(@Nullable String searchString, int numberOfTopItems) {
        if (searchString == null || numberOfTopItems < 1)
            return Collections.emptyList();

        return findItemsImpl(prepareKeywords(searchString, false), numberOfTopItems)
                .stream()
                .map(e -> e.unwrap().unwrap())
                .collect(Collectors.toList());
    }

    /**
     * Find top matching item for the supplied search string and return it
     * wrapped in the augumented response object.
     *
     * @param searchString Raw search string
     * @return Response Response containing search keywords and possibly a single item.
     */
    @NotNull
    public synchronized Response<T> findAugumentedItem(@Nullable String searchString) {
        return findAugumentedItems(searchString, 1);
    }

    /**
     * Request an augumented result containing the search string, scores for all items
     * and list of keywords matched (can be used to provide hints to user).
     *
     * @param searchString     Raw search string, e.g. "new york pizza"
     * @param numberOfTopItems Number of items the result should be limited to
     * @return Response object containing 0 to n top scoring items and corresponding metadata
     */
    @NotNull
    public synchronized Response<T> findAugumentedItems(@Nullable String searchString, int numberOfTopItems) {
        if (searchString == null) {
            searchString = "";
        }

        List<Response.Item<T>> results = findItemsImpl(prepareKeywords(searchString, false), numberOfTopItems)
                .stream()
                .map(i -> new Response.Item<>(i.unwrap().unwrap(), itemKeywordsMap.get(i.unwrap()), i.getScore()))
                .collect(Collectors.toList());

        return new Response<>(searchString, results);
    }

    /**
     * Clear the search database.
     */
    public synchronized void clear() {
        keywordsToItemsMap.clear();
        substringsToKeywordsMap.clear();
        itemKeywordsMap.clear();
    }

    /**
     * Returns human-readable statistics string of current in-memory arrays.
     *
     * @return example output: "10 items; 100 keywords; 10000 fragments"
     */
    @NotNull
    public synchronized String getStats() {
        return String.format("%d items; %d keywords; %d fragments",
                itemKeywordsMap.size(),
                keywordsToItemsMap.size(),
                substringsToKeywordsMap.size()
        );
    }

    /*
     * Implementation methods
     */

    @NotNull
    private List<ScoreWrapper<T>> findItemsImpl(@NotNull Set<String> searchKeywords, int maxItemsToList) {
        if (searchKeywords.isEmpty() || maxItemsToList < 1)
            return Collections.emptyList();

        // search itself
        Collection<ScoreWrapper<T>> matchingItems = findAndScoreImpl(searchKeywords);

        /*
         * Use custom sort if the candidates list is larger than number of items we
         * need to report back. On large sets of results this can bring notable
         * improvements in speed when compared to built-in sorting methods.
         */
        if (matchingItems.size() > maxItemsToList) {
            return sortAndLimit(matchingItems, maxItemsToList, Comparator.reverseOrder());
        } else {
            return matchingItems.stream()
                    .sorted(Comparator.reverseOrder())
                    .limit(maxItemsToList)
                    .collect(Collectors.toList());
        }
    }

    @NotNull
    private Collection<ScoreWrapper<T>> findAndScoreImpl(@NotNull Set<String> suppliedFragments) {
        Map<HashWrapper<T>, ScoreWrapper<T>> matchingItems = Collections.emptyMap();

        boolean firstFragment = true;
        for (String suppliedFragment : suppliedFragments) {
            Map<HashWrapper<T>, ScoreWrapper<T>> fragmentItems = matchSingleFragment(suppliedFragment);

            if (firstFragment) {
                if (candidateAccumulationPolicy == INTERSECTION && fragmentItems.size() == 0)
                    break;

                matchingItems = fragmentItems;
                firstFragment = false;
            } else {
                if (candidateAccumulationPolicy == CANDIDATE_ACCUMULATION_POLICY.INTERSECTION) {
                    matchingItems = matchingItems.values().stream()
                            .filter(k -> fragmentItems.containsKey(k.unwrap()))
                            .map(k -> k.incrementScoreBy(fragmentItems.get(k.unwrap()).getScore()))
                            .collect(
                                    Collectors.toMap(
                                            ScoreWrapper::unwrap,
                                            v -> v,
                                            scoreMerger,
                                            LinkedHashMap::new
                                    )
                            );

                    /*
                     * If we end up with no items in second or latter iterations we may as well break
                     * as no new results will be permitted through.
                     */
                    if (matchingItems.size() == 0)
                        break;
                } else { // implied (candidateAccumulationPolicy == UNION)
                    matchingItems = Stream.of(matchingItems, fragmentItems)
                            .map(Map::entrySet)
                            .flatMap(Collection::stream)
                            .collect(
                                    Collectors.toMap(
                                            Map.Entry::getKey,
                                            Map.Entry::getValue,
                                            scoreMerger,
                                            LinkedHashMap::new
                                    )
                            );
                }
            }
        }

        return matchingItems.values();
    }

    @NotNull
    private Map<HashWrapper<T>, ScoreWrapper<T>> matchSingleFragment(@NotNull String candidateFragment) {
        Set<String> candidateKeywords = substringsToKeywordsMap.get(candidateFragment);
        if (candidateKeywords == null) {
            if (unmatchedPolicy == BACKTRACKING && candidateFragment.length() > 1) {
                /*
                 * If we have a supplied keyword we don't have a match for,
                 * try to shorten it by a char to see if that yields a result.
                 *
                 * As a result we should be able to match 'termite' against 'terminator'
                 * after two backtracking iterations.
                 */
                return matchSingleFragment(candidateFragment.substring(0, candidateFragment.length() - 1));
            } else {
                return Collections.emptyMap();
            }
        } else {
            /*
             * Otherwise proceed with normal 1:1 matching.
             */
            return scoreSingleFragment(candidateFragment, candidateKeywords);
        }
    }

    @NotNull
    private Map<HashWrapper<T>, ScoreWrapper<T>> scoreSingleFragment(@NotNull String candidateFragment, @NotNull Set<String> candidateKeywords) {
        Map<HashWrapper<T>, ScoreWrapper<T>> fragmentItems = new LinkedHashMap<>();

        for (String keyword : candidateKeywords) {
            double score = keywordMatchScorer.apply(candidateFragment, keyword);
            Set<HashWrapper<T>> items = keywordsToItemsMap.get(keyword);

            for (HashWrapper<T> i : items) {
                ScoreWrapper<T> w = fragmentItems.get(i);
                if (w == null) {
                    fragmentItems.put(i, new ScoreWrapper<>(i, score));
                } else {
                    w.setIfHigher(score); // only score max match for any particular fragment
                }
            }
        }

        return fragmentItems;
    }

    private boolean addItemImpl(@NotNull HashWrapper<T> item, @NotNull Set<String> suppliedKeywords) {
        if (suppliedKeywords.size() == 0) {
            return false; // No valid item or keywords found, skip adding
        }

        // Populate search maps
        for (String keyword : suppliedKeywords) {
            addItemToKeywordItemsList(item, keyword);
            mapKeywordSubstrings(keyword);
        }

        // Keep track of all the various keywords item has been assigned with (needed for item removal)
        Set<String> knownKeywords = itemKeywordsMap.get(item);

        if (knownKeywords == null) {
            knownKeywords = new HashSet<>();
            itemKeywordsMap.put(item, knownKeywords);
        }

        // Add keywords (or add keywords not already known if item already exists)
        suppliedKeywords.forEach(knownKeywords::add);

        return true;
    }

    private boolean removeItemImpl(@NotNull HashWrapper<T> item) {
        if (!itemKeywordsMap.containsKey(item))
            return false;

        //  all known keywords for the item
        Set<String> knownKeywords = itemKeywordsMap.get(item);

        // remove search term mappings
        for (String keyword : knownKeywords) {
            unmapKeywordSubstrings(keyword);
            removeItemFromKeywordItemsList(item, keyword);
        }

        // forget about the item
        itemKeywordsMap.remove(item);
        return true;
    }

    private void mapKeywordSubstrings(@NotNull String keyword) {
        for (int i = 0; i < keyword.length(); i++) {
            for (int y = i + 1; y <= keyword.length(); y++) {
                mapSingleKeywordSubstring(keyword, keyword.substring(i, y));
            }
        }
    }

    private void unmapKeywordSubstrings(@NotNull String keyword) {
        for (int i = 0; i < keyword.length(); i++) {
            for (int y = i + 1; y <= keyword.length(); y++) {
                unmapSingleKeywordSubstring(keyword, keyword.substring(i, y));
            }
        }
    }

    private void mapSingleKeywordSubstring(@NotNull String keyword, @NotNull String keywordSubstring) {
        Set<String> substringKeywordsList = substringsToKeywordsMap.get(keywordSubstring);

        if (substringKeywordsList == null) {
            substringKeywordsList = new LinkedHashSet<>();
            substringsToKeywordsMap.put(keywordSubstring, substringKeywordsList);
        }

        substringKeywordsList.add(keyword);
    }

    private void unmapSingleKeywordSubstring(@NotNull String keyword, @NotNull String keywordSubstring) {
        Set<String> substringKeywordsList = substringsToKeywordsMap.get(keywordSubstring);

        if (substringKeywordsList != null) {
            substringKeywordsList.remove(keyword);

            if (substringKeywordsList.size() == 0) {
                substringsToKeywordsMap.remove(keywordSubstring);
            }
        }
    }

    private void addItemToKeywordItemsList(@NotNull HashWrapper<T> item, @NotNull String keyword) {
        Set<HashWrapper<T>> keywordItems = keywordsToItemsMap.get(keyword);

        if (keywordItems == null) {
            keywordItems = new HashSet<>();
            keywordsToItemsMap.put(keyword, keywordItems);
        }

        keywordItems.add(item);
    }

    private void removeItemFromKeywordItemsList(@NotNull HashWrapper<T> item, @NotNull String keyword) {
        Set<HashWrapper<T>> keywordItems = keywordsToItemsMap.get(keyword);

        keywordItems.remove(item);

        if (keywordItems.size() == 0) {
            keywordsToItemsMap.remove(keyword);
        }
    }

    @NotNull
    private Set<String> prepareKeywords(@NotNull String keywordsString, boolean filterShortKeywords) {
        return keywordsExtractor.apply(keywordsString).stream()
                .filter(kw -> !kw.isEmpty())
                .map(keywordNormalizer)
                .filter(s -> !filterShortKeywords || s.length() >= minimumKeywordLength)
                .collect(Collectors.toSet());
    }

    /*
     * Utilities
     */

    /**
     * Purpose built sort discarding known beyond-the-cut elements early.
     * Trades the cost of manual insertion against the cost of having to sort whole array.
     * <p>
     * Comparable to built in sort functions on smaller datasets (<1000 elements), becomes
     * significantly quicker for larger datasets.
     *
     * @param input          collection to select elements from
     * @param limitResultsTo maximum size of generated ordered list
     * @param comparator     comparator to use (or use Comparator.naturalOrder())
     * @param <X>            type of objects to sort
     * @return sorted list consisting of first (up to limitResultsTo) elements in specified comparator order
     */
    <X> List<X> sortAndLimit(@NotNull Collection<? extends X> input,
                             int limitResultsTo,
                             @NotNull Comparator<X> comparator) {
        limitResultsTo = Math.max(limitResultsTo, 0); // Safety check that limit is not negative
        LinkedList<X> result = new LinkedList<>();

        for (X entry : input) {
            if (result.size() < limitResultsTo) {
                insertInListInOrderedPos(result, entry, comparator);
            } else if (comparator.compare(entry, result.getLast()) < 0) {
                insertInListInOrderedPos(result, entry, comparator);
                result.removeLast();
            }
        }

        return result;
    }

    private <X> void insertInListInOrderedPos(@NotNull List<X> result,
                                              @NotNull X entry,
                                              @NotNull Comparator<X> comparator) {
        for (int pos = 0; pos < result.size(); pos++) {
            if (comparator.compare(entry, result.get(pos)) < 0) {
                result.add(pos, entry);
                return;
            }
        }
        // If not added already (and returned), append to end of the list
        result.add(entry);
    }
}
