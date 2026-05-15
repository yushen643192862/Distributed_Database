package edu.minisql.app;

import edu.minisql.cluster.MiniSqlSystem;

import java.util.Scanner;

public class App {
    public static void main(String[] args) {
        MiniSqlSystem system = MiniSqlSystem.bootstrapDemoCluster();

        System.out.println("Distributed MiniSQL");
        System.out.println("Data file: " + system.persistencePath().toAbsolutePath());
        System.out.println("Type SQL and press Enter. Type exit to quit.");
        System.out.println();

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("minisql> ");
                if (!scanner.hasNextLine()) {
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
}
