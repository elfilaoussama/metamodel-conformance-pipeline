package metamodel.conformance.web;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.sql.*;
import java.util.*;

/** A serialized, short-transaction writer; the database is only a metadata catalog. */
@Component
public final class Catalog {
    private final WorkbenchDatabase database;
    private final JsonMapper json = JsonMapper.builder().build();
    public Catalog(WorkbenchDatabase database) throws SQLException {
        this.database = database;
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS runs(id TEXT PRIMARY KEY, request_key TEXT UNIQUE NOT NULL, document TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, document TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS runs_state ON runs(json_extract(document,'$.state'))");
        }
    }
    private Connection connect() throws SQLException {
        return database.connect();
    }
    public synchronized void save(RunRecord run) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s = c.prepareStatement("INSERT INTO runs VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET document=excluded.document")) {
                    s.setString(1, run.id()); s.setString(2, run.idempotencyKey()); s.setString(3, json.writeValueAsString(run)); s.executeUpdate();
                }
                try (PreparedStatement s = c.prepareStatement("INSERT INTO events(run_id,document) VALUES(?,?)")) {
                    s.setString(1, run.id()); s.setString(2, json.writeValueAsString(run)); s.executeUpdate();
                }
                c.commit();
            } catch (Exception failure) { c.rollback(); throw failure; }
        }
    }
    public synchronized Optional<RunRecord> byRequest(String key) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("SELECT document FROM runs WHERE request_key=?")) {
            s.setString(1, key); try (ResultSet rs = s.executeQuery()) { return rs.next() ? Optional.of(json.readValue(rs.getString(1), RunRecord.class)) : Optional.empty(); }
        }
    }
    public synchronized RunRecord get(String id) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("SELECT document FROM runs WHERE id=?")) {
            s.setString(1, id); try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) throw new NoSuchElementException("run not found");
                return json.readValue(rs.getString(1), RunRecord.class);
            }
        }
    }
    public synchronized List<RunRecord> list() throws SQLException {
        List<RunRecord> rows = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT document FROM runs ORDER BY rowid DESC LIMIT 100")) {
            while (rs.next()) rows.add(json.readValue(rs.getString(1), RunRecord.class));
        }
        return rows;
    }
    public synchronized List<RunRecord> active() throws SQLException {
        List<RunRecord> rows = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT document FROM runs WHERE json_extract(document,'$.state') IN ('QUEUED','RUNNING','CANCELLING')")) {
            while (rs.next()) rows.add(json.readValue(rs.getString(1), RunRecord.class));
        }
        return rows;
    }
    /** FIFO backlog; the dispatcher reserves a running slot before fetching another page. */
    public synchronized List<RunRecord> queued(int limit) throws SQLException {
        List<RunRecord> rows = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "SELECT document FROM runs WHERE json_extract(document,'$.state')='QUEUED' ORDER BY rowid LIMIT ?")) {
            s.setInt(1, limit);
            try (ResultSet rs = s.executeQuery()) { while (rs.next()) rows.add(json.readValue(rs.getString(1), RunRecord.class)); }
        }
        return rows;
    }
    public synchronized List<RunRecord> requestGroup(String key) throws SQLException {
        if (key == null || !key.matches("[A-Za-z0-9-]{8,100}")) throw new IllegalArgumentException("Invalid submission group.");
        List<RunRecord> rows = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "SELECT document FROM runs WHERE substr(request_key,1,?)=? ORDER BY rowid")) {
            s.setInt(1, key.length() + 1); s.setString(2, key + "-");
            try (ResultSet rs = s.executeQuery()) { while (rs.next()) rows.add(json.readValue(rs.getString(1), RunRecord.class)); }
        }
        return rows;
    }
    public synchronized List<RunRecord> replays(String id) throws SQLException {
        List<RunRecord> rows = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("SELECT document FROM runs WHERE json_extract(document,'$.replayOf')=? ORDER BY rowid DESC")) {
            s.setString(1, id);
            try (ResultSet rs = s.executeQuery()) { while (rs.next()) rows.add(json.readValue(rs.getString(1), RunRecord.class)); }
        }
        return rows;
    }
    public synchronized List<Map<String,Object>> events(String id, long after) throws SQLException {
        List<Map<String,Object>> rows = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("SELECT seq,document FROM events WHERE run_id=? AND seq>? ORDER BY seq LIMIT 100")) {
            s.setString(1, id); s.setLong(2, after);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) rows.add(Map.of("sequence", rs.getLong(1), "run", json.readValue(rs.getString(2), RunRecord.class)));
            }
        }
        return rows;
    }
}
