package test;

import parser.ASTNode;
import parser.Parser;
import semantic.SemanticAnalyzer;
import testsupport.TestCatalogFactory;

public class SemanticTestSuite {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        shouldPass("select id, name from users where age > 18;");
        shouldPass("select users.id, orders.total from users join orders on users.id = orders.user_id;");
        shouldPass("select u.id, o.total from users u join orders o on u.id = o.user_id;");
        shouldPass("insert into users (id, name) values (1, 'Tom');");
        shouldPass("insert into users values (1, 'Tom', null, null);");
        // ==================== 一、正确 SELECT（10 条）✅ ====================
        shouldPass("select id, name from users where age > 18;");
        shouldPass("select * from users;");
        shouldPass("select users.id, users.name from users;");
        shouldPass("select u.id, u.name from users u;");
        shouldPass("select id, name from users where age >= 18 and age <= 60;");
        shouldPass("select id, name from users where email is not null;");
        shouldPass("select count(*) from users;");
        shouldPass("select avg(age), max(age) from users;");
        shouldPass("select id, name from users order by id desc;");
        shouldPass("select id, name from users limit 10 offset 5;");

        // ==================== 二、错误列名（10 条）❌ ====================
        shouldFail("select email2 from users;", "Unknown column");
        shouldFail("select id, name2 from users;", "Unknown column");
        shouldFail("select users.email2 from users;", "Unknown column");
        shouldFail("select u.email2 from users u;", "Unknown column");
        shouldFail("select id from users where age2 > 18;", "Unknown column");
        shouldFail("select id from users order by name2;", "Unknown column");
        shouldFail("select id from users group by email2;", "Unknown column");
        shouldFail("select id from users having age2 > 18;", "Unknown column");
        shouldFail("update users set name2 = 'Alice' where id = 1;", "Unknown column");
        shouldFail("delete from users where email2 = 'test@test.com';", "Unknown column");

        // ==================== 三、错误表名（10 条）❌ ====================
        shouldFail("select id from student;", "Unknown table");
        shouldFail("select * from unknown_table;", "Unknown table");
        shouldFail("insert into unknown_table values (1, 'test');", "Unknown table");
        shouldFail("update unknown_table set name = 'test';", "Unknown table");
        shouldFail("delete from unknown_table where id = 1;", "Unknown table");
        shouldFail("select * from users join unknown_table on users.id = unknown_table.user_id;", "Unknown table");
        shouldFail("select * from users u join orders o on u.id = o.user_id join products p on o.id = p.id join unknown_table;", "Unknown table");
        shouldFail("drop table unknown_table;", "Unknown table");
        shouldFail("alter table unknown_table add column age int;", "Unknown table");
        shouldFail("truncate table unknown_table;", "Unknown table");

        // ==================== 四、JOIN / 别名（9 条）❌ ====================
        shouldFail("select users.id from users u;", "Unknown table or alias");
        shouldFail("select u.id, u.name from users;", "Unknown table or alias");
        shouldFail("select id from users join orders on users.id = orders.user_id;", "Ambiguous column");
        shouldFail("select name from users u join orders o on u.id = o.user_id join products p on o.id = p.id;", "Ambiguous column");
        shouldFail("select users.id from users u join orders o on u.id = o.user_id;", "Unknown table or alias");
        shouldFail("select u.id from users join orders o on users.id = o.user_id;", "Unknown table or alias");
        shouldFail("select users.id from users u;", "Unknown table or alias");
        shouldFail("select u.id from users u join orders u on u.id = u.user_id;", "Duplicate table alias");
        shouldFail("select id from users join orders where users.id = orders.user_id;", "Ambiguous column");

