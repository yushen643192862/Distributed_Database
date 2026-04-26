package test;

import parser.ASTNode;
import parser.ASTPrinter;
import parser.Parser;

import java.util.Scanner;

public class TestParser {
    public static void main(String[] args) {
        String sql;
        if (args.length > 0) {
            sql = String.join(" ", args);
        } else {
            Scanner scanner = new Scanner(System.in);
            System.out.println("请输入 SQL：");
            sql = scanner.nextLine();
        }

        try {
            Parser parser = new Parser(sql);
            ASTNode ast = parser.parseStatement();

            ASTPrinter printer = new ASTPrinter();
            System.out.println(printer.print(ast));
        } catch (RuntimeException e) {
            System.err.println("解析失败：" + e.getMessage());
        }
    }
}
