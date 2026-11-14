package org.pischedule.packaging;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Launched with only the shaded application JAR and test classes on its classpath. */
public final class BundledJarSmoke {
    private BundledJarSmoke() { }

    public static void main(String[] args) throws Exception {
        // Driver discovery must use the bundled JDBC service descriptor and native library.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 42")) {
            if (!result.next() || result.getInt(1) != 42) {
                throw new IllegalStateException("Bundled SQLite JDBC smoke test failed");
            }
        }
    }
}
