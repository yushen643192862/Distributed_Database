package testsupport;

import semantic.SchemaCatalog;
import semantic.TableSchema;

public class TestCatalogFactory {
    public static SchemaCatalog create() {
        SchemaCatalog catalog = new SchemaCatalog();
        
        // users 表：id(NN), name(NN), age, email
        catalog.addTable(new TableSchema("users")
                .addColumn("id", "int", true)
                .addColumn("name", "varchar", true)
                .addColumn("age", "int", false)
                .addColumn("email", "varchar", false));
        
        // orders 表：id(NN), user_id(NN), total(NN)
        catalog.addTable(new TableSchema("orders")
                .addColumn("id", "int", true)
                .addColumn("user_id", "int", true)
                .addColumn("user_code", "varchar", false)
                .addColumn("total", "decimal", true));
        
        // products 表：id(NN), name(NN), price, stock
        catalog.addTable(new TableSchema("products")
                .addColumn("id", "int", true)
                .addColumn("name", "varchar", true)
                .addColumn("price", "decimal", false)
                .addColumn("stock", "int", false));
        
        // categories 表：id(NN), name(NN), parent_id
        catalog.addTable(new TableSchema("categories")
                .addColumn("id", "int", true)
                .addColumn("name", "varchar", true)
                .addColumn("parent_id", "int", false));
        
        return catalog;
    }
}
