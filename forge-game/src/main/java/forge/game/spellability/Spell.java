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
package forge.game.spellability;

import com.google.common.collect.Sets;

import forge.card.CardStateName;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CardUtil;
import forge.game.cost.Cost;
import forge.game.cost.CostPayment;
import forge.game.player.Player;
import forge.game.staticability.StaticAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

/**
 * <p>
 * Abstract Spell class.
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public abstract class Spell extends SpellAbility implements java.io.Serializable, Cloneable {

    /** Constant <code>serialVersionUID=-7930920571482203460L</code>. */
    private static final long serialVersionUID = -7930920571482203460L;

    private boolean castFaceDown = false;

    /**
     * <p>
     * Constructor for Spell.
     * </p>
     * 
     * @param sourceCard
     *            a {@link forge.game.card.Card} object.
     */
    public Spell(final Card sourceCard) {
        this(sourceCard, new Cost(sourceCard.getManaCost(), false));
    }
    public Spell(final Card sourceCard, final Cost abCost) {
        super(sourceCard, abCost);

        this.setStackDescription(sourceCard.getSpellText());
        this.getRestrictions().setZone(ZoneType.Hand);
    }

    /** {@inheritDoc} */
    @Override
    public boolean canPlay() {
        final Card card = this.getHostCard();
        Player activator = this.getActivatingPlayer();
        if (activator == null) {
            activator = card.getController();
            if (activator == null) {
            	return false;
            }
        }

        final Game game = activator.getGame();
        if (game.getStack().isSplitSecondOnStack()) {
            return false;
        }

        boolean isInstant = card.isInstant();
        // special case for split cards
        if (card.isSplitCard()) {
            CardStateName name = isLeftSplit() ? CardStateName.LeftSplit : CardStateName.RightSplit;
            isInstant = card.getState(name).getType().isInstant();
        }

        boolean flash = card.hasKeyword("Flash");

        if (this.hasParam("Bestow") && !card.isBestowed() && !card.isInZone(ZoneType.Battlefield)) {
            // Rule 601.3: cast Bestow with Flash
            // for the check the card does need to be animated
            // otherwise the StaticAbility will not found them
            
            Card lki = card.isLKI() ? card : CardUtil.getLKICopy(card);
            
            lki.animateBestow(false);

            CardCollection preList = new CardCollection(lki);
            game.getAction().checkStaticAbilities(false, Sets.newHashSet(lki), preList);
            
            flash = lki.hasKeyword("Flash");
            
            // need to check again to reset the Keywords and other effects
            if (card.isLKI()) {
                lki.unanimateBestow(false);
                game.getAction().checkStaticAbilities(false, Sets.newHashSet(lki), preList);
            }
        }


        if (!(isInstant || activator.canCastSorcery() || flash
               || this.getRestrictions().isInstantSpeed()
               || activator.hasKeyword("You may cast nonland cards as though they had flash.")
               || card.hasStartOfKeyword("You may cast CARDNAME as though it had flash.")
               || this.hasSVar("IsCastFromPlayEffect")
               || (card.isFaceDown() && !card.getLastKnownZone().is(ZoneType.Battlefield) && card.getState(CardStateName.Original).getType().isInstant()))) {
            return false;
        }

        if (!this.getRestrictions().canPlay(card, this)) {
            return false;
        }

        // for uncastables like lotus bloom, check if manaCost is blank (except for morph spells)
        if (!isCastFaceDown() && isBasicSpell() && card.getState(card.isFaceDown() ? CardStateName.Original : card.getCurrentStateName()).getManaCost().isNoCost()) {
            return false;
        }

        if (this.getPayCosts() != null) {
            if (!CostPayment.canPayAdditionalCosts(this.getPayCosts(), this)) {
                return false;
            }
        }
        // legendary sorcery
        if (card.isSorcery() && card.getType().isLegendary() &&
                CardLists.getValidCards(activator.getCardsIn(ZoneType.Battlefield), "Creature.Legendary,Planeswalker.Legendary", card.getController(), card).isEmpty()) {
            return false;
        }
        return checkOtherRestrictions();
    } // canPlay()
    
    public boolean checkOtherRestrictions() {
        final Card source = this.getHostCard();
        Player activator = getActivatingPlayer();
        final Game game = activator.getGame();
        // CantBeCast static abilities
        final CardCollection allp = new CardCollection(game.getCardsIn(ZoneType.listValueOf("Battlefield,Command")));
        allp.add(source);
        for (final Card ca : allp) {
            final FCollectionView<StaticAbility> staticAbilities = ca.getStaticAbilities();
            for (final StaticAbility stAb : staticAbilities) {
                if (stAb.applyAbility("CantBeCast", source, activator)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public final Object clone() {
        try {
            return super.clone();
        } catch (final Exception ex) {
            throw new RuntimeException("Spell : clone() error, " + ex);
        }
    }

    @Override
    public boolean isSpell() { return true; }
    @Override
    public boolean isAbility() { return false; }


    /**
     * @return the castFaceDown
     */
    public boolean isCastFaceDown() {
        return castFaceDown;
    }

    /**
     * @param faceDown the castFaceDown to set
     */
    public void setCastFaceDown(boolean faceDown) {
        this.castFaceDown = faceDown;
    }

}
