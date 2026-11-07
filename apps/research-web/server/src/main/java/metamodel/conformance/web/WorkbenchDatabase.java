package metamodel.conformance.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.sql.*;

/** Shared connection policy; stores own their schemas and short transactions. */
@Component
public final class WorkbenchDatabase {
    private final String url;
    @Autowired public WorkbenchDatabase(Workspace workspace) { this(workspace.root); }
    WorkbenchDatabase(Path root) { url = "jdbc:sqlite:" + root.resolve("catalog.sqlite"); }
    public Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=5000"); }
        return connection;
    }
}
