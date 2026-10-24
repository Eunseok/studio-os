package dev.studioos;

import static dev.studioos.Domain.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class Store {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  public Store(JdbcTemplate db, ObjectMapper json, TransactionTemplate tx) {
    this.db = db;
    this.json = json;
    this.tx = tx;
  }

  public String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public <T> T decode(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public <T> List<T> all(String kind, Class<T> type) {
    return db.query(
        "SELECT payload FROM entities WHERE kind=? ORDER BY id",
        (r, i) -> decode(r.getString(1), type),
        kind);
  }

  public <T> T get(String kind, String id, Class<T> type) {
    return db
        .query(
            "SELECT payload FROM entities WHERE kind=? AND id=?",
            (r, i) -> decode(r.getString(1), type),
            kind,
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("항목을 찾을 수 없습니다: " + id));
  }

  public void save(
      String kind, String id, Object value, String event, String taskId, Map<String, Object> data) {
    tx.executeWithoutResult(
        s -> {
          if (db.update(
                  "UPDATE entities SET payload=? WHERE id=? AND kind=?", encode(value), id, kind)
              == 0)
            db.update(
                "INSERT INTO entities(id,kind,payload) VALUES(?,?,?)", id, kind, encode(value));
          db.update(
              "INSERT INTO events(event_type,task_id,created_at,payload) VALUES(?,?,?,?)",
              event,
              taskId,
              now(),
              encode(data));
        });
  }

  public List<Event> events(long after) {
    return db.query(
        "SELECT * FROM events WHERE id>? ORDER BY id LIMIT 200",
        (r, i) -> {
          try {
            return new Event(
                r.getLong("id"),
                r.getString("event_type"),
                r.getString("task_id"),
                r.getString("created_at"),
                json.readValue(
                    r.getString("payload"), new TypeReference<Map<String, Object>>() {}));
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        },
        after);
  }

  public long latestEvent() {
    return Optional.ofNullable(db.queryForObject("SELECT MAX(id) FROM events", Long.class))
        .orElse(0L);
  }
}
