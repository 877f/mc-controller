package com.mccontroler;

import com.mccontroler.web.WebServer;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

/**
 * Client-side automation console.
 *
 * <p>This mod never runs on a dedicated server. It stands up a small HTTP server bound to
 * loopback, serves a control panel, and turns panel requests into Baritone work on the
 * client thread.
 */
@Mod(value = MCControler.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = MCControler.MODID, value = Dist.CLIENT)
public final class MCControler {

    public static final String MODID = "mccontroler";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static WebServer server;

    public MCControler(IEventBus modEventBus) {
        // Nothing to register yet; the server starts once the client is ready.
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // enqueueWork keeps us off the parallel mod-loading threads.
        event.enqueueWork(MCControler::startServer);
    }

    private static void startServer() {
        if (server != null) {
            return;
        }
        try {
            server = new WebServer();
            server.start();
            LOGGER.info("[MC Controler] control panel ready at {}", server.url());
        } catch (Exception e) {
            LOGGER.error("[MC Controler] failed to start the control panel", e);
            server = null;
        }
    }

    /** Stops the HTTP server. Safe to call when it was never started. */
    public static void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    public static WebServer server() {
        return server;
    }
}
