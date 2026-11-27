package com.ninja6.infernolantern.repository;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Projects only verified current reservations from an already validated immutable native store. */
final class NativeReservationSnapshot {
    static final long MAX_DATABASE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_TOWERS = 1024;
    private static final int MAX_COMPONENT_ROWS = 65_536;
    private static final int MAX_CELLS = 65_536;
    private static final String PENDING = "('INTENT','EFFECTS_APPLIED','RECONCILING')";

    private NativeReservationSnapshot() {}

    static Optional<Set<Cell>> inspect(Connection connection) {
        try {
            return Optional.of(read(connection));
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    private static Set<Cell> read(Connection connection) throws Exception {
        verifyCoordinates(connection);
        var pending = pendingIds(connection);
        var expected = new HashMap<Cell, Reservation>();
        try (var query = connection.createStatement();
             var rows = query.executeQuery("SELECT id,active_operation FROM towers LIMIT " + (MAX_TOWERS + 1))) {
            int count = 0;
            while (rows.next()) {
                require(++count <= MAX_TOWERS);
                UUID id = uuid(rows.getString("id"));
                UUID active = optionalUuid(rows.getString("active_operation"));
                var tower = SqliteRepository.loadTower(connection, id);
                require(tower != null && id.equals(tower.state().tower().id())
                        && Objects.equals(active, tower.activeOperation()));
                var states = new ArrayList<State>(2);
                states.add(tower.state());
                if (active != null) {
                    require(pending.remove(active));
                    var operation = SqliteRepository.loadOperation(connection, active);
                    require(operation != null && operation.pending() && id.equals(operation.intent().towerId())
                            && tower.rowVersion() == operation.intent().expectedRowVersion() + 1
                            && tower.state().equals(operation.before()));
                    State after = operation.intent().after();
                    Tower beforeTower = tower.state().tower();
                    Tower afterTower = after.tower();
                    require(beforeTower.variantId().equals(afterTower.variantId())
                            && afterTower.generation() >= beforeTower.generation()
                            && afterTower.deploymentRevision() >= beforeTower.deploymentRevision());
                    states.add(after);
                }
                for (Cell cell : SqliteRepository.cells(states)) {
                    require(expected.putIfAbsent(cell, new Reservation(cell, id, active)) == null
                            && expected.size() <= MAX_CELLS);
                }
            }
        }
        require(pending.isEmpty());

        var confirmed = new HashSet<Cell>();
        try (var query = connection.createStatement();
             var rows = query.executeQuery("SELECT world,x,y,z,tower_id,operation_id FROM structure_cells LIMIT "
                     + (MAX_CELLS + 1))) {
            while (rows.next()) {
                Cell cell = cell(rows);
                var actual = new Reservation(cell, uuid(rows.getString("tower_id")),
                        optionalUuid(rows.getString("operation_id")));
                require(actual.equals(expected.remove(cell)));
                confirmed.add(cell);
            }
        }
        require(expected.isEmpty());
        return Set.copyOf(confirmed);
    }

    private static Set<UUID> pendingIds(Connection connection) throws Exception {
        var pending = new HashSet<UUID>();
        try (var query = connection.createStatement();
             var rows = query.executeQuery("SELECT id,typeof(before_state) AS before_type,"
                     + "typeof(after_state) AS after_type,length(before_state) AS before_size,"
                     + "length(after_state) AS after_size FROM operations WHERE phase IN " + PENDING
                     + " LIMIT " + (MAX_TOWERS + 1))) {
            while (rows.next()) {
                require("blob".equals(rows.getString("before_type")) && "blob".equals(rows.getString("after_type"))
                        && rows.getLong("before_size") <= MAX_PAYLOAD && rows.getLong("after_size") <= MAX_PAYLOAD);
                require(pending.add(uuid(rows.getString("id"))) && pending.size() <= MAX_TOWERS);
            }
        }
        return pending;
    }

    private static void verifyCoordinates(Connection connection) throws Exception {
        try (var query = connection.createStatement();
             var rows = query.executeQuery("PRAGMA foreign_key_check")) {
            require(!rows.next());
        }
        verifyCoordinateRows(connection, "SELECT world,x,y,z FROM towers WHERE world IS NOT NULL", MAX_TOWERS);
        verifyCoordinateRows(connection, "SELECT world,x,y,z FROM manifest_components", MAX_COMPONENT_ROWS);
    }

    private static void verifyCoordinateRows(Connection connection, String sql, int limit) throws Exception {
        try (var query = connection.createStatement();
             var rows = query.executeQuery(sql + " LIMIT " + (limit + 1))) {
            int count = 0;
            while (rows.next()) {
                require(++count <= limit);
                cell(rows);
            }
        }
    }

    private static Cell cell(ResultSet row) throws SQLException, IOException {
        return new Cell(uuid(row.getString("world")), coordinate(row, "x"), coordinate(row, "y"), coordinate(row, "z"));
    }

    private static int coordinate(ResultSet row, String column) throws SQLException, IOException {
        Object value = row.getObject(column);
        if (value instanceof Integer exact) return exact;
        if (value instanceof Long wide) return Math.toIntExact(wide);
        throw new IOException("Invalid reservation coordinate");
    }

    private static UUID optionalUuid(String value) throws IOException {
        return value == null ? null : uuid(value);
    }

    private static UUID uuid(String value) throws IOException {
        if (value == null) throw new IOException("Missing reservation identity");
        UUID id = UUID.fromString(value);
        require(id.toString().equals(value));
        return id;
    }

    private static void require(boolean condition) throws IOException {
        if (!condition) throw new IOException("Unverified native reservation");
    }
}
