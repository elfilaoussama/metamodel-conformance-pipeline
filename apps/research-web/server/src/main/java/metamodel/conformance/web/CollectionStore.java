package metamodel.conformance.web;

import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import java.sql.*;
import java.util.*;

@Repository
public final class CollectionStore {
    private final WorkbenchDatabase database;
    private final JsonMapper json = JsonMapper.builder().build();
    public CollectionStore(WorkbenchDatabase database) throws SQLException {
        this.database = database;
        try (Connection c = database.connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS research_collections(id TEXT PRIMARY KEY,version INTEGER NOT NULL,document TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS collection_reviews(id TEXT PRIMARY KEY,document TEXT NOT NULL)");
        }
    }
    public List<ResearchCollection> list() throws SQLException {
        List<ResearchCollection> result = new ArrayList<>();
        try (Connection c = database.connect(); Statement s = c.createStatement(); ResultSet rows = s.executeQuery("SELECT document FROM research_collections ORDER BY rowid DESC")) {
            while (rows.next()) result.add(json.readValue(rows.getString(1), ResearchCollection.class));
        }
        return result;
    }
    public ResearchCollection get(String id) throws SQLException {
        ResearchCollection.requireId(id);
        try (Connection c = database.connect(); PreparedStatement s = c.prepareStatement("SELECT document FROM research_collections WHERE id=?")) {
            s.setString(1, id); try (ResultSet rows = s.executeQuery()) {
                if (!rows.next()) throw new NoSuchElementException("Collection not found.");
                return json.readValue(rows.getString(1), ResearchCollection.class);
            }
        }
    }
    public void create(ResearchCollection collection) throws SQLException {
        try (Connection c = database.connect(); PreparedStatement s = c.prepareStatement("INSERT INTO research_collections VALUES(?,?,?)")) {
            s.setString(1, collection.id()); s.setLong(2, collection.version()); s.setString(3, json.writeValueAsString(collection)); s.executeUpdate();
        }
    }
    public void save(ResearchCollection collection, long expectedVersion) throws SQLException {
        if (collection.version() != expectedVersion + 1) throw new IllegalArgumentException("Invalid collection revision.");
        try (Connection c = database.connect(); PreparedStatement s = c.prepareStatement("UPDATE research_collections SET version=?,document=? WHERE id=? AND version=?")) {
            s.setLong(1, collection.version()); s.setString(2, json.writeValueAsString(collection)); s.setString(3, collection.id()); s.setLong(4, expectedVersion);
            if (s.executeUpdate() != 1) throw new IllegalStateException("This collection changed in another page. Reload it before applying your changes.");
        }
    }
    public void saveReview(ResearchCollection.Review review) throws SQLException {
        try (Connection c = database.connect(); PreparedStatement s = c.prepareStatement("INSERT INTO collection_reviews VALUES(?,?)")) {
            s.setString(1, review.id()); s.setString(2, json.writeValueAsString(review)); s.executeUpdate();
        }
    }
    public ResearchCollection.Review review(String id) throws SQLException {
        ResearchCollection.requireId(id);
        try (Connection c = database.connect(); PreparedStatement s = c.prepareStatement("SELECT document FROM collection_reviews WHERE id=?")) {
            s.setString(1, id); try (ResultSet row = s.executeQuery()) {
                if (!row.next()) throw new NoSuchElementException("Run review not found.");
                return json.readValue(row.getString(1), ResearchCollection.Review.class);
            }
        }
    }
    public List<ResearchCollection.Review> reviews(String collectionId) throws SQLException {
        List<ResearchCollection.Review> result = new ArrayList<>();
        try (Connection c = database.connect(); PreparedStatement s = c.prepareStatement("SELECT document FROM collection_reviews WHERE json_extract(document,'$.collectionId')=? ORDER BY rowid DESC")) {
            s.setString(1, collectionId); try (ResultSet rows = s.executeQuery()) { while (rows.next()) result.add(json.readValue(rows.getString(1), ResearchCollection.Review.class)); }
        }
        return result;
    }
}
