package com.mccontroler.inv;

import com.mccontroler.job.JobManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;

/**
 * Closes containers the bot opened without stranding the player's mouse.
 *
 * <p>{@link LocalPlayer#closeContainer()} on its own is not enough. It swaps the menu back to the
 * inventory and tells the server, but leaves the GUI screen standing — and a screen that is still
 * open keeps the mouse released, because {@code Gui.setScreen(null)} is the only call that grabs
 * it back. Worse, that screen is now bound to a menu the client has already discarded, so Escape
 * does not reliably dismiss it. The result was a dead cursor that only a relog fixed.
 */
public final class Screens {

    /**
     * True once the bot has asked a station to open.
     *
     * <p>Opening is a server round trip: the bot sends the interaction and the screen arrives some
     * ticks later. A job that ends in between — finished, failed or stopped — leaves nobody to
     * close the screen that is still in flight, so {@link #tick()} sweeps it up afterwards. The
     * flag keeps that sweep from ever touching a chest the player opened themselves.
     */
    private static boolean botOpened;

    private Screens() {
    }

    /** Called right before the bot right-clicks a station, so a late screen can be cleaned up. */
    public static void expectOpen() {
        botOpened = true;
    }

    /** Closes any container the bot has open and gives the mouse back to the player. */
    public static void closeAny() {
        botOpened = false;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        // Dismissing the screen is what re-grabs the mouse, so it has to happen even when the
        // menu has already been reset.
        if (mc.gui.screen() instanceof AbstractContainerScreen<?>) {
            mc.gui.setScreen(null);
        }
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    /**
     * Catches a station screen that arrived after the job asking for it had already ended.
     *
     * <p>Only runs while nothing is queued, so a screen the bot is legitimately using mid-job is
     * left alone.
     */
    public static void tick() {
        if (!botOpened || JobManager.get().isBusy()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            botOpened = false;
            return;
        }
        if (mc.gui.screen() instanceof AbstractContainerScreen<?>) {
            closeAny();
        }
    }
}
