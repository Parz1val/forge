/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.card;

import com.google.common.base.Predicate;
import com.google.common.collect.*;
import forge.card.CardEdition.CardInSet;
import forge.card.CardEdition.Type;
import forge.deck.generation.IDeckGenPool;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.util.CollectionSuppliers;
import forge.util.Lang;
import forge.util.TextUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.Map.Entry;

public final class CardDb implements ICardDatabase, IDeckGenPool {
    public final static String foilSuffix = "+";
    public final static char NameSetSeparator = '|';
    private final String exlcudedCardName = "Concentrate";
    private final String exlcudedCardSet = "DS0";

    // need this to obtain cardReference by name+set+artindex
    private final ListMultimap<String, PaperCard> allCardsByName = Multimaps.newListMultimap(new TreeMap<>(String.CASE_INSENSITIVE_ORDER), CollectionSuppliers.arrayLists());
    private final Map<String, PaperCard> uniqueCardsByName = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, CardRules> rulesByName;
    private final Map<String, ICardFace> facesByName = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);
    private static Map<String, String> artPrefs = new HashMap<>();

    private final Map<String, String> alternateName = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, Integer> artIds = new HashMap<>();

    private final CardEdition.Collection editions;
    private List<String> filtered;

    public enum CardArtPreference {
        LatestArtAllEditions(false, true),
        LatestArtExcludedPromoAndOnlineEditions(true, true),
        OldArtAllEditions(false, false),
        OldArtExcludedPromoAndOnlineEditions(true, false);

        final boolean filterSets;
        final boolean latestFirst;

        CardArtPreference(boolean filterIrregularSets, boolean latestSetFirst) {
            filterSets = filterIrregularSets;
            latestFirst = latestSetFirst;
        }

        public boolean accept(CardEdition ed) {
            if (ed == null) return false;
            return !filterSets || ed.getType() == Type.CORE || ed.getType() == Type.EXPANSION || ed.getType() == Type.REPRINT;
        }

        public static String[] getPreferences(){
            return new String[]{"Latest Art (All Editions)",
                    "Latest Art (Excluded Promo And Online Editions)",
                    "Old Art (All Editions)",
                    "Old Art (Excluded Promo And Online Editions)"};
        }
    }

    // Placeholder to setup default art Preference - to be moved from Static Data!
    private CardArtPreference defaultCardArtPreference;

    public static class CardRequest {
        public String cardName;
        public String edition;
        public int artIndex;
        public boolean isFoil;
        public String collectorNumber;

        private CardRequest(String name, String edition, int artIndex, boolean isFoil, String collectorNumber) {
            cardName = name;
            this.edition = edition;
            this.artIndex = artIndex;
            this.isFoil = isFoil;
            this.collectorNumber = collectorNumber;
        }

        public static String compose(String cardName, String setCode) {
            setCode = setCode != null ? setCode : "";
            cardName = cardName != null ? cardName : "";
            return cardName + NameSetSeparator + setCode;
        }

        public static String compose(String cardName, String setCode, int artIndex) {
            String requestInfo = compose(cardName, setCode);
            artIndex = Math.max(artIndex, IPaperCard.DEFAULT_ART_INDEX);
            return requestInfo + NameSetSeparator + artIndex;
        }

        public static String compose(String cardName, String setCode, String collectorNumber) {
            String requestInfo = compose(cardName, setCode);
            // CollectorNumber will be wrapped in square brackets
            collectorNumber = preprocessCollectorNumber(collectorNumber);
            return requestInfo + NameSetSeparator + collectorNumber;
        }

        private static String preprocessCollectorNumber(String collectorNumber) {
            if (collectorNumber == null)
                return "";
            collectorNumber = collectorNumber.trim();
            if (!collectorNumber.startsWith("["))
                collectorNumber = "[" + collectorNumber;
            if (!collectorNumber.endsWith("]"))
                collectorNumber += "]";
            return collectorNumber;
        }

        public static String compose(String cardName, String setCode, int artIndex, String collectorNumber) {
            String requestInfo = compose(cardName, setCode, artIndex);
            // CollectorNumber will be wrapped in square brackets
            collectorNumber = preprocessCollectorNumber(collectorNumber);
            return requestInfo + NameSetSeparator + collectorNumber;
        }

        private static boolean isCollectorNumber(String s) {
            return s.startsWith("[") && s.endsWith("]");
        }

        private static boolean isArtIndex(String s) {
            return StringUtils.isNumeric(s) && s.length() == 1;
        }

        private static boolean isSetCode(String s) {
            return !StringUtils.isNumeric(s);
        }

        public static CardRequest fromString(String reqInfo) {
            if (reqInfo == null)
                return null;

            String[] info = TextUtil.split(reqInfo, NameSetSeparator);
            int setPos;
            int artPos;
            int cNrPos;
            if (info.length >= 4) { // name|set|artIndex|[collNr]
                setPos = isSetCode(info[1]) ? 1 : -1;
                artPos = isArtIndex(info[2]) ? 2 : -1;
                cNrPos = isCollectorNumber(info[3]) ? 3 : -1;
            } else if (info.length == 3) { // name|set|artIndex (or CollNr)
                setPos = isSetCode(info[1]) ? 1 : -1;
                artPos = isArtIndex(info[2]) ? 2 : -1;
                cNrPos = isCollectorNumber(info[2]) ? 2 : -1;
            } else if (info.length == 2) { // name|set (or artIndex, even if not possible via compose)
                setPos = isSetCode(info[1]) ? 1 : -1;
                artPos = isArtIndex(info[1]) ? 1 : -1;
                cNrPos = -1;
            } else {
                setPos = -1;
                artPos = -1;
                cNrPos = -1;
            }
            String cardName = info[0];
            boolean isFoil = false;
            if (cardName.endsWith(foilSuffix)) {
                cardName = cardName.substring(0, cardName.length() - foilSuffix.length());
                isFoil = true;
            }

            String preferredArt = artPrefs.get(cardName);
            int artIndex = artPos > 0 ? Integer.parseInt(info[artPos]) : IPaperCard.NO_ART_INDEX;  // default: no art index
            if (preferredArt != null) { //account for preferred art if needed
                System.err.println("I AM HERE - DECIDE WHAT TO DO");
            }
            String collectorNumber = cNrPos > 0 ? info[cNrPos].substring(1, info[cNrPos].length() - 1) : IPaperCard.NO_COLLECTOR_NUMBER;
            String setName = setPos > 0 ? info[setPos] : null;
            if ("???".equals(setName)) {
                setName = null;
            }
            // finally, check whether any between artIndex and CollectorNumber has been set
            if (collectorNumber.equals(IPaperCard.NO_COLLECTOR_NUMBER) && artIndex == IPaperCard.NO_ART_INDEX)
                artIndex = IPaperCard.DEFAULT_ART_INDEX;
            return new CardRequest(cardName, setName, artIndex, isFoil, collectorNumber);
        }
    }

    public CardDb(Map<String, CardRules> rules, CardEdition.Collection editions0, List<String> filteredCards){
        this(rules, editions0, filteredCards, "LatestPrint");
    }
    public CardDb(Map<String, CardRules> rules, CardEdition.Collection editions0, List<String> filteredCards, String preferredCardArt) {
        this.filtered = filteredCards;
        this.rulesByName = rules;
        this.editions = editions0;

        // create faces list from rules
        for (final CardRules rule : rules.values()) {
            if (filteredCards.contains(rule.getName()) && !exlcudedCardName.equalsIgnoreCase(rule.getName()))
                continue;
            final ICardFace main = rule.getMainPart();
            facesByName.put(main.getName(), main);
            if (main.getAltName() != null) {
                alternateName.put(main.getAltName(), main.getName());
            }
            final ICardFace other = rule.getOtherPart();
            if (other != null) {
                facesByName.put(other.getName(), other);
                if (other.getAltName() != null) {
                    alternateName.put(other.getAltName(), other.getName());
                }
            }
        }
        setCardArtPreference(preferredCardArt);
    }

    private void addSetCard(CardEdition e, CardInSet cis, CardRules cr) {
        int artIdx = IPaperCard.DEFAULT_ART_INDEX;
        String key = e.getCode() + "/" + cis.name;
        if (artIds.containsKey(key)) {
            artIdx = artIds.get(key) + 1;
        }

        artIds.put(key, artIdx);
        addCard(new PaperCard(cr, e.getCode(), cis.rarity, artIdx, cis.collectorNumber));
    }

    public void loadCard(String cardName, CardRules cr) {
        rulesByName.put(cardName, cr);
        // This seems very unperformant. Does this get called often?
        System.out.println("Inside loading card");

        for (CardEdition e : editions) {
            for (CardInSet cis : e.getAllCardsInSet()) {
                if (cis.name.equalsIgnoreCase(cardName)) {
                    addSetCard(e, cis, cr);
                }
            }
        }

        reIndex();
    }

    public void initialize(boolean logMissingPerEdition, boolean logMissingSummary, boolean enableUnknownCards) {
        Set<String> allMissingCards = new LinkedHashSet<>();
        List<String> missingCards = new ArrayList<>();
        CardEdition upcomingSet = null;
        Date today = new Date();

        for (CardEdition e : editions.getOrderedEditions()) {
            boolean coreOrExpSet = e.getType() == CardEdition.Type.CORE || e.getType() == CardEdition.Type.EXPANSION;
            boolean isCoreExpSet = coreOrExpSet || e.getType() == CardEdition.Type.REPRINT;
            if (logMissingPerEdition && isCoreExpSet) {
                System.out.print(e.getName() + " (" + e.getAllCardsInSet().size() + " cards)");
            }
            if (coreOrExpSet && e.getDate().after(today) && upcomingSet == null) {
                upcomingSet = e;
            }

            for (CardEdition.CardInSet cis : e.getAllCardsInSet()) {
                CardRules cr = rulesByName.get(cis.name);
                if (cr != null) {
                    addSetCard(e, cis, cr);
                } else {
                    missingCards.add(cis.name);
                }
            }
            if (isCoreExpSet && logMissingPerEdition) {
                if (missingCards.isEmpty()) {
                    System.out.println(" ... 100% ");
                } else {
                    int missing = (e.getAllCardsInSet().size() - missingCards.size()) * 10000 / e.getAllCardsInSet().size();
                    System.out.printf(" ... %.2f%% (%s missing: %s)%n", missing * 0.01f, Lang.nounWithAmount(missingCards.size(), "card"), StringUtils.join(missingCards, " | "));
                }
            }
            if (isCoreExpSet && logMissingSummary) {
                allMissingCards.addAll(missingCards);
            }
            missingCards.clear();
            artIds.clear();
        }

        if (logMissingSummary) {
            System.out.printf("Totally %d cards not implemented: %s\n", allMissingCards.size(), StringUtils.join(allMissingCards, " | "));
        }

        if (upcomingSet != null) {
            System.err.println("Upcoming set " + upcomingSet + " dated in the future. All unaccounted cards will be added to this set with unknown rarity.");
        }

        for (CardRules cr : rulesByName.values()) {
            if (!contains(cr.getName())) {
                if (upcomingSet != null) {
                    addCard(new PaperCard(cr, upcomingSet.getCode(), CardRarity.Unknown));
                } else if (enableUnknownCards) {
                    System.err.println("The card " + cr.getName() + " was not assigned to any set. Adding it to UNKNOWN set... to fix see res/editions/ folder. ");
                    addCard(new PaperCard(cr, CardEdition.UNKNOWN.getCode(), CardRarity.Special));
                }
            }
        }

        reIndex();
    }

    public void addCard(PaperCard paperCard) {
        if (excludeCard(paperCard.getName(), paperCard.getEdition()))
            return;

        allCardsByName.put(paperCard.getName(), paperCard);

        if (paperCard.getRules().getSplitType() == CardSplitType.None) {
            return;
        }

        if (paperCard.getRules().getOtherPart() != null) {
            //allow looking up card by the name of other faces
            allCardsByName.put(paperCard.getRules().getOtherPart().getName(), paperCard);
        }
        if (paperCard.getRules().getSplitType() == CardSplitType.Split) {
            //also include main part for split cards
            allCardsByName.put(paperCard.getRules().getMainPart().getName(), paperCard);
        }
    }

    private boolean excludeCard(String cardName, String cardEdition) {
        if (filtered.isEmpty())
            return false;
        if (filtered.contains(cardName)) {
            if (exlcudedCardSet.equalsIgnoreCase(cardEdition) && exlcudedCardName.equalsIgnoreCase(cardName))
                return true;
            else return !exlcudedCardName.equalsIgnoreCase(cardName);
        }
        return false;
    }

    private void reIndex() {
        uniqueCardsByName.clear();
        for (Entry<String, Collection<PaperCard>> kv : allCardsByName.asMap().entrySet()) {
            PaperCard pc = getFirstWithImage(kv.getValue());
            uniqueCardsByName.put(kv.getKey(), pc);
        }
    }

    private static PaperCard getFirstWithImage(final Collection<PaperCard> cards) {
        //NOTE: this is written this way to avoid checking final card in list
        final Iterator<PaperCard> iterator = cards.iterator();
        PaperCard pc = iterator.next();
        while (iterator.hasNext()) {
            if (pc.hasImage()) {
                return pc;
            }
            pc = iterator.next();
        }
        return pc;
    }

    public boolean setPreferredArt(String cardName, String preferredArt) {
        CardRequest request = CardRequest.fromString(cardName + NameSetSeparator + preferredArt);
        PaperCard pc = tryGetCard(request);
        if (pc != null) {
            artPrefs.put(cardName, preferredArt);
            uniqueCardsByName.put(cardName, pc);
            return true;
        }
        return false;
    }

    public CardRules getRules(String cardName) {
        CardRules result = rulesByName.get(cardName);
        if (result != null) {
            return result;
        } else {
            return CardRules.getUnsupportedCardNamed(cardName);
        }
    }

    public CardArtPreference getCardArtPreference(){ return this.defaultCardArtPreference; }
    public void setCardArtPreference(String artPreference){
        artPreference = artPreference.replaceAll("[\\s\\(\\)]", "");
        CardArtPreference cardArtPreference = null;
        try{
            cardArtPreference = CardArtPreference.valueOf(artPreference);
        } catch (IllegalArgumentException ex){
            cardArtPreference = CardArtPreference.LatestArtAllEditions;  // default
        }
        finally {
            if (cardArtPreference != null)
                this.defaultCardArtPreference = cardArtPreference;
        }
    }

    /*
     * ======================
     * 1. CARD LOOKUP METHODS
     * ======================
     */
    @Override
    public PaperCard getCard(String cardName) {
        CardRequest request = CardRequest.fromString(cardName);
        return tryGetCard(request);
    }

    @Override
    public PaperCard getCard(final String cardName, String setCode) {
        CardRequest request = CardRequest.fromString(CardRequest.compose(cardName, setCode));
        return tryGetCard(request);
    }

    @Override
    public PaperCard getCard(final String cardName, String setCode, int artIndex) {
        String reqInfo = CardRequest.compose(cardName, setCode, artIndex);
        CardRequest request = CardRequest.fromString(reqInfo);
        return tryGetCard(request);
    }

    @Override
    public PaperCard getCard(final String cardName, String setCode, String collectorNumber) {
        String reqInfo = CardRequest.compose(cardName, setCode, collectorNumber);
        CardRequest request = CardRequest.fromString(reqInfo);
        return tryGetCard(request);
    }

    @Override
    public PaperCard getCard(final String cardName, String setCode, int artIndex, String collectorNumber) {
        String reqInfo = CardRequest.compose(cardName, setCode, artIndex, collectorNumber);
        CardRequest request = CardRequest.fromString(reqInfo);
        return tryGetCard(request);
    }

    private PaperCard tryGetCard(CardRequest request) {
        // Before doing anything, check that a non-null request has been provided
        if (request == null)
            return null;
        // 1. First off, try using all possible search parameters, to narrow down the actual cards looked for.
        String reqEditionCode = request.edition;
        PaperCard result = null;
        if ((reqEditionCode != null) && (reqEditionCode.length() > 0)) {
            // This get is robust even against expansion aliases (e.g. TE and TMP both valid for Tempest) -
            // MOST of the extensions have two short codes, 141 out of 221 (so far)
            CardEdition edition = editions.get(reqEditionCode);
            return this.getCardFromSet(request.cardName, edition, request.artIndex,
                    request.collectorNumber, request.isFoil);
        }

        // 2. Card lookup in edition with specified filter didn't work.
        // So now check whether the cards exists in the DB first,
        // and select pick the card based on current SetPreference policy as a fallback
        Collection<PaperCard> cards = getAllCards(request.cardName);
        if (cards == null)
            return null;
        // Either No Edition has been specified OR as a fallback in case of any error!
        // get card using the default policy strategy (Latest, Earliest, or Random)
        // Note: Request is transformed back into the unique line,
        // embedding all the information; Parameter Name is counter intuitive though.
        result = getCardFromEditions(request.cardName);
        return result != null && request.isFoil ? result.getFoiled() : result;
    }

    /*
     * ==========================================
     * 2. CARD LOOKUP FROM A SINGLE EXPANSION SET
     * ==========================================
     */
    @Override
    public PaperCard getCardFromSet(String cardName, CardEdition edition, boolean isFoil) {
        return getCardFromSet(cardName, edition, IPaperCard.NO_ART_INDEX,
                IPaperCard.NO_COLLECTOR_NUMBER, isFoil);
    }

    @Override
    public PaperCard getCardFromSet(String cardName, CardEdition edition, int artIndex, boolean isFoil) {
        return getCardFromSet(cardName, edition, artIndex, IPaperCard.NO_COLLECTOR_NUMBER, isFoil);
    }

    @Override
    public PaperCard getCardFromSet(String cardName, CardEdition edition, String collectorNumber, boolean isFoil) {
        return getCardFromSet(cardName, edition, IPaperCard.NO_ART_INDEX, collectorNumber, isFoil);
    }

    @Override
    public PaperCard getCardFromSet(String cardName, CardEdition edition, int artIndex,
                                    String collectorNumber, boolean isFoil) {
        if (edition == null || cardName == null)  // preview cards
            return null;  // No cards will be returned

        // Allow to pass in cardNames with foil markers, and adapt accordingly
        CardRequest cardNameRequest = CardRequest.fromString(cardName);
        cardName = cardNameRequest.cardName;
        isFoil = isFoil || cardNameRequest.isFoil;

        List<PaperCard> cards = getAllCards(cardName);
        // Look for Code or Code2 to make the retrieval more robust
        List<PaperCard> candidates = Lists.newArrayList(Iterables.filter(cards, new Predicate<PaperCard>() {
            @Override
            public boolean apply(PaperCard c) {
                boolean artIndexFilter = true;
                boolean collectorNumberFilter = true;
                boolean setFilter = ((c.getEdition().equalsIgnoreCase(edition.getCode())) ||
                        (c.getEdition().equalsIgnoreCase(edition.getCode2())));
                if (artIndex > 0)
                    artIndexFilter = (c.getArtIndex() == artIndex);
                if ((collectorNumber != null) && (collectorNumber.length() > 0)
                        && !(collectorNumber.equals(IPaperCard.NO_COLLECTOR_NUMBER)))
                    collectorNumberFilter = (c.getCollectorNumber().equals(collectorNumber));
                return setFilter && artIndexFilter && collectorNumberFilter;
            }
        }));
        if (candidates.isEmpty()) {
            return null;
        }
        PaperCard candidate = candidates.get(0);
        // Before returning make sure that actual candidate has Image.
        // If not, try to replace current candidate with one having image, so to align this implementation with old one.
        if (!candidate.hasImage()) {
            for (PaperCard card : candidates) {
                if (card.hasImage()) {
                    candidate = card;
                    break; // found, ready to go
                }
            }
        }
        return isFoil ? candidate.getFoiled() : candidate;
    }

    /*
     * ====================================================
     * 3. CARD LOOKUP BASED ON PREFERRED PRINT (FRAME) OPTION
     * ====================================================
     */

    /* Get Card from Edition using the default `CardArtPreference`
    NOTE: this method has NOT been included in the Interface API refactoring as it
    relies on a specific (new) attribute included in the `CardDB` that sets the
    default `ArtPreference`. This attribute does not necessarily belongs to any
    class implementing ICardInterface, and so the not inclusion in the API
     */
    public PaperCard getCardFromEditions(final String cardName) {
        return this.getCardFromEditions(cardName, this.defaultCardArtPreference);
    }

    public PaperCard getCardFromEditions(final String cardName, final Date printedBefore) {
        return this.getCardFromEditions(cardName, this.defaultCardArtPreference, IPaperCard.NO_ART_INDEX, printedBefore);
    }

    public PaperCard getCardFromEditions(final String cardName, final int artIndex, final Date printedBefore) {
        return this.getCardFromEditions(cardName, this.defaultCardArtPreference, artIndex, printedBefore);
    }

    @Override
    public PaperCard getCardFromEditions(final String cardName, CardArtPreference artPreference) {
        return getCardFromEditions(cardName, artPreference, IPaperCard.NO_ART_INDEX, null);
    }

    @Override
    public PaperCard getCardFromEditions(final String cardName, CardArtPreference artPreference, int artIndex) {
        return getCardFromEditions(cardName, artPreference, artIndex, null);
    }

    @Override
    public PaperCard getCardFromEditions(final String cardName, final CardArtPreference artPreference, final Date printedBefore) {
        return getCardFromEditions(cardName, artPreference, IPaperCard.NO_ART_INDEX, printedBefore);
    }

    @Override
    public PaperCard getCardFromEditions(final String cardName, final CardArtPreference artPreference, int artIndex,
                                         final Date printedBefore) {
        if (cardName == null)
            return null;
        final CardRequest cr = CardRequest.fromString(cardName);
        // Check whether input `frame` is null. In that case, fallback to default SetPreference !-)
        final CardArtPreference artPref = artPreference != null ? artPreference : this.defaultCardArtPreference;
        if (artIndex >= IPaperCard.DEFAULT_ART_INDEX && cr.artIndex < IPaperCard.DEFAULT_ART_INDEX) {
            cr.artIndex = artIndex;
        }
        List<PaperCard> cards = getAllCards(cr.cardName);
        if (printedBefore != null) {
            cards = Lists.newArrayList(Iterables.filter(cards, new Predicate<PaperCard>() {
                @Override
                public boolean apply(PaperCard c) {
                    CardEdition ed = editions.get(c.getEdition());
                    return ed.getDate().before(printedBefore);
                }
            }));
        }

        if (cards.size() == 0)  // Don't bother continuing! No card has been found!
            return null;

        /* 2. Retrieve cards based of [Frame]Set Preference
           ================================================ */

        // Collect the list of all editions found for target card
        LinkedHashSet<CardEdition> cardEditions = new LinkedHashSet<>();
        for (PaperCard card : cards) {
            CardEdition ed = editions.get(card.getEdition());
            cardEditions.add(ed);
        }
        // Filter Cards Editions based on set preferences
        List<CardEdition> acceptedEditions = Lists.newArrayList(Iterables.filter(cardEditions, new Predicate<CardEdition>() {
            @Override
            public boolean apply(CardEdition ed) {
                return artPref.accept(ed);
            }
        }));
        Collections.sort(acceptedEditions);  // CardEdition correctly sort by (release) date
        if (artPref.latestFirst)
            Collections.reverse(acceptedEditions);  // newest editions first

        PaperCard candidate = null;
        for (CardEdition ed : acceptedEditions) {
            PaperCard cardFromSet = getCardFromSet(cr.cardName, ed, artIndex, cr.isFoil);
            if (candidate == null && cardFromSet != null)
                // save the first card found, as the last backup in case no other candidate *with image* will be found
                candidate = cardFromSet;

            if (cardFromSet != null && cardFromSet.hasImage()) {
                candidate = cardFromSet;
                break;  // we're done here: found card **with Image**
            }
        }
        return candidate;  // any foil request already handled in getCardFromSet
    }

    @Override
    public int getMaxArtIndex(String cardName) {
        if (cardName == null)
            return IPaperCard.NO_ART_INDEX;
        int max = IPaperCard.NO_ART_INDEX;
        for (PaperCard pc : getAllCards(cardName)) {
            if (max < pc.getArtIndex()) {
                max = pc.getArtIndex();
            }
        }
        return max;
    }

    @Override
    public int getArtCount(String cardName, String setName) {
        int cnt = 0;
        if (cardName == null || setName == null)
            return cnt;

        Collection<PaperCard> cards = getAllCards(cardName);
        if (null == cards) {
            return 0;
        }

        for (PaperCard pc : cards) {
            if (pc.getEdition().equalsIgnoreCase(setName)) {
                cnt++;
            }
        }

        return cnt;
    }

    // returns a list of all cards from their respective latest (or preferred) editions
    @Override
    public Collection<PaperCard> getUniqueCards() {
        return uniqueCardsByName.values();
    }

    public Collection<PaperCard> getUniqueCardsNoAlt() {
        return Maps.filterEntries(this.uniqueCardsByName, new Predicate<Entry<String, PaperCard>>() {
            @Override
            public boolean apply(Entry<String, PaperCard> e) {
                if (e == null)
                    return false;
                return e.getKey().equals(e.getValue().getName());
            }
        }).values();
    }

    public PaperCard getUniqueByName(final String name) {
        return uniqueCardsByName.get(getName(name));
    }

    public Collection<ICardFace> getAllFaces() {
        return facesByName.values();
    }

    public ICardFace getFaceByName(final String name) {
        return facesByName.get(getName(name));
    }

    @Override
    public Collection<PaperCard> getAllCards() {
        return Collections.unmodifiableCollection(allCardsByName.values());
    }

    public Collection<PaperCard> getAllCardsNoAlt() {
        return Multimaps.filterEntries(allCardsByName, new Predicate<Entry<String, PaperCard>>() {
            @Override
            public boolean apply(Entry<String, PaperCard> entry) {
                return entry.getKey().equals(entry.getValue().getName());
            }
        }).values();
    }

    public Collection<PaperCard> getAllNonPromoCards() {
        return Lists.newArrayList(Iterables.filter(getAllCards(), new Predicate<PaperCard>() {
            @Override
            public boolean apply(final PaperCard paperCard) {
                CardEdition edition = null;
                try {
                    edition = editions.getEditionByCodeOrThrow(paperCard.getEdition());
                } catch (Exception ex) {
                    return false;
                }
                return edition != null && edition.getType() != Type.PROMO;
            }
        }));
    }

    public Collection<PaperCard> getAllNonPromosNonReprintsNoAlt() {
        return Lists.newArrayList(Iterables.filter(getAllCardsNoAlt(), new Predicate<PaperCard>() {
            @Override
            public boolean apply(final PaperCard paperCard) {
                CardEdition edition = null;
                try {
                    edition = editions.getEditionByCodeOrThrow(paperCard.getEdition());
                    if (edition.getType() == Type.PROMO||edition.getType() == Type.REPRINT)
                        return false;
                } catch (Exception ex) {
                    return false;
                }
                return true;
            }
        }));
    }

    public String getName(final String cardName) {
        if (alternateName.containsKey(cardName)) {
            return alternateName.get(cardName);
        }
        return cardName;
    }

    @Override
    public List<PaperCard> getAllCards(String cardName) {
        return allCardsByName.get(getName(cardName));
    }

    public List<PaperCard> getAllCardsNoAlt(String cardName) {
        return Lists.newArrayList(Multimaps.filterEntries(allCardsByName, new Predicate<Entry<String, PaperCard>>() {
            @Override
            public boolean apply(Entry<String, PaperCard> entry) {
                return entry.getKey().equals(entry.getValue().getName());
            }
        }).get(getName(cardName)));
    }

    /**
     * Returns a modifiable list of cards matching the given predicate
     */
    @Override
    public List<PaperCard> getAllCards(Predicate<PaperCard> predicate) {
        return Lists.newArrayList(Iterables.filter(getAllCards(), predicate));
    }

    /**
     * Returns a modifiable list of cards matching the given predicate
     */
    public List<PaperCard> getAllCardsNoAlt(Predicate<PaperCard> predicate) {
        return Lists.newArrayList(Iterables.filter(getAllCardsNoAlt(), predicate));
    }

    // Do I want a foiled version of these cards?
    @Override
    public Collection<PaperCard> getAllCards(CardEdition edition) {
        List<PaperCard> cards = Lists.newArrayList();

        for (CardInSet cis : edition.getAllCardsInSet()) {
            PaperCard card = this.getCard(cis.name, edition.getCode());
            if (card == null) {
                // Just in case the card is listed in the edition file but Forge doesn't support it
                continue;
            }

            cards.add(card);
        }
        return cards;
    }

    @Override
    public boolean contains(String name) {
        return allCardsByName.containsKey(getName(name));
    }

    @Override
    public Iterator<PaperCard> iterator() {
        return getAllCards().iterator();
    }

    @Override
    public Predicate<? super PaperCard> wasPrintedInSets(List<String> setCodes) {
        return new PredicateExistsInSets(setCodes);
    }

    private class PredicateExistsInSets implements Predicate<PaperCard> {
        private final List<String> sets;

        public PredicateExistsInSets(final List<String> wantSets) {
            this.sets = wantSets; // maybe should make a copy here?
        }

        @Override
        public boolean apply(final PaperCard subject) {
            for (PaperCard c : getAllCards(subject.getName())) {
                if (sets.contains(c.getEdition())) {
                    return true;
                }
            }
            return false;
        }
    }

    // This Predicate validates if a card was printed at [rarity], on any of its printings
    @Override
    public Predicate<? super PaperCard> wasPrintedAtRarity(CardRarity rarity) {
        return new PredicatePrintedAtRarity(rarity);
    }

    private class PredicatePrintedAtRarity implements Predicate<PaperCard> {
        private final Set<String> matchingCards;

        public PredicatePrintedAtRarity(CardRarity rarity) {
            this.matchingCards = new HashSet<>();
            for (PaperCard c : getAllCards()) {
                if (c.getRarity() == rarity) {
                    this.matchingCards.add(c.getName());
                }
            }
        }

        @Override
        public boolean apply(final PaperCard subject) {
            return matchingCards.contains(subject.getName());
        }
    }

    public StringBuilder appendCardToStringBuilder(PaperCard card, StringBuilder sb) {
        final boolean hasBadSetInfo = "???".equals(card.getEdition()) || StringUtils.isBlank(card.getEdition());
        sb.append(card.getName());
        if (card.isFoil()) {
            sb.append(CardDb.foilSuffix);
        }

        if (!hasBadSetInfo) {
            int artCount = getArtCount(card.getName(), card.getEdition());
            sb.append(CardDb.NameSetSeparator).append(card.getEdition());
            if (artCount >= IPaperCard.DEFAULT_ART_INDEX) {
                sb.append(CardDb.NameSetSeparator).append(card.getArtIndex()); // indexes start at 1 to match image file name conventions
            }
        }

        return sb;
    }

    public PaperCard createUnsupportedCard(String cardRequest) {

        CardRequest request = CardRequest.fromString(cardRequest);
        CardEdition cardEdition = CardEdition.UNKNOWN;
        CardRarity cardRarity = CardRarity.Unknown;

        // May iterate over editions and find out if there is any card named 'cardRequest' but not implemented with Forge script.
        if (StringUtils.isBlank(request.edition)) {
            for (CardEdition edition : editions) {
                for (CardInSet cardInSet : edition.getAllCardsInSet()) {
                    if (cardInSet.name.equals(request.cardName)) {
                        cardEdition = edition;
                        cardRarity = cardInSet.rarity;
                        break;
                    }
                }
                if (cardEdition != CardEdition.UNKNOWN) {
                    break;
                }
            }
        } else {
            cardEdition = editions.get(request.edition);
            if (cardEdition != null) {
                for (CardInSet cardInSet : cardEdition.getAllCardsInSet()) {
                    if (cardInSet.name.equals(request.cardName)) {
                        cardRarity = cardInSet.rarity;
                        break;
                    }
                }
            } else {
                cardEdition = CardEdition.UNKNOWN;
            }
        }

        // Note for myself: no localisation needed here as this goes in logs
        if (cardRarity == CardRarity.Unknown) {
            System.err.println("Forge could not find this card in the Database. Any chance you might have mistyped the card name?");
        } else {
            System.err.println("We're sorry, but this card is not supported yet.");
        }

        return new PaperCard(CardRules.getUnsupportedCardNamed(request.cardName), cardEdition.getCode(), cardRarity);

    }

    private final Editor editor = new Editor();

    public Editor getEditor() {
        return editor;
    }

    public class Editor {
        private boolean immediateReindex = true;

        public CardRules putCard(CardRules rules) {
            return putCard(rules, null); /* will use data from editions folder */
        }

        public CardRules putCard(CardRules rules, List<Pair<String, CardRarity>> whenItWasPrinted) {
            // works similarly to Map<K,V>, returning prev. value
            String cardName = rules.getName();

            CardRules result = rulesByName.get(cardName);
            if (result != null && result.getName().equals(cardName)) { // change properties only
                result.reinitializeFromRules(rules);
                return result;
            }

            result = rulesByName.put(cardName, rules);

            // 1. generate all paper cards from edition data we have (either explicit, or found in res/editions, or add to unknown edition)
            List<PaperCard> paperCards = new ArrayList<>();
            if (null == whenItWasPrinted || whenItWasPrinted.isEmpty()) {
                // TODO Not performant Each time we "putCard" we loop through ALL CARDS IN ALL editions
                for (CardEdition e : editions.getOrderedEditions()) {
                    int artIdx = IPaperCard.DEFAULT_ART_INDEX;
                    for (CardInSet cis : e.getAllCardsInSet()) {
                        if (!cis.name.equals(cardName)) {
                            continue;
                        }
                        paperCards.add(new PaperCard(rules, e.getCode(), cis.rarity, artIdx++));
                    }
                }
            } else {
                String lastEdition = null;
                int artIdx = 0;
                for (Pair<String, CardRarity> tuple : whenItWasPrinted) {
                    if (!tuple.getKey().equals(lastEdition)) {
                        artIdx = IPaperCard.DEFAULT_ART_INDEX;
                        lastEdition = tuple.getKey();
                    }
                    CardEdition ed = editions.get(lastEdition);
                    if (null == ed) {
                        continue;
                    }
                    paperCards.add(new PaperCard(rules, lastEdition, tuple.getValue(), artIdx++));
                }
            }
            if (paperCards.isEmpty()) {
                paperCards.add(new PaperCard(rules, CardEdition.UNKNOWN.getCode(), CardRarity.Special));
            }
            // 2. add them to db
            for (PaperCard paperCard : paperCards) {
                addCard(paperCard);
            }
            // 3. reindex can be temporary disabled and run after the whole batch of rules is added to db.
            if (immediateReindex) {
                reIndex();
            }
            return result;
        }

        public boolean isImmediateReindex() {
            return immediateReindex;
        }

        public void setImmediateReindex(boolean immediateReindex) {
            this.immediateReindex = immediateReindex;
        }
    }
}
