package com.example.tradetracker;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.api.util.Text;
import org.pf4j.Extension;
import net.runelite.client.plugins.PluginType;

import javax.inject.Inject;
import java.util.ArrayList;

import static net.runelite.api.ChatMessageType.PUBLICCHAT;
import static net.runelite.api.ChatMessageType.TRADEREQ;

@Extension
@Slf4j
@PluginDescriptor(
        name = "Trade Tracker",
        description = "Hides trades from players already traded",
        tags = {"farming", "minigame", "overlay", "skilling", "timers"},
        type = PluginType.UTILITY
)
public class TradeTrackerPlugin extends Plugin {

    private final CharMatcher jagexPrintableCharMatcher = Text.JAGEX_PRINTABLE_CHAR_MATCHER;
    private static final Splitter NEWLINE_SPLITTER = Splitter
            .on("\n")
            .omitEmptyStrings()
            .trimResults();

    @Inject
    private Client client;

    @Inject
    private TradeTrackerConfig config;

    @Provides
    TradeTrackerConfig getConfig(ConfigManager configManager) {
        return configManager.getConfig(TradeTrackerConfig.class);
    }

    private ArrayList<String> usersTradedAlready = new ArrayList<String>();
    private ArrayList<String> whiteListedPlayers = new ArrayList<>();
    private ArrayList<String> advertisers = new ArrayList<>();
    private ArrayList<String> adWords = new ArrayList<>();

    @Override
    protected void startUp() throws Exception {
        updateWhiteListedNames();
        updateAdWords();

        client.refreshChat();
    }

    @Override
    protected void shutDown() throws Exception {
        usersTradedAlready.clear();
        advertisers.clear();
        client.refreshChat();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!"tradetracker".equals(event.getGroup())) {
            return;
        }

        updateWhiteListedNames();
        updateAdWords();

        //Refresh chat after config change to reflect current rules
        client.refreshChat();
    }

    @Subscribe
    public void onOverheaadTextChangged(OverheadTextChanged event) {
        if (!(event.getActor() instanceof Player)) {
            return;
        }

        String playerName = getPlayersName(event.getActor().getName());
        String playersMessage = event.getOverheadText().toLowerCase();

        boolean seenAdvertising = checkMessageForAds(playersMessage);

        System.out.println(seenAdvertising + ": " + playerName.toUpperCase() + " from overhead: " + playersMessage);

        // if all words found and user isn't in advertisers group, add them
        if (seenAdvertising) {
            if (!advertisers.contains(playerName)) {
                advertisers.add(playerName);
            }
        }
    }

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent event) {
        if (!"chatFilterCheck".equals(event.getEventName())) {
            return;
        } else {
            int[] intStack = client.getIntStack();
            int intStackSize = client.getIntStackSize();
            int messageType = intStack[intStackSize - 2];
            int messageId = intStack[intStackSize - 1];

            ChatMessageType chatMessageType = ChatMessageType.of(messageType);
            MessageNode messageNode = client.getMessages().get(messageId);
            String playerName = getPlayersName(messageNode.getName());

            if (chatMessageType == TRADEREQ) {

                // if user is whitelisted, always show them
                if (whiteListedPlayers.contains(playerName)) {
                    return;
                } else {

                    // If only whitelist, don't show any
                    if (config.onlyShowWhitelist()) {
                        intStack[intStackSize - 3] = 0;
                    } else {
                        // if show ads is on, show trades if user has advertised
                        String oldmessage = messageNode.getValue();

                        if (usersTradedAlready.contains(playerName)) {

                            String[] stringStack = client.getStringStack();
                            int stringStackSize = client.getStringStackSize();

                            //String message = stringStack[stringStackSize - 1];
                            stringStack[stringStackSize - 1] = oldmessage + " - PAID!";
                        } else if (advertisers.contains(playerName)) {
                            if (!oldmessage.contains("seen advertising")) {
                                String[] stringStack = client.getStringStack();
                                int stringStackSize = client.getStringStackSize();

                                //String message = stringStack[stringStackSize - 1];
                                stringStack[stringStackSize - 1] = oldmessage + " - seen advertising.";
                            }
                        }

                        if (config.onlyShowAdvertisers()) {
                            if (!advertisers.contains(playerName)) {
                                intStack[intStackSize - 3] = 0;
                            }
                        }

                        // If hide paid ads is on, remove trades from players who have been paid
                        if (config.hidePaidAdvertisers()) {
                            if (usersTradedAlready.contains(playerName)) {
                                intStack[intStackSize - 3] = 0;
                            }
                        }
                    }
                }
            } else if (chatMessageType == PUBLICCHAT) {
                // skip messages for people who have already been seen advertising
                // this avoids unnecessary looping of each message
                if (!advertisers.contains(playerName)) {
                    String playersMessage = messageNode.getValue().toLowerCase();

                    boolean seenAdvertising = checkMessageForAds(playersMessage);

                    System.out.println(seenAdvertising + ": " + playerName.toUpperCase() + " from message: " + playersMessage);

                    // if seen advertising and not in the list, add them
                    if (seenAdvertising) {
                        if (!advertisers.contains(playerName)) {
                            advertisers.add(playerName);
                        }
                    }
                }
            } else {
                System.out.println(chatMessageType);
            }
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        // if widget is 334 (second trade screen), add user to users traded
        if (event.getGroupId() == 334) {
            Widget widget = client.getWidget(334, 1);
            //Widget widget = client.getWidget(WidgetInfo.PLAYER_TRADE_FINAL_SCREEN);
            String playerName = widget.getStaticChildren()[27].getText();
            String tradedItem = widget.getStaticChildren()[25].getDynamicChildren()[0].getText();

            playerName = playerName.split("<br>")[1];
            playerName = getPlayersName(playerName);

            if (tradedItem.toLowerCase().contains(config.itemTraded())) {
                System.out.println("traded " + config.itemTraded() + " to " + playerName);
                usersTradedAlready.add(playerName);
            }
        }
    }

    // pass in a message and it will compare to the words on the adwords plugin
    boolean checkMessageForAds(String message) {
        boolean allWordsFound = true;

        for (String word : adWords) {
            if (!message.contains(word.toLowerCase())) {
                allWordsFound = false;
            }
        }

        return allWordsFound;
    }

    public void updateWhiteListedNames() {
        whiteListedPlayers.clear();

        NEWLINE_SPLITTER.splitToList(config.whitelistedPlayers()).stream().forEach((player) -> whiteListedPlayers.add(player.toLowerCase()));
    }

    public void updateAdWords() {
        adWords.clear();

        NEWLINE_SPLITTER.splitToList(config.adWords()).stream().forEach((word) -> adWords.add(word.toLowerCase()));
    }

    public String getPlayersName(String message) {
        return jagexPrintableCharMatcher.retainFrom(message).replace('\u00A0', ' ').toLowerCase();
    }
}