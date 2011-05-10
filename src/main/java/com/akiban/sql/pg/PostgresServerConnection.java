/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.sql.pg;

import com.akiban.sql.StandardException;
import com.akiban.sql.parser.SQLParser;
import com.akiban.sql.parser.StatementNode;
import com.akiban.sql.parser.CursorNode;

import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.ais.model.Column;
import com.akiban.ais.model.UserTable;
import com.akiban.server.service.ServiceManager;
import com.akiban.server.service.ServiceManagerImpl;
import com.akiban.server.service.session.Session;
import com.akiban.util.Tap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.*;
import java.io.*;
import java.util.*;

/**
 * Connection to a Postgres server client.
 * Runs in its own thread; has its own AkServer Session.
 *
 */
public class PostgresServerConnection implements Runnable
{
    private static final Logger logger = LoggerFactory.getLogger(PostgresServerConnection.class);

    private final static Tap parserTap = Tap.add(new Tap.Count("sql: parse"));
    private final static Tap optimizerTap = Tap.add(new Tap.Count("sql: optimize"));

    private PostgresServer server;
    private boolean running = false, ignoreUntilSync = false;
    private Socket socket;
    private PostgresMessenger messenger;
    private int pid, secret;
    private int version;
    private Properties properties;
    private Map<String,PostgresStatement> preparedStatements =
        new HashMap<String,PostgresStatement>();
    private Map<String,PostgresStatement> boundPortals =
        new HashMap<String,PostgresStatement>();

    private Session session;
    private ServiceManager serviceManager;
    private AkibanInformationSchema ais;
    private SQLParser parser;
    private PostgresStatementCompiler compiler;
    private Thread thread;

    public PostgresServerConnection(PostgresServer server, Socket socket, 
                                    int pid, int secret) {
        this.server = server;
        this.socket = socket;
        this.pid = pid;
        this.secret = secret;
    }

    public void start() {
        running = true;
        thread = new Thread(this);
        thread.start();
    }

    public void stop() {
        running = false;
        // Can only wake up stream read by closing down socket.
        try {
            socket.close();
        }
        catch (IOException ex) {
        }
        if (thread != null) {
            try {
                // Wait a bit, but don't hang up shutdown if thread is wedged.
                thread.join(500);
            }
            catch (InterruptedException ex) {
            }
            thread = null;
        }
    }

    public void run() {
        try {
            messenger = new PostgresMessenger(socket.getInputStream(),
                                              socket.getOutputStream());
            topLevel();
        }
        catch (Exception ex) {
            if (running)
                logger.warn("Error in server", ex);
        }
        finally {
            try {
                socket.close();
            }
            catch (IOException ex) {
            }
        }
    }

    protected enum ErrorMode { NONE, SIMPLE, EXTENDED };

    protected void topLevel() throws IOException, StandardException {
        logger.info("Connect from {}" + socket.getRemoteSocketAddress());
        messenger.readMessage(false);
        processStartupMessage();
        while (running) {
            int type = messenger.readMessage();
            if (ignoreUntilSync) {
                if ((type != -1) && (type != PostgresMessenger.SYNC_TYPE))
                    continue;
                ignoreUntilSync = false;
            }
            ErrorMode errorMode = ErrorMode.NONE;
            try {
                switch (type) {
                case -1:                                    // EOF
                    stop();
                    break;
                case PostgresMessenger.SYNC_TYPE:
                    readyForQuery();
                    break;
                case PostgresMessenger.PASSWORD_MESSAGE_TYPE:
                    processPasswordMessage();
                    break;
                case PostgresMessenger.QUERY_TYPE:
                    errorMode = ErrorMode.SIMPLE;
                    processQuery();
                    break;
                case PostgresMessenger.PARSE_TYPE:
                    errorMode = ErrorMode.EXTENDED;
                    processParse();
                    break;
                case PostgresMessenger.BIND_TYPE:
                    errorMode = ErrorMode.EXTENDED;
                    processBind();
                    break;
                case PostgresMessenger.DESCRIBE_TYPE:
                    errorMode = ErrorMode.EXTENDED;
                    processDescribe();
                    break;
                case PostgresMessenger.EXECUTE_TYPE:
                    errorMode = ErrorMode.EXTENDED;
                    processExecute();
                    break;
                case PostgresMessenger.CLOSE_TYPE:
                    processClose();
                    break;
                case PostgresMessenger.TERMINATE_TYPE:
                    processTerminate();
                    break;
                default:
                    throw new IOException("Unknown message type: " + (char)type);
                }
            }
            catch (StandardException ex) {
                logger.warn("Error in query", ex);
                if (errorMode == ErrorMode.NONE) throw ex;
                {
                    messenger.beginMessage(PostgresMessenger.ERROR_RESPONSE_TYPE);
                    messenger.write('S');
                    messenger.writeString("ERROR");
                    // TODO: Could dummy up an SQLSTATE, etc.
                    messenger.write('M');
                    messenger.writeString(ex.getMessage());
                    messenger.write(0);
                    messenger.sendMessage(true);
                }
                if (errorMode == ErrorMode.EXTENDED)
                    ignoreUntilSync = true;
                else
                    readyForQuery();
            }
        }
        server.removeConnection(pid);
    }

    protected void readyForQuery() throws IOException {
        messenger.beginMessage(PostgresMessenger.READY_FOR_QUERY_TYPE);
        messenger.writeByte('I'); // Idle ('T' -> xact open; 'E' -> xact abort)
        messenger.sendMessage(true);
    }

    protected void processStartupMessage() throws IOException {
        int version = messenger.readInt();
        switch (version) {
        case PostgresMessenger.VERSION_CANCEL:
            processCancelRequest();
            return;
        case PostgresMessenger.VERSION_SSL:
            processSSLMessage();
            return;
        default:
            this.version = version;
            logger.debug("Version {}.{}", (version >> 16), (version & 0xFFFF));
        }
        properties = new Properties();
        while (true) {
            String param = messenger.readString();
            if (param.length() == 0) break;
            String value = messenger.readString();
            properties.put(param, value);
        }
        logger.debug("Properties: {}", properties);
        String enc = properties.getProperty("client_encoding");
        if (enc != null) {
            if ("UNICODE".equals(enc))
                messenger.setEncoding("UTF-8");
            else
                messenger.setEncoding(enc);
        }
        
        String schema = properties.getProperty("database");
        session = ServiceManagerImpl.newSession();
        serviceManager = ServiceManagerImpl.get();
        ais = serviceManager.getDXL().ddlFunctions().getAIS(session);
        parser = new SQLParser();
        if (false)
            compiler = new PostgresHapiCompiler(parser, ais, schema);
        else
            compiler = new PostgresOperatorCompiler(parser, ais, schema,
                                                    session, serviceManager);

        {
            messenger.beginMessage(PostgresMessenger.AUTHENTICATION_TYPE);
            messenger.writeInt(PostgresMessenger.AUTHENTICATION_CLEAR_TEXT);
            messenger.sendMessage(true);
        }
    }

    protected void processCancelRequest() throws IOException {
        int pid = messenger.readInt();
        int secret = messenger.readInt();
        PostgresServerConnection connection = server.getConnection(pid);
        if ((connection != null) && (secret == connection.secret))
            // No easy way to signal in another thread.
            connection.messenger.setCancel(true);
        stop();                                         // That's all for this connection.
    }

    protected void processSSLMessage() throws IOException {
        throw new IOException("NIY");
    }

    protected void processPasswordMessage() throws IOException {
        String user = properties.getProperty("user");
        String pass = messenger.readString();
        logger.info("Login {}/{}", user, pass);
        Properties status = new Properties();
        // This is enough to make the JDBC driver happy.
        status.put("client_encoding", properties.getProperty("client_encoding", "UNICODE"));
        status.put("server_encoding", messenger.getEncoding());
        status.put("server_version", "8.4.7"); // Not sure what the min it'll accept is.
        status.put("session_authorization", user);
        
        {
            messenger.beginMessage(PostgresMessenger.AUTHENTICATION_TYPE);
            messenger.writeInt(PostgresMessenger.AUTHENTICATION_OK);
            messenger.sendMessage();
        }
        for (String prop : status.stringPropertyNames()) {
            messenger.beginMessage(PostgresMessenger.PARAMETER_STATUS_TYPE);
            messenger.writeString(prop);
            messenger.writeString(status.getProperty(prop));
            messenger.sendMessage();
        }
        {
            messenger.beginMessage(PostgresMessenger.BACKEND_KEY_DATA_TYPE);
            messenger.writeInt(pid);
            messenger.writeInt(secret);
            messenger.sendMessage();
        }
        readyForQuery();
    }

    // ODBC driver sends this at the start; returning no rows is fine (and normal).
    public static final String ODBC_LO_TYPE_QUERY = "select oid, typbasetype from pg_type where typname = 'lo'";

    protected void processQuery() throws IOException, StandardException {
        String sql = messenger.readString();
        logger.info("Query: {}", sql);
        if (sql.equals(ODBC_LO_TYPE_QUERY)) {
            messenger.beginMessage(PostgresMessenger.COMMAND_COMPLETE_TYPE);
            messenger.writeString("SELECT");
            messenger.sendMessage();
        }
        else {
            List<StatementNode> stmts;
            try {
                parserTap.in();
                stmts = parser.parseStatements(sql);
            }
            finally {
                parserTap.out();
            }
            for (StatementNode stmt : stmts) {
                if (!(stmt instanceof CursorNode))
                    throw new StandardException("Not a SELECT");
                PostgresStatement pstmt;
                try {
                    optimizerTap.in();
                    pstmt = compiler.compile((CursorNode)stmt, null);
                }
                finally {
                    optimizerTap.out();
                }
                pstmt.sendRowDescription(messenger);
                int nrows = pstmt.execute(messenger, session, -1);

                messenger.beginMessage(PostgresMessenger.COMMAND_COMPLETE_TYPE);
                messenger.writeString("SELECT");
                messenger.sendMessage();
            }
        }
        readyForQuery();
    }

