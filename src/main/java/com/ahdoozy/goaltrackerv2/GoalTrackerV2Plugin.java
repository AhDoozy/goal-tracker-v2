package com.ahdoozy.goaltrackerv2;



import com.ahdoozy.goaltrackerv2.models.task.*;
import com.google.inject.Provides;
import com.ahdoozy.goaltrackerv2.models.enums.TaskType;
import com.ahdoozy.goaltrackerv2.services.TaskIconService;
import com.ahdoozy.goaltrackerv2.services.TaskUpdateService;
import com.ahdoozy.goaltrackerv2.ui.GoalTrackerPanel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.SessionOpen;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.chatbox.ChatboxItemSearch;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.AsyncBufferedImage;

import javax.inject.Inject;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.stream.IntStream;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.util.ColorUtil;

@Slf4j
@PluginDescriptor(name = "Goal Tracker V2", description = "Keep track of your goals and complete them automatically")
/**
 * Main entry point for the Goal Tracker plugin.
 * Handles lifecycle (startup/shutdown), UI registration, and listens for
 * RuneLite events to update tasks and goals automatically.
 */
public final class GoalTrackerV2Plugin extends Plugin
{
    public static final int[] PLAYER_INVENTORIES = {
            InventoryID.INVENTORY.getId(),
            InventoryID.EQUIPMENT.getId(),
            InventoryID.BANK.getId(),
            InventoryID.SEED_VAULT.getId(),
            InventoryID.GROUP_STORAGE.getId()
    };

    @Getter
    @Inject
    private Client client;

    @Getter
    @Inject
    private SkillIconManager skillIconManager;

    @Getter
    @Inject
    private ItemManager itemManager;

    @Getter
    @Inject
    private ChatboxItemSearch itemSearch;

    @Getter
    @Inject
    private ChatboxPanelManager chatboxPanelManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Getter
    @Inject
    private ClientThread clientThread;

    @Getter
    @Inject
    private ItemCache itemCache;

    @Inject
    private ChatMessageManager chatMessageManager;

    @Getter
    @Inject
    private GoalTrackerV2Config config;

    @Getter
    @Inject
    private TaskUpdateService taskUpdateService;

    @Getter
    @Inject
    private TaskIconService taskIconService;

    @Getter
    @Inject
    private TaskUIStatusManager uiStatusManager;

    @Getter
    @Inject
    private GoalManager goalManager;

    @Inject
    private GoalTrackerPanel goalTrackerPanel;

    private NavigationButton uiNavigationButton;

    @Setter
    private boolean validateAll = true;

    private boolean warmedIcons = false;

    // Debounced UI refresh timer (coalesces many varbit changes into one repaint)
    private Timer uiRefreshTimer;

    private void notifyTask(Task task)
    {
        if (task == null) { return; }

        try {
            // Use existing config color for chat prefix
            final Color chosen = config.completionMessageColor();
            final String prefix = ColorUtil.wrapWithColorTag("Goal Tracker", chosen);

            final String msg = prefix + ": Completed task — " + task;

            chatMessageManager.queue(
                QueuedMessage.builder()
                    .type(ChatMessageType.GAMEMESSAGE)
                    .runeLiteFormattedMessage(msg)
                    .build()
            );
        }
        catch (Exception ex) {
            log.warn("notifyTask failed", ex);
        }
    }

    /**
     * Schedule a debounced refresh of the sidebar panel. If a refresh is already
     * scheduled, it will be replaced with the new delay. This prevents spammy
     * repainting during rapid varbit changes.
     */
    private void schedulePanelRefresh(final int delayMs)
    {
        if (goalTrackerPanel == null)
        {
            return;
        }
        if (uiRefreshTimer != null && uiRefreshTimer.isRunning())
        {
            uiRefreshTimer.stop();
        }
        uiRefreshTimer = new Timer(delayMs, e -> SwingUtilities.invokeLater(goalTrackerPanel::refresh));
        uiRefreshTimer.setRepeats(false);
        uiRefreshTimer.start();
    }

