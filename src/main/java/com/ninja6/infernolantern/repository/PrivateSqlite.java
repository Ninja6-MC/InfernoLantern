package com.ninja6.infernolantern.repository;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/** One private driver/native loader per plugin lifetime; no global JDBC registration. */
final class PrivateSqlite {
    static final String RESOURCE="/com/ninja6/infernolantern/internal/sqlite-jdbc.jar";
    static final String SHA256="f8dce420a6016278497f4f0560f65602b9db858de141b541cf91a4644ebe4639";
    private static Constructor<?> constructor;
    private PrivateSqlite(){}

    private static synchronized Constructor<?> connectionConstructor()throws Exception {
        if(constructor!=null)return constructor;
        Path extracted=Files.createTempFile("infernolantern-jdbc-", ".jar");
        boolean retained=false;
        try {
            try(InputStream input=PrivateSqlite.class.getResourceAsStream(RESOURCE)){
                if(input==null)throw new IOException("Missing private SQLite driver resource");
                try(var output=Files.newOutputStream(extracted)) {input.transferTo(output);}
            }
            try(var input=Files.newInputStream(extracted)){
                var digest=MessageDigest.getInstance("SHA-256");
                input.transferTo(new java.security.DigestOutputStream(OutputStream.nullOutputStream(),digest));
                if(!SHA256.equals(HexFormat.of().formatHex(digest.digest())))throw new IOException("Private SQLite driver digest mismatch");
            }
            // The platform parent exposes java.sql but neither Paper's org.sqlite nor
            // its native resources. Xerial extracts its own versioned native resource.
            var loader=new URLClassLoader(new URL[]{extracted.toUri().toURL()},ClassLoader.getPlatformClassLoader());
            try {
                constructor=Class.forName("org.sqlite.jdbc4.JDBC4Connection",true,loader)
                        .getConstructor(String.class,String.class,Properties.class);
            }catch(Throwable failure){loader.close();throw failure;}
            extracted.toFile().deleteOnExit();
            retained=true;
            return constructor;
        }finally{if(!retained)Files.deleteIfExists(extracted);}
    }

    static Connection open(Path file)throws SQLException {
        return connect(file.toString(), false);
    }

    /** The owner lock excludes native writers and the caller verifies that no sidecars exist. */
    static Connection inspectNative(Path file)throws SQLException {
        return connect(file.toUri().toASCIIString()+"?mode=ro&immutable=1", true);
    }

    private static Connection connect(String address, boolean inspect)throws SQLException {
        Connection connection=null;
        try {
            Properties properties=new Properties();
            properties.setProperty("busy_timeout","5000");
            properties.setProperty("transaction_mode","IMMEDIATE");
            if(inspect)properties.setProperty("open_mode","1");
            connection=(Connection)connectionConstructor().newInstance("jdbc:sqlite:"+address,address,properties);
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT sqlite_version()")){
                String actual=rows.next()?rows.getString(1):"missing";
                if(!"3.54.1".equals(actual))throw new SQLException("Expected private SQLite 3.54.1; observed "+actual);
            }
            return connection;
        }catch(Exception|LinkageError failure){
            if(connection!=null)try{connection.close();}catch(SQLException close){failure.addSuppressed(close);}
            Throwable cause=failure instanceof InvocationTargetException invocation?invocation.getCause():failure;
            throw new SQLException("Private SQLite initialization failed; preserve database and journal",cause);
        }
    }
}