    protected void processParse() throws IOException, StandardException {
        String stmtName = messenger.readString();
        String sql = messenger.readString();
        short nparams = messenger.readShort();
        int[] paramTypes = new int[nparams];
        for (int i = 0; i < nparams; i++)
            paramTypes[i] = messenger.readInt();
        logger.info("Parse: {}", sql);

        StatementNode stmt;
        try {
            parserTap.in();
            stmt = parser.parseStatement(sql);
        }
        finally {
            parserTap.out();
        }
        if (!(stmt instanceof CursorNode))
            throw new StandardException("Not a SELECT");
        PostgresStatement pstmt;
        try {
            optimizerTap.in();
            pstmt = compiler.compile((CursorNode)stmt, paramTypes);
        }
        finally {
            optimizerTap.out();
        }
        preparedStatements.put(stmtName, pstmt);
        messenger.beginMessage(PostgresMessenger.PARSE_COMPLETE_TYPE);
        messenger.sendMessage();
    }

    protected void processBind() throws IOException {
        String portalName = messenger.readString();
        String stmtName = messenger.readString();
        String[] params = null;
        {
            short nformats = messenger.readShort();
            boolean[] paramsBinary = new boolean[nformats];
            for (int i = 0; i < nformats; i++)
                paramsBinary[i] = (messenger.readShort() == 1);
            short nparams = messenger.readShort();
            params = new String[nparams];
            boolean binary = false;
            for (int i = 0; i < nparams; i++) {
                if (i < nformats)
                    binary = paramsBinary[i];
                int len = messenger.readInt();
                if (len < 0) continue;      // Null
                byte[] param = new byte[len];
                messenger.readFully(param, 0, len);
                if (binary) {
                    throw new IOException("Don't know how to parse binary format.");
                }
                else {
                    params[i] = new String(param, messenger.getEncoding());
                }
            }
        }
        boolean[] resultsBinary = null; 
        boolean defaultResultsBinary = false;
        {        
            short nresults = messenger.readShort();
            if (nresults == 1)
                defaultResultsBinary = (messenger.readShort() == 1);
            else if (nresults > 0) {
                resultsBinary = new boolean[nresults];
                for (int i = 0; i < nresults; i++) {
                    resultsBinary[i] = (messenger.readShort() == 1);
                }
                defaultResultsBinary = resultsBinary[nresults-1];
            }
        }
        PostgresStatement pstmt = preparedStatements.get(stmtName);
        boundPortals.put(portalName, 
                         pstmt.getBoundRequest(params, 
                                               resultsBinary, defaultResultsBinary));
        messenger.beginMessage(PostgresMessenger.BIND_COMPLETE_TYPE);
        messenger.sendMessage();
    }

    protected void processDescribe() throws IOException, StandardException {
        byte source = messenger.readByte();
        String name = messenger.readString();
        PostgresStatement pstmt;        
        switch (source) {
        case (byte)'S':
            pstmt = preparedStatements.get(name);
            break;
        case (byte)'P':
            pstmt = boundPortals.get(name);
            break;
        default:
            throw new IOException("Unknown describe source: " + (char)source);
        }
        if (false) {
            // This would be for a query not returning data.
            messenger.beginMessage(PostgresMessenger.NO_DATA_TYPE);
            messenger.sendMessage();
        }
        else {
            pstmt.sendRowDescription(messenger);
        }
    }

    protected void processExecute() throws IOException, StandardException {
        String portalName = messenger.readString();
        int maxrows = messenger.readInt();
        PostgresStatement pstmt = boundPortals.get(portalName);
        int nrows = pstmt.execute(messenger, session, maxrows);
        messenger.beginMessage(PostgresMessenger.COMMAND_COMPLETE_TYPE);
        messenger.writeString("SELECT");
        messenger.sendMessage();
    }

    protected void processClose() throws IOException {
        byte source = messenger.readByte();
        String name = messenger.readString();
        PostgresStatement pstmt;        
        switch (source) {
        case (byte)'S':
            pstmt = preparedStatements.remove(name);
            break;
        case (byte)'P':
            pstmt = boundPortals.remove(name);
            break;
        default:
            throw new IOException("Unknown describe source: " + (char)source);
        }
        messenger.beginMessage(PostgresMessenger.CLOSE_COMPLETE_TYPE);
        messenger.sendMessage();
    }
    
    protected void processTerminate() throws IOException {
        stop();
    }

}
