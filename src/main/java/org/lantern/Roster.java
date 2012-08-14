package org.lantern;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.TreeSet;

import org.jivesoftware.smack.RosterEntry;
import org.jivesoftware.smack.RosterListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.packet.Presence;
import org.littleshoot.commom.xmpp.XmppP2PClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSortedSet;

/**
 * Class that keeps track of all roster entries.
 */
public class Roster implements RosterListener {

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private Collection<LanternRosterEntry> rosterEntries = 
        new TreeSet<LanternRosterEntry>();
    
    private final Collection<String> incomingSubscriptionRequests = 
        new HashSet<String>();

    private final XmppHandler xmppHandler;

    private volatile boolean populated;
    
    /**
     * Creates a new roster.
     */
    public Roster(final XmppHandler xmppHandler) {
        this.xmppHandler = xmppHandler;
    }

    public void loggedIn() {
        log.info("Got logged in event");
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                final XMPPConnection conn = 
                    xmppHandler.getP2PClient().getXmppConnection();
                
                final org.jivesoftware.smack.Roster roster = conn.getRoster();
                roster.setSubscriptionMode(
                    org.jivesoftware.smack.Roster.SubscriptionMode.manual);
                roster.addRosterListener(Roster.this);
                final Collection<RosterEntry> unordered = roster.getEntries();
                
                final Collection<LanternRosterEntry> entries = 
                    LanternUtils.getRosterEntries(unordered);
                rosterEntries = entries;
                
                for (final RosterEntry entry : unordered) {
                    final Iterator<Presence> presences = 
                        roster.getPresences(entry.getUser());
                    while (presences.hasNext()) {
                        final Presence p = presences.next();
                        processPresence(p);
                    }
                }
                populated = true;
            }
        };
        final Thread t = new Thread(r, "Roster-Populating-Thread");
        t.setDaemon(true);
        t.start();
    }
    
    private void processPresence(final Presence presence) {
        final String from = presence.getFrom();
        log.debug("Got presence: {}", presence.toXML());
        if (LanternUtils.isLanternHub(from)) {
            log.info("Got Lantern hub presence");
        } else if (LanternUtils.isLanternJid(from)) {
            this.xmppHandler.addOrRemovePeer(presence, from);
            onPresence(presence);
        } else {
            onPresence(presence);
        }
    }
    
    private void onPresence(final Presence pres) {
        log.info("Got presence!! {}", pres);
        final String email = LanternUtils.jidToEmail(pres.getFrom());
        synchronized (rosterEntries) {
            for (final LanternRosterEntry entry : rosterEntries) {
                if (entry.getEmail().equals(email)) {
                    entry.setAvailable(pres.isAvailable());
                    entry.setStatus(pres.getStatus());
                    return;
                }
            }
        }
        // This may be someone we have subscribed to who we're just now
        // getting the presence for.
        log.info("Adding non-roster presence: {}", email);
        addEntry(new LanternRosterEntry(pres));
    }

    private void addEntry(final LanternRosterEntry pres) {
        final boolean added = rosterEntries.add(pres);
        if (!added) {
            log.warn("Could not add {}", pres);
        }
    }
    
    public Collection<LanternRosterEntry> getEntries() {
        synchronized (this.rosterEntries) {
            return ImmutableSortedSet.copyOf(this.rosterEntries);
        }
    }

    public void addIncomingSubscriptionRequest(final String from) {
        incomingSubscriptionRequests.add(from);
        LanternHub.asyncEventBus().post(new RosterStateChangedEvent());
    }
    

    public void removeIncomingSubscriptionRequest(final String from) {
        incomingSubscriptionRequests.remove(from);
        LanternHub.asyncEventBus().post(new RosterStateChangedEvent());
    }

    public Collection<String> getSubscriptionRequests() {
        return incomingSubscriptionRequests;
    }

    @Override
    public void entriesAdded(final Collection<String> entries) {
        log.debug("Adding entries: {} for roster: {}", entries, this);
        for (final String entry : entries) {
            addEntry(new LanternRosterEntry(entry));
        }
        LanternHub.asyncEventBus().post(new RosterStateChangedEvent());
    }

    @Override
    public void entriesDeleted(final Collection<String> entries) {
        log.debug("Roster entries deleted: {}", entries);
        for (final String entry : entries) {
            final String email = LanternUtils.jidToEmail(entry);
            synchronized (rosterEntries) {
                for (final LanternRosterEntry lre : rosterEntries) {
                    // We remove both because we're not sure what form it's 
                    // stored in.
                    if (lre.getEmail().equals(email) || 
                        lre.getEmail().equals(entry)) {
                        rosterEntries.remove(lre);
                        break;
                    }
                }
            }
        }
        LanternHub.asyncEventBus().post(new RosterStateChangedEvent());
    }

    @Override
    public void entriesUpdated(final Collection<String> entries) {
        // Not sure what to do with this one -- initiate a request for updated
        // info about each entry in the list?
        log.debug("Entries updated: {} for roster: {}", entries, this);
        for (final String entry : entries) {
            final Presence pres = 
                this.xmppHandler.getP2PClient().getXmppConnection().getRoster().getPresence(entry);
            onPresence(pres);
        }
        LanternHub.asyncEventBus().post(new RosterStateChangedEvent());
    }

    @Override
    public void presenceChanged(final Presence pres) {
        log.debug("Got presence changed event.");
        processPresence(pres);
        //LanternHub.asyncEventBus().post(new RosterStateChangedEvent());
    }
    

    public boolean populated() {
        return this.populated;
    }
    
    public void reset() {
        this.incomingSubscriptionRequests.clear();
        this.rosterEntries.clear();
        this.populated = false;
    }
    
    @Override
    public String toString() {
        String id = "";
        final XmppP2PClient client = this.xmppHandler.getP2PClient();
        if (client != null) {
            final XMPPConnection conn = client.getXmppConnection();
            id = conn.getUser();
        }
        return "Roster for "+id+" [rosterEntries=" + rosterEntries + "]";
    }
}
