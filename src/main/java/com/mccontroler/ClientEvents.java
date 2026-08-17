package com.mccontroler;

import com.mccontroler.BotSettings;
import com.mccontroler.bot.BaritoneBridge;
import com.mccontroler.bot.Survival;
import com.mccontroler.inv.Screens;
import com.mccontroler.job.JobManager;
import com.mccontroler.place.Waypoints;
import com.mccontroler.web.EventStream;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client-thread hooks: drives the job queue and reacts to joining/leaving a world. */
@EventBusSubscriber(modid = MCControler.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    /** Ticks to wait on the death screen before respawning, so the log is readable. */
    private static final int RESPAWN_DELAY = 40;

    private static int deadTicks = -1;

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        handleDeath();
        Survival.tick();
        JobManager.get().tick();
        // Runs after the queue so a station screen that arrived late — the job that asked for it
        // having already finished, failed or been stopped — cannot strand the player's mouse.
        Screens.tick();
    }

    /**
     * Cancels work and respawns when the bot dies.
     *
     * <p>Unattended automation means nobody is watching to click "Respawn": without this the
     * client sits on the death screen indefinitely while jobs quietly fail against a corpse.
     * Dying also invalidates every assumption a running job holds — position, inventory, the
     * furnace it was standing at — so the queue is cleared rather than resumed.
     */
    private static void handleDeath() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) {
            deadTicks = -1;
            return;
        }

        if (player.isDeadOrDying()) {
            if (deadTicks < 0) {
                deadTicks = 0;
                EventStream.log("the bot died — cancelling work and respawning", "err");
                JobManager.get().stopAll();
                saveDeathPoint(player);
            } else if (++deadTicks == RESPAWN_DELAY && BotSettings.get("autoRespawn")) {
                player.respawn();
                Survival.onRespawn();
                EventStream.log("respawned — your gear is at the \"death\" place, "
                        + "send the bot back from the Places tab", "ok");
            }
        } else {
            deadTicks = -1;
        }
    }

    /**
     * Records where the bot died as a waypoint called "death".
     *
     * <p>Dying scatters the whole inventory on the ground, and without the coordinates it is
     * simply gone. Saving it as a normal waypoint means recovery is one click in the panel.
     */
    private static void saveDeathPoint(net.minecraft.client.player.LocalPlayer player) {
        try {
            var level = net.minecraft.client.Minecraft.getInstance().level;
            if (level == null) {
                return;
            }
            var pos = player.blockPosition();
            Waypoints.save("death", level.dimension().identifier().toString(),
                    pos.getX(), pos.getY(), pos.getZ());
            EventStream.log("died at " + pos.toShortString() + " — saved as the place \"death\"", "err");
        } catch (Exception e) {
            MCControler.LOGGER.error("[MC Controler] could not save the death point", e);
        }
    }

    @SubscribeEvent
    static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        // Baritone builds its settings per-session, so defaults are applied on every join.
        try {
            BaritoneBridge.applyDefaults();
        } catch (Throwable t) {
            MCControler.LOGGER.error("[MC Controler] could not configure Baritone", t);
            EventStream.log("Baritone is not responding — is it installed?", "err");
        }
        WebServerInfo.announce();
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // Leaving the world invalidates every position a job was holding on to.
        JobManager.get().stopAll();
        EventStream.log("left the world — jobs cleared");
    }

    /** Reminds the player where the panel is, once they are actually in a world. */
    private static final class WebServerInfo {
        static void announce() {
            var server = MCControler.server();
            if (server != null) {
                EventStream.log("control panel: " + server.url(), "ok");
            }
        }
    }
}