        // ==================== 五、INSERT 类型错误（10 条）❌ ====================
        shouldFail("insert into users (id, name, age) values ('abc', 'Alice', 25);", "Type mismatch");
        shouldFail("insert into users values (1, 'Alice', 'old', 'alice@test.com');", "Type mismatch");
        shouldFail("insert into orders (id, user_id, total) values (1, 'abc', 100.00);", "Type mismatch");
        shouldFail("insert into orders (id, user_id, total) values (1, 1, 'abc');", "Type mismatch");
        shouldFail("insert into users (id, name, age) values (1, 123, 25);", "Type mismatch");
        shouldFail("insert into users (name, age) values ('Alice', 'old');", "Type mismatch");
        shouldFail("insert into orders (total) values ('abc');", "Type mismatch");
        shouldFail("insert into users (email) values (12345);", "Type mismatch");
        shouldFail("insert into products (price, stock) values ('abc', 10);", "Type mismatch");
        shouldFail("insert into products (price, stock) values (19.99, 'abc');", "Type mismatch");

        // ==================== 六、INSERT NULL / NOT NULL（10 条）❌ ====================
        shouldFail("insert into users (id, name) values (null, 'Alice');", "Column cannot be NULL");
        shouldFail("insert into users (id, name) values (1, null);", "Column cannot be NULL");
        shouldFail("insert into users (name, age) values ('Alice', 25);", "Missing NOT NULL column");
        shouldFail("insert into users (age) values (25);", "Missing NOT NULL column");
        shouldFail("insert into users values (null, null, 25, null);", "Column cannot be NULL");
        shouldFail("insert into users (id) values (1);", "Missing NOT NULL column");
        shouldFail("update users set name = null where id = 1;", "Column cannot be NULL");
        shouldFail("update users set id = null where id = 1;", "Column cannot be NULL");
        shouldFail("insert into orders (id, user_id, total) values (null, 1, 100.00);", "Column cannot be NULL");
        shouldFail("insert into orders (id, user_id, total) values (1, null, 100.00);", "Column cannot be NULL");

        // ==================== 七、UPDATE 类型错误（10 条）❌ ====================
        shouldFail("update users set age = 'old' where id = 1;", "Type mismatch");
        shouldFail("update users set name = 123 where id = 1;", "Type mismatch");
        shouldFail("update users set id = 'abc' where name = 'Alice';", "Type mismatch");
        shouldFail("update users set email = 12345 where id = 1;", "Type mismatch");
        shouldFail("update orders set total = 'abc' where id = 1;", "Type mismatch");
        shouldFail("update orders set user_id = 'abc' where id = 1;", "Type mismatch");
        shouldFail("update products set price = 'abc' where id = 1;", "Type mismatch");
        shouldFail("update products set stock = 'abc' where id = 1;", "Type mismatch");
        shouldFail("update users set age = 'old', name = 123 where id = 1;", "Type mismatch");
        shouldFail("update users set age = 'old' where age > 'old';", "Type mismatch");

        // ==================== 八、WHERE 条件类型不匹配（10 条）❌ ====================
        shouldFail("select id from users where age > 'old';", "Type mismatch in condition");
        shouldFail("select id from users where name = 123;", "Type mismatch in condition");
        shouldFail("select id from users where id = 'abc';", "Type mismatch in condition");
        shouldFail("select id from users where email = 12345;", "Type mismatch in condition");
        shouldFail("select id from users where age between '10' and '20';", "Type mismatch in condition");
        shouldFail("select id from orders where total > 'abc';", "Type mismatch in condition");
        shouldFail("select id from orders where user_id = 'abc';", "Type mismatch in condition");
        shouldFail("select id from products where price < 'abc';", "Type mismatch in condition");
        shouldFail("select id from products where stock > 'abc';", "Type mismatch in condition");
        shouldFail("select id from users where age in ('10', '20', '30');", "Type mismatch in condition");

        // ==================== 九、CREATE TABLE 重复列 / 已存在（10 条）❌ ====================
        shouldFail("create table users (id int, id int);", "Duplicate column");
        shouldFail("create table users (id int, name varchar(100), id int);", "Duplicate column");
        shouldFail("create table users (id int, name varchar(100), name varchar(100));", "Duplicate column");
        shouldFail("create table users (id int, age int, age int);", "Duplicate column");
        shouldFail("create table test (id int, id int, id int);", "Duplicate column");
        shouldFail("create table test (col1 int, col2 int, col1 int);", "Duplicate column");
        shouldFail("create table users (id int);", "Table already exists");
        shouldFail("create table orders (id int);", "Table already exists");
        shouldFail("create table products (id int);", "Table already exists");
        shouldFail("create table categories (id int);", "Table already exists");

