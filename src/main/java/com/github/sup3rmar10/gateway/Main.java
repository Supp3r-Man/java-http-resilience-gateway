package com.github.sup3rmar10.gateway;

import java.nio.file.Path;

/** Usage: java -cp target/classes com.github.sup3rmar10.gateway.Main config/gateway.properties */
public final class Main {
    private Main() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: Main <config.properties>");
            System.exit(2);
        }
        GatewayConfig cfg = GatewayConfig.load(Path.of(args[0]));
        Gateway gw = new Gateway(cfg);
        gw.start();
        Runtime.getRuntime().addShutdownHook(new Thread(gw::close));
        System.out.println("gateway listening on " + cfg.host + ":" + gw.port() + " (metrics at /metrics)");
        Thread.currentThread().join();
    }
}
