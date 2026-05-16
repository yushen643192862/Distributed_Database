package minisql.app;

import minisql.cluster.MiniSqlSystem;

import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

public class App {
    public static void main(String[] args) {
        MiniSqlSystem system = MiniSqlSystem.bootstrapDemoCluster();

        System.out.println("Distributed MiniSQL");
        System.out.println("Data file: " + system.persistencePath().toAbsolutePath());
        System.out.println("Master RPC: http://127.0.0.1:" + system.masterRpcPort() + "/rpc");
        System.out.println("Type SQL and press Enter. Type exit to quit.");
        System.out.println();

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("minisql> ");
                if (!scanner.hasNextLine()) {
                    if (serverMode()) {
                        System.out.println();
                        System.out.println("No interactive stdin; master RPC server remains online.");
                        awaitForever();
                    }
                    break;
                }

                String line = scanner.nextLine().trim();
                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }

                try {
                    long start = System.nanoTime();
                    System.out.println(system.execute(line));
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    System.out.println("(" + elapsedMs + " ms)");
                } catch (RuntimeException ex) {
                    System.out.println("ERROR: " + ex.getMessage());
                }
            }
        }
    }

    private static boolean serverMode() {
        String value = System.getenv("MINISQL_SERVER_MODE");
        return value != null && Boolean.parseBoolean(value);
    }

    private static void awaitForever() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