    /**
     * Preloads commonly used item icons so they render instantly in the UI.
     */
    public void warmItemIcons()
    {
        try
        {
            // Example: warm up the TODO_LIST icon at minimum
            itemManager.getImage(ItemID.TODO_LIST);

            // Warm up skill icons
            for (Skill skill : Skill.values())
            {
                skillIconManager.getSkillImage(skill);
            }
        }
        catch (Exception ex)
        {
            log.warn("warmItemIcons failed", ex);
        }
    }

    @Override
    protected void startUp()
    {
        // Defensive guards to avoid NPEs during test bootstrap if DI bindings are missing
        if (goalManager == null || itemCache == null || goalTrackerPanel == null || itemManager == null || clientToolbar == null)
        {
            log.warn("GoalTrackerV2Plugin: skipping full startup because a dependency was null. goalManager={}, itemCache={}, panel={}, itemManager={}, toolbar={}",
                    goalManager != null, itemCache != null, goalTrackerPanel != null, itemManager != null, clientToolbar != null);
            return;
        }

        try {
            goalManager.load();
            itemCache.load();
        } catch (Exception ex) {
            log.error("GoalTrackerV2Plugin: failed to load persisted state", ex);
        }

        goalTrackerPanel.home();

        final AsyncBufferedImage icon = itemManager.getImage(ItemID.TODO_LIST);
        if (icon == null)
        {
            log.warn("GoalTrackerV2Plugin: icon was null; skipping sidebar button creation");
        }
        else
        {
            icon.onLoaded(() -> {
                uiNavigationButton = NavigationButton.builder()
                        .tooltip("Goal Tracker")
                        .icon(icon)
                        .priority(7)
                        .panel(goalTrackerPanel)
                        .build();

                clientToolbar.addNavigation(uiNavigationButton);
            });
        }

        goalTrackerPanel.onGoalUpdated((goal) -> goalManager.save());
        goalTrackerPanel.onTaskAdded((task) -> {
            if (taskUpdateService.update(task)) {
                if (task.getStatus().isCompleted()) {
                    notifyTask(task);
                }

                uiStatusManager.refresh(task);
            }

            goalManager.save();
        });
        goalTrackerPanel.onTaskUpdated((task) -> goalManager.save());

        // Preload item icons at plugin startup so they are visible immediately
        warmItemIcons();
        warmedIcons = true; // avoid re-warming on first login tick
    }

    @Override
    protected void shutDown()
    {
        if (uiNavigationButton != null)
        {
            clientToolbar.removeNavigation(uiNavigationButton);
            uiNavigationButton = null;
        }
    }

    @Subscribe
    public void onSessionOpen(SessionOpen event)
    {
        if (goalManager != null) {
            try { goalManager.load(); } catch (Exception ex) { log.error("Failed to load goals on session open", ex); }
        }
        if (goalTrackerPanel != null) {
            goalTrackerPanel.refresh();
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        List<SkillLevelTask> skillLevelTasks = goalManager.getIncompleteTasksByType(TaskType.SKILL_LEVEL);
        for (SkillLevelTask task : skillLevelTasks) {
            if (!taskUpdateService.update(task, event)) continue;
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            // Re-check quest tasks after login
            clientThread.invokeLater(() -> refreshQuestTasks());

            // Refresh the panel once, 10s after login, after detection settles
            schedulePanelRefresh(10_000);
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        // Quest progress often updates via varbits/varps
        clientThread.invokeLater(() -> refreshQuestTasks());

        // Debounce UI refresh during rapid quest varbit updates
        schedulePanelRefresh(750);
    }

    private void refreshQuestTasks()
    {
        if (goalManager == null || client == null) return;
        List<QuestTask> questTasks = goalManager.getIncompleteTasksByType(TaskType.QUEST);
        for (QuestTask task : questTasks)
        {
            task.refreshStatus(client);
            uiStatusManager.refresh(task);
            if (task.getStatus().isCompleted())
            {
                notifyTask(task);
            }
        }
    }
    @Provides
    public GoalTrackerV2Config provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(GoalTrackerV2Config.class);
    }
}