package com.ninja6.infernolantern.repository;

import java.nio.file.Path;
import java.sql.*;

/** Fault injection must share the repository's native library and process-local locks. */
public final class SqliteTestConnections {
    private SqliteTestConnections(){}
    public static Connection open(Path file)throws SQLException{return PrivateSqlite.open(file);}
}
