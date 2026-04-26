package test;

import parser.ASTNode;
import parser.Parser;
import semantic.SemanticAnalyzer;
import semantic.SemanticException;
import testsupport.TestCatalogFactory;

import java.util.Scanner;

public class TestSemantic {
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

            SemanticAnalyzer analyzer = new SemanticAnalyzer(TestCatalogFactory.create());
            analyzer.analyze(ast);

            System.out.println("语义分析通过");
        } catch (SemanticException e) {
            System.err.println("语义分析失败：" + e.getMessage());
        } catch (RuntimeException e) {
            System.err.println("解析失败：" + e.getMessage());
        }
    }
}