        // ==================== 十、DROP TABLE / ALTER TABLE（10 条）❌ ====================
        shouldFail("drop table missing_table;", "Unknown table");
        shouldFail("drop table users2;", "Unknown table");
        shouldFail("drop table orders2;", "Unknown table");
        shouldFail("drop table unknown_table;", "Unknown table");
        shouldFail("alter table users add column age int;", "Column already exists");
        shouldFail("alter table users add column id int;", "Column already exists");
        shouldFail("alter table users add column email varchar(200);", "Column already exists");
        shouldFail("alter table users drop column email2;", "Unknown column");
        shouldFail("alter table users drop column age2;", "Unknown column");
        shouldFail("alter table users drop column id2;", "Unknown column");

        // ==================== 十一、INSERT 值数量错误（1 条）❌ ====================
        shouldFail("insert into users (id, name) values (1);", "INSERT value count mismatch");
        shouldFail("insert into users values (1, 'Alice');", "INSERT value count mismatch");
        shouldFail("insert into users (id, name, age) values (1, 'Alice');", "INSERT value count mismatch");
        shouldFail("insert into users (id, name, age, email) values (1, 'Alice', 25);", "INSERT value count mismatch");
        shouldFail("insert into users values (1, 'Alice', 25, 'a@b.com', 'extra');", "INSERT value count mismatch");
        shouldFail("insert into orders (id, user_id) values (1);", "INSERT value count mismatch");
        shouldFail("insert into orders values (1, 1);", "INSERT value count mismatch");
        shouldFail("insert into products (id, name, price) values (1, 'Product');", "INSERT value count mismatch");
        shouldFail("insert into products values (1, 'Product', 19.99, 10, 'extra');", "INSERT value count mismatch");
        shouldFail("insert into categories (id, name) values (1);", "INSERT value count mismatch");
        //非聚合列未出现在 GROUP BY 中
        shouldFail("select id, name, count(*) from users group by id;", "not in GROUP BY");

        //HAVING 中使用了非聚合列且不在 GROUP BY 中
        shouldFail("select id, count(*) from users group by id having name = 'A';", "must appear in GROUP BY");

        //聚合参数错误（如果支持 COUNT(1,2) 之类的）
        shouldFail("select count(1, 2) from users;", "Invalid argument count");

        //LIMIT 负数
        shouldFail("select id from users limit -5;", "positive");

        //INSERT 重复列名
        shouldFail("insert into users (id, id) values (1, 2);", "Duplicate column");

        //UPDATE 重复列名
        shouldFail("update users set id=1, id=2 where id=1;", "Duplicate column");

        //JOIN ON 类型不兼容（需要表中有一列是 VARCHAR，另一列是 INT 对比）
        shouldFail("select * from users join orders on users.id = orders.user_code;", "Type mismatch");

        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void shouldPass(String sql) {
        try {
            analyze(sql);
            passed++;
            System.out.println("[PASS] " + sql);
        } catch (RuntimeException e) {
            failed++;
            System.out.println("[FAIL] expected pass, got: " + e.getMessage());
            System.out.println("       SQL: " + sql);
        }
    }

    private static void shouldFail(String sql, String expectedMessage) {
        try {
            analyze(sql);
            failed++;
            System.out.println("[FAIL] expected error containing: " + expectedMessage);
            System.out.println("       SQL: " + sql);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains(expectedMessage)) {
                passed++;
                System.out.println("[PASS] " + sql);
            } else {
                failed++;
                System.out.println("[FAIL] wrong error: " + e.getMessage());
                System.out.println("       Expected contains: " + expectedMessage);
                System.out.println("       SQL: " + sql);
            }
        }
    }

    private static void analyze(String sql) {
        Parser parser = new Parser(sql);
        ASTNode ast = parser.parseStatement();
        SemanticAnalyzer analyzer = new SemanticAnalyzer(TestCatalogFactory.create());
        analyzer.analyze(ast);
    }
}
