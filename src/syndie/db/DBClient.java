package syndie.db;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import net.i2p.data.*;
import syndie.Constants;
import syndie.data.ArchiveInfo;
import syndie.data.ChannelInfo;
import syndie.data.MessageInfo;
import syndie.data.NymKey;
import syndie.data.ReferenceNode;

import syndie.data.SyndieURI;
import net.i2p.I2PAppContext;
import net.i2p.util.Log;

public class DBClient {
    private static final Class[] _gcjKludge = new Class[] { 
        org.hsqldb.jdbcDriver.class
        , org.hsqldb.GCJKludge.class
        , org.hsqldb.persist.GCJKludge.class
    };
    private I2PAppContext _context;
    private Log _log;
    
    private Connection _con;
    private SyndieURIDAO _uriDAO;
    private String _login;
    private String _pass;
    private long _nymId;
    private File _rootDir;
    private String _url;
    private Thread _shutdownHook;
    private boolean _shutdownInProgress;
    private String _defaultArchive;
    private String _httpProxyHost;
    private int _httpProxyPort;
    private String _fcpHost;
    private int _fcpPort;
    private String _freenetPrivateKey;
    private String _freenetPublicKey;
        
    public DBClient(I2PAppContext ctx, File rootDir) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _rootDir = rootDir;
        _shutdownInProgress = false;
        _shutdownHook = new Thread(new Thread(new Runnable() {
            public void run() {
                _shutdownInProgress = true;
                close();
            }
        }, "DB shutdown"));
    }
    
    public void connect(String url) throws SQLException { 
        //System.out.println("Connecting to " + url);
        _url = url;
        _con = DriverManager.getConnection(url);
        Runtime.getRuntime().addShutdownHook(_shutdownHook);
        
        initDB();
        _uriDAO = new SyndieURIDAO(this);
        _login = null;
        _pass = null;
        _nymId = -1;
    }
    public long connect(String url, String login, String passphrase) throws SQLException {
        connect(url);
        return getNymId(login, passphrase);
    }
    I2PAppContext ctx() { return _context; }
    Connection con() { return _con; }
    
    /** if logged in, the login used is returned here */
    String getLogin() { return _login; }
    /** if logged in, the password authenticating it is returned here */
    String getPass() { return _pass; }
    boolean isLoggedIn() { return _login != null; }
    /** if logged in, the internal nymId associated with that login */
    long getLoggedInNymId() { return _nymId; }
    
    File getTempDir() { return new File(_rootDir, "tmp"); }
    File getOutboundDir() { return new File(_rootDir, "outbound"); }
    File getArchiveDir() { return new File(_rootDir, "archive"); }
    
    String getDefaultHTTPProxyHost() { return _httpProxyHost; }
    void setDefaultHTTPProxyHost(String host) { _httpProxyHost = host; }
    int getDefaultHTTPProxyPort() { return _httpProxyPort; }
    void setDefaultHTTPProxyPort(int port) { _httpProxyPort = port; }
    String getDefaultHTTPArchive() { return _defaultArchive; }
    void setDefaultHTTPArchive(String archive) { _defaultArchive = archive; }
    
    String getDefaultFreenetHost() { return _fcpHost; }
    void setDefaultFreenetHost(String host) { _fcpHost = host; }
    int getDefaultFreenetPort() { return _fcpPort; }
    void setDefaultFreenetPort(int port) { _fcpPort = port; }
    String getDefaultFreenetPrivateKey() { return _freenetPrivateKey; }
    void setDefaultFreenetPrivateKey(String privateSSK) { _freenetPrivateKey = privateSSK; }
    String getDefaultFreenetPublicKey() { return _freenetPublicKey; }
    void setDefaultFreenetPublicKey(String publicSSK) { _freenetPublicKey = publicSSK; }
    
    public void close() {
        _login = null;
        _pass = null;
        _nymId = -1;
        _defaultArchive = null;
        _httpProxyHost = null;
        _httpProxyPort = -1;
        _fcpHost = null;
        _fcpPort = -1;
        _freenetPrivateKey = null;
        _freenetPublicKey = null;
        try {
            if (_con == null) return;
            if (_con.isClosed()) return;
            PreparedStatement stmt = _con.prepareStatement("SHUTDOWN");
            stmt.execute();
            if (_log.shouldLog(Log.INFO))
                _log.info("Database shutdown");
            stmt.close();
            _con.close();
        } catch (SQLException se) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error closing the connection and shutting down the database", se);
        }
        if (!_shutdownInProgress)
            Runtime.getRuntime().removeShutdownHook(_shutdownHook);
    }
    
    String getString(String query, int column, long keyVal) {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(query);
            stmt.setLong(1, keyVal);
            rs = stmt.executeQuery();
            if (rs.next()) {
                String rv = rs.getString(column);
                if (!rs.wasNull())
                    return rv;
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error fetching the string", se);
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        return null;
    }
  
    public static final long NYM_ID_LOGIN_UNKNOWN = -1;
    public static final long NYM_ID_PASSPHRASE_INVALID = -2;
    public static final long NYM_ID_LOGIN_ALREADY_EXISTS = -3;
    
    private static final String SQL_GET_NYM_ID = "SELECT nymId, passSalt, passHash FROM nym WHERE login = ?";
    /**
     * if the passphrase is blank, simply get the nymId for the login, otherwise
     * authenticate the passphrase, returning -1 if the login doesn't exist, -2
     * if the passphrase is invalid, or the nymId if it is correct.  If the nym and
     * password are both set and are authenticated, they are stored in memory on
     * the DBClient itself and can be queried with getLogin() and getPass().
     */
    public long getNymId(String login, String passphrase) {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_NYM_ID);
            stmt.setString(1, login);
            rs = stmt.executeQuery();
            if (rs.next()) {
                long nymId = rs.getLong(1);
                byte salt[] = rs.getBytes(2);
                byte hash[] = rs.getBytes(3);
                if (passphrase == null) {
                    return nymId;
                } else {
                    byte calc[] = _context.keyGenerator().generateSessionKey(salt, DataHelper.getUTF8(passphrase)).getData();
                    if (DataHelper.eq(calc, hash)) {
                        _login = login;
                        _pass = passphrase;
                        _nymId = nymId;
                        return nymId;
                    } else {
                        return NYM_ID_PASSPHRASE_INVALID;
                    }
                }
            } else {
                return NYM_ID_LOGIN_UNKNOWN;
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to check the get the nymId", se);
            return NYM_ID_LOGIN_UNKNOWN;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    }
    
    private static final String SQL_INSERT_NYM = "INSERT INTO nym (nymId, login, publicName, passSalt, passHash, isDefaultUser) VALUES (?, ?, ?, ?, ?, ?)";
    public long register(String login, String passphrase, String publicName) {
        long nymId = nextId("nymIdSequence");
        byte salt[] = new byte[16];
        _context.random().nextBytes(salt);
        byte hash[] = _context.keyGenerator().generateSessionKey(salt, DataHelper.getUTF8(passphrase)).getData();
        
        PreparedStatement stmt = null;
        try {
            stmt = _con.prepareStatement(SQL_INSERT_NYM);
            stmt.setLong(1, nymId);
            stmt.setString(2, login);
            stmt.setString(3, publicName);
            stmt.setBytes(4, salt);
            stmt.setBytes(5, hash);
            stmt.setBoolean(6, false);
            int rows = stmt.executeUpdate();
            if (rows != 1)
                return NYM_ID_LOGIN_ALREADY_EXISTS;
            else
                return nymId;
        } catch (SQLException se) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to register the nymId", se);
            return NYM_ID_LOGIN_ALREADY_EXISTS;
        } finally {
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    }
    
    public long nextId(String seq) {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            //String query = "SELECT NEXT VALUE FOR " + seq + " FROM information_schema.system_sequences WHERE sequence_name = '" + seq.toUpperCase() + "'";
            String query = "CALL NEXT VALUE FOR " + seq;
            stmt = _con.prepareStatement(query);
            rs = stmt.executeQuery();
            if (rs.next()) {
                long rv = rs.getLong(1);
                if (rs.wasNull())
                    return -1;
                else
                    return rv;
            } else {
                return -1;
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the next sequence ID", se);
            return -1;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    }
    
    public SyndieURI getURI(long uriId) {
        return _uriDAO.fetch(uriId);
    }
    public long addURI(SyndieURI uri) {
        return _uriDAO.add(uri);
    }
    
    public static void main(String args[]) {
        DBClient client = new DBClient(I2PAppContext.getGlobalContext(), new File(TextEngine.getRootPath()));
        try {
            client.connect("jdbc:hsqldb:file:/tmp/testSynDB;hsqldb.nio_data_file=false");
            client.close();
        } catch (SQLException se) {
            se.printStackTrace();
        }
    }
    
    private void initDB() {
        int version = checkDBVersion();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Known DB version: " + version);
        if (version < 0)
            buildDB();
        int updates = getDBUpdateCount(); // syndie/db/ddl_update$n.txt
        for (int i = 1; i <= updates; i++) {
            if (i >= version) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Updating database version " + i + " to " + (i+1));
                updateDB(i);
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("No need for update " + i + " (version: " + version + ")");
            }
        }
    }
    private int checkDBVersion() {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement("SELECT versionNum FROM appVersion WHERE app = 'syndie.db'");
            rs = stmt.executeQuery();
            while (rs.next()) {
                int rv = rs.getInt(1);
                if (!rs.wasNull())
                    return rv;
            }
            return -1;
        } catch (SQLException se) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to check the database version (does not exist?)", se);
            return -1;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    }
    private void buildDB() {
        if (_log.shouldLog(Log.INFO))
            _log.info("Building the database...");
        try {
            InputStream in = getClass().getResourceAsStream("ddl.txt");
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in));
                StringBuffer cmdBuf = new StringBuffer();
                String line = null;
                while ( (line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("//") || line.startsWith("--"))
                        continue;
                    cmdBuf.append(' ').append(line);
                    if (line.endsWith(";")) {
                        exec(cmdBuf.toString());
                        cmdBuf.setLength(0);
                    }
                }
            }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error reading the db script", ioe);
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error building the db", se);
        }
    }
    private int getDBUpdateCount() {
        int updates = 0;
        while (true) {
            try {
                InputStream in = getClass().getResourceAsStream("ddl_update" + (updates+1) + ".txt");
                if (in != null) {
                    in.close();
                    updates++;
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("There were " + updates + " database updates known for " + getClass().getName() + " ddl_update*.txt");
                    return updates;
                }
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("problem listing the updates", ioe);
            }
        }
    }
    private void updateDB(int oldVersion) {
        try {
            InputStream in = getClass().getResourceAsStream("ddl_update" + oldVersion + ".txt");
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in));
                StringBuffer cmdBuf = new StringBuffer();
                String line = null;
                while ( (line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("//") || line.startsWith("--"))
                        continue;
                    cmdBuf.append(' ').append(line);
                    if (line.endsWith(";")) {
                        exec(cmdBuf.toString());
                        cmdBuf.setLength(0);
                    }
                }
            }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error reading the db script", ioe);
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error building the db", se);
        }
    }
    private void exec(String cmd) throws SQLException {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Exec [" + cmd + "]");
        PreparedStatement stmt = null;
        try {
            stmt = _con.prepareStatement(cmd);
            stmt.executeUpdate();
        } finally { 
            if (stmt != null) stmt.close();
        }
    }
    public int exec(String sql, long param1) throws SQLException {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Exec param [" + sql + "]");
        PreparedStatement stmt = null;
        try {
            stmt = _con.prepareStatement(sql);
            stmt.setLong(1, param1);
            return stmt.executeUpdate();
        } finally { 
            if (stmt != null) stmt.close();
        }
    }
    public void exec(String query, UI ui) {
        ui.debugMessage("Executing [" + query + "]");
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(query);
            String up = query.toUpperCase();
            if (!up.startsWith("SELECT") && !up.startsWith("CALL")) {
                int rows = stmt.executeUpdate();
                ui.statusMessage("Command completed, updating " + rows + " rows");
                ui.commandComplete(rows, null);
                return;
            }
            rs = stmt.executeQuery();
            ResultSetMetaData md = stmt.getMetaData();
            int rows = 0;
            while (rs.next()) {
                rows++;
                ui.statusMessage("----------------------------------------------------------");
                for (int i = 0; i < md.getColumnCount(); i++) {
                    Object obj = rs.getObject(i+1);
                    if (obj != null) {
                        if (obj instanceof byte[]) {
                            String str = Base64.encode((byte[])obj);
                            if (str.length() <= 32)
                                ui.statusMessage(md.getColumnLabel(i+1) + ":\t" + str);
                            else
                                ui.statusMessage(md.getColumnLabel(i+1) + ":\t" + str.substring(0,32) + "...");
                        } else {
                            ui.statusMessage(md.getColumnLabel(i+1) + ":\t" + obj.toString());
                        }
                    } else {
                        ui.statusMessage(md.getColumnLabel(i+1) + ":\t[null value]");
                    }
                }
            }
            ui.statusMessage("Rows matching the query: " + rows);
            ui.commandComplete(rows, null);
        } catch (SQLException se) {
            ui.errorMessage("Error executing the query", se);
            ui.commandComplete(-1, null);
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    }

    private static final String SQL_GET_READKEYS = "SELECT keyType, keyData, keySalt, authenticated, keyPeriodBegin, keyPeriodEnd " +
                                                   "FROM nymKey WHERE " + 
                                                   "keyChannel = ? AND nymId = ? AND keyFunction = '" + Constants.KEY_FUNCTION_READ + "'";
    private static final String SQL_GET_CHANREADKEYS = "SELECT keyData, keyStart FROM channelReadKey WHERE channelId = ? ORDER BY keyStart ASC";
    /** 
     * list of SessionKey instances that the nym specified can use to try and read/write 
     * posts to the given identHash channel
     */
    public List getReadKeys(Hash identHash, long nymId, String nymPassphrase) {
        List rv = new ArrayList(1);
        byte pass[] = DataHelper.getUTF8(nymPassphrase);
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_READKEYS);
            stmt.setBytes(1, identHash.getData());
            stmt.setLong(2, nymId);
            rs = stmt.executeQuery();
            while (rs.next()) {
                String type = rs.getString(1);
                byte data[] = rs.getBytes(2);
                byte salt[] = rs.getBytes(3);
                boolean auth= rs.getBoolean(4);
                Date begin  = rs.getDate(5);
                Date end    = rs.getDate(6);
                
                if (Constants.KEY_TYPE_AES256.equals(type)) {
                    if (salt != null) {
                        byte readKey[] = new byte[SessionKey.KEYSIZE_BYTES];
                        SessionKey saltedKey = _context.keyGenerator().generateSessionKey(salt, pass);
                        _context.aes().decrypt(data, 0, readKey, 0, saltedKey, salt, data.length);
                        int pad = (int)readKey[readKey.length-1];
                        byte key[] = new byte[readKey.length-pad];
                        System.arraycopy(readKey, 0, key, 0, key.length);
                        rv.add(new SessionKey(key));
                    } else {
                        rv.add(new SessionKey(data));
                    }
                } else {
                    // we dont know how to deal with anything but AES256
                }
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the read keys", se);
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        
        // ok, that covers nym-local keys, now lets look for any channelReadKeys that came from
        // signed channel metadata
        long channelId = getChannelId(identHash);
        try {
            stmt = _con.prepareStatement(SQL_GET_CHANREADKEYS);
            //stmt.setBytes(1, identHash.getData());
            stmt.setLong(1, channelId);
            rs = stmt.executeQuery();
            while (rs.next()) {
                byte key[] = rs.getBytes(1);
                if ( (key != null) && (key.length == SessionKey.KEYSIZE_BYTES) )
                    rv.add(new SessionKey(key));
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the channel read keys", se);
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        return rv;
    }

    private static final String SQL_GET_KNOWN_EDITION = "SELECT MAX(edition) FROM channel WHERE channelHash = ?";
    /** highest channel meta edition, or -1 if unknown */
    public long getKnownEdition(Hash ident) {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_KNOWN_EDITION);
            stmt.setBytes(1, ident.getData());
            rs = stmt.executeQuery();
            if (rs.next()) {
                long edition = rs.getLong(1);
                if (rs.wasNull())
                    return -1;
                else
                    return edition;
            } else {
                return -1;
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the channel's meta edition", se);
            return -1;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    }

    private static final String SQL_GET_CHANNEL_IDS = "SELECT channelId, channelHash FROM channel ORDER BY channelHash";
    /** retrieve a mapping of channelId (Long) to channel hash (Hash) */
    public Map getChannelIds() {
        Map rv = new HashMap();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_CHANNEL_IDS);
            rs = stmt.executeQuery();
            while (rs.next()) {
                long id = rs.getLong(1);
                if (rs.wasNull())
                    continue;
                byte hash[] = rs.getBytes(2);
                if (rs.wasNull())
                    continue;
                if (hash.length != Hash.HASH_LENGTH)
                    continue;
                rv.put(new Long(id), new Hash(hash));
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the channel list", se);
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        return rv;
    }
    
    private static final String SQL_GET_CHANNEL_ID = "SELECT channelId FROM channel WHERE channelHash = ?";
    public long getChannelId(Hash channel) {
        if (channel == null) return -1;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_CHANNEL_ID);
            stmt.setBytes(1, channel.getData());
            rs = stmt.executeQuery();
            if (rs.next()) {
                long id = rs.getLong(1);
                if (rs.wasNull())
                    return -1;
                else
                    return id;
            } else {
                return -1;
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the channel id", se);
            return -1;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    }
    
    private static final String SQL_GET_SIGNKEYS = "SELECT keyType, keyData, keySalt, authenticated, keyPeriodBegin, keyPeriodEnd " +
                                                   "FROM nymKey WHERE " + 
                                                   "keyChannel = ? AND nymId = ? AND "+
                                                   "(keyFunction = '" + Constants.KEY_FUNCTION_MANAGE + "' OR keyFunction = '" + Constants.KEY_FUNCTION_POST + "')";
    /** 
     * list of SigningPrivateKey instances that the nym specified can use to
     * try and authenticate/authorize posts to the given identHash channel
     */
    public List getSignKeys(Hash identHash, long nymId, String nymPassphrase) {
        ensureLoggedIn();
        if (identHash == null) throw new IllegalArgumentException("you need an identHash (or you should use getNymKeys())");
        List rv = new ArrayList(1);
        byte pass[] = DataHelper.getUTF8(nymPassphrase);
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_SIGNKEYS);
            stmt.setBytes(1, identHash.getData());
            stmt.setLong(2, nymId);
            rs = stmt.executeQuery();
            while (rs.next()) {
                String type = rs.getString(1);
                byte data[] = rs.getBytes(2);
                byte salt[] = rs.getBytes(3);
                boolean auth= rs.getBoolean(4);
                Date begin  = rs.getDate(5);
                Date end    = rs.getDate(6);
                
                if (Constants.KEY_TYPE_DSA.equals(type)) {
                    if (salt != null) {
                        byte readKey[] = new byte[data.length];
                        SessionKey saltedKey = _context.keyGenerator().generateSessionKey(salt, pass);
                        _context.aes().decrypt(data, 0, readKey, 0, saltedKey, salt, data.length);
                        int pad = (int)readKey[readKey.length-1];
                        byte key[] = new byte[readKey.length-pad];
                        System.arraycopy(readKey, 0, key, 0, key.length);
                        rv.add(new SigningPrivateKey(key));
                    } else {
                        rv.add(new SigningPrivateKey(data));
                    }
                } else {
                    // we dont know how to deal with anything but DSA signing keys
                }
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the signing keys", se);
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        return rv;
    }

    private static final String SQL_GET_REPLY_KEY = "SELECT encryptKey FROM channel WHERE channelId = ?";
    public PublicKey getReplyKey(long channelId) {
        ensureLoggedIn();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_REPLY_KEY);
            stmt.setLong(1, channelId);
            rs = stmt.executeQuery();
            if (rs.next()) {
                byte rv[] = rs.getBytes(1);
                if (rs.wasNull())
                    return null;
                else
                    return new PublicKey(rv);
            } else {
                return null;
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the channel's reply key", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    }
    
    private static final String SQL_GET_NYMKEYS = "SELECT keyType, keyData, keySalt, authenticated, keyPeriodBegin, keyPeriodEnd, keyFunction, keyChannel " +
                                                   "FROM nymKey WHERE nymId = ?";
    /** return a list of NymKey structures */
    public List getNymKeys(long nymId, String pass, Hash channel, String keyFunction) {
        ensureLoggedIn();
        List rv = new ArrayList(1);
        byte passB[] = DataHelper.getUTF8(pass);
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            String query = SQL_GET_NYMKEYS;
            if (channel != null)
                query = query + " AND keyChannel = ?";
            if (keyFunction != null)
                query = query + " AND keyFunction = ?";
            stmt = _con.prepareStatement(query);
            stmt.setLong(1, nymId);
            if (channel != null) {
                stmt.setBytes(2, channel.getData());
                if (keyFunction != null)
                    stmt.setString(3, keyFunction);
            } else if (keyFunction != null) {
                stmt.setString(2, keyFunction);
            }
            
            rs = stmt.executeQuery();
            while (rs.next()) {
                String type = rs.getString(1);
                byte data[] = rs.getBytes(2);
                byte salt[] = rs.getBytes(3);
                boolean auth= rs.getBoolean(4);
                Date begin  = rs.getDate(5);
                Date end    = rs.getDate(6);
                String function = rs.getString(7);
                byte chan[] = rs.getBytes(8);
                
                if (salt != null) {
                    SessionKey saltedKey = _context.keyGenerator().generateSessionKey(salt, passB);
                    //_log.debug("salt: " + Base64.encode(salt));
                    //_log.debug("passB: " + Base64.encode(passB));
                    //_log.debug("encrypted: " + Base64.encode(data));
                    byte decr[] = new byte[data.length];
                    _context.aes().decrypt(data, 0, decr, 0, saltedKey, salt, data.length);
                    int pad = (int)decr[decr.length-1];
                    //_log.debug("pad: " + pad);
                    byte key[] = new byte[decr.length-pad];
                    System.arraycopy(decr, 0, key, 0, key.length);
                    //_log.debug("key: " + Base64.encode(key));
                    data = key;
                }
                
                rv.add(new NymKey(type, data, _context.sha().calculateHash(data).toBase64(), auth, function, nymId, (chan != null ? new Hash(chan) : null)));
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the keys", se);
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        return rv;        
    }
    
    public List getReplyKeys(Hash identHash, long nymId, String pass) {
        List keys = getNymKeys(nymId, pass, identHash, Constants.KEY_FUNCTION_REPLY);
        List rv = new ArrayList();
        for (int i = 0; i < keys.size(); i++)
            rv.add(new PrivateKey(((NymKey)keys.get(i)).getData()));
        return rv;
    }

    private static final String SQL_GET_AUTHORIZED_POSTERS = "SELECT identKey FROM channel WHERE channelId = ?" +
                                                             " UNION " +
                                                             "SELECT authPubKey FROM channelPostKey WHERE channelId = ?" +
                                                             " UNION " +
                                                             "SELECT authPubKey FROM channelManageKey WHERE channelId = ?";
    public List getAuthorizedPosters(Hash channel) {
        ensureLoggedIn();
        long channelId = getChannelId(channel);
        List rv = new ArrayList();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_AUTHORIZED_POSTERS);
            stmt.setLong(1, channelId);
            stmt.setLong(2, channelId);
            stmt.setLong(3, channelId);
            rs = stmt.executeQuery();
            while (rs.next()) {
                byte key[] = rs.getBytes(1);
                if (rs.wasNull()) {
                    continue;
                } else {
                    SigningPublicKey pub = new SigningPublicKey(key);
                    if (!rv.contains(pub))
                        rv.add(pub);
                }
            }
            rs.close();
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the channel's authorized posting keys", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        return rv;
    }

    private static final String SQL_GET_IDENT_KEY = "SELECT identKey FROM channel WHERE channelHash = ?";
    public SigningPublicKey getIdentKey(Hash hash) {
        ensureLoggedIn();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_IDENT_KEY);
            stmt.setBytes(1, hash.getData());
            rs = stmt.executeQuery();
            if (rs.next()) {
                byte rv[] = rs.getBytes(1);
                if (rs.wasNull())
                    return null;
                else
                    return new SigningPublicKey(rv);
            } else {
                return null;
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the channel's ident key", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    }

    /*
    private static final String SQL_GET_INTERNAL_MESSAGE_ID_FULL = "SELECT msgId FROM channelMessage WHERE authorChannelHash = ? AND messageId = ? AND targetChannelId = ?";
    private static final String SQL_GET_INTERNAL_MESSAGE_ID_NOAUTH = "SELECT msgId FROM channelMessage WHERE authorChannelHash IS NULL AND messageId = ? AND targetChannelId = ?";
    private static final String SQL_GET_INTERNAL_MESSAGE_ID_NOMSG = "SELECT msgId FROM channelMessage WHERE authorChannelHash = ? AND messageId IS NULL AND targetChannelId = ?";
    long getInternalMessageId(Hash author, long targetChannelId, Long messageId) {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            if ( (author != null) && (messageId != null) ) {
                stmt = _con.prepareStatement(SQL_GET_INTERNAL_MESSAGE_ID_FULL);
                stmt.setBytes(1, author.getData());
                stmt.setLong(2, messageId.longValue());
                stmt.setLong(3, targetChannelId);
            } else if ( (author == null) && (messageId != null) ) {
                stmt = _con.prepareStatement(SQL_GET_INTERNAL_MESSAGE_ID_NOAUTH);
                stmt.setLong(1, messageId.longValue());
                stmt.setLong(2, targetChannelId);
            } else if ( (author != null) && (messageId == null) ) {
                stmt = _con.prepareStatement(SQL_GET_INTERNAL_MESSAGE_ID_NOMSG);
                stmt.setBytes(1, author.getData());
                stmt.setLong(2, targetChannelId);
            } else {
                return -1;
            }
            rs = stmt.executeQuery();
            if (rs.next()) {
                long rv = rs.getLong(1);
                if (rs.wasNull())
                    return -1;
                else
                    return rv;
            } else {
                return -1;
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the internal message id", se);
            return -1;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    }
     */

    private static final String SQL_GET_CHANNEL_INFO = "SELECT channelId, channelHash, identKey, encryptKey, edition, name, description, allowPubPost, allowPubReply, expiration, readKeyMissing, pbePrompt FROM channel WHERE channelId = ?";
    private static final String SQL_GET_CHANNEL_TAG = "SELECT tag, wasEncrypted FROM channelTag WHERE channelId = ?";
    private static final String SQL_GET_CHANNEL_POST_KEYS = "SELECT authPubKey FROM channelPostKey WHERE channelId = ?";
    private static final String SQL_GET_CHANNEL_MANAGE_KEYS = "SELECT authPubKey FROM channelManageKey WHERE channelId = ?";
    private static final String SQL_GET_CHANNEL_ARCHIVES = "SELECT archiveId, wasEncrypted FROM channelArchive WHERE channelId = ?";
    private static final String SQL_GET_CHANNEL_READ_KEYS = "SELECT keyData FROM channelReadKey WHERE channelId = ?";
    private static final String SQL_GET_CHANNEL_META_HEADERS = "SELECT headerName, headerValue, wasEncrypted FROM channelMetaHeader WHERE channelId = ? ORDER BY headerName";
    private static final String SQL_GET_CHANNEL_REFERENCES = "SELECT groupId, parentGroupId, siblingOrder, name, description, uriId, referenceType, wasEncrypted FROM channelReferenceGroup WHERE channelId = ? ORDER BY parentGroupId ASC, siblingOrder ASC";
    public ChannelInfo getChannel(long channelId) {
        ensureLoggedIn();
        ChannelInfo info = new ChannelInfo();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_CHANNEL_INFO);
            stmt.setLong(1, channelId);
            rs = stmt.executeQuery();
            if (rs.next()) {
                // channelId, channelHash, identKey, encryptKey, edition, name, 
                // description, allowPubPost, allowPubReply, expiration, readKeyMissing, pbePrompt
                byte chanHash[] = rs.getBytes(2);
                byte identKey[] = rs.getBytes(3);
                byte encryptKey[] = rs.getBytes(4);
                long edition = rs.getLong(5);
                if (rs.wasNull()) edition = -1;
                String name = rs.getString(6);
                String desc = rs.getString(7);
                boolean allowPost = rs.getBoolean(8);
                if (rs.wasNull()) allowPost = false;
                boolean allowReply = rs.getBoolean(9);
                if (rs.wasNull()) allowReply = false;
                java.sql.Date exp = rs.getDate(10);
                boolean readKeyMissing = rs.getBoolean(11);
                if (rs.wasNull()) readKeyMissing = false;
                String pbePrompt = rs.getString(12);
                
                info.setChannelId(channelId);
                info.setChannelHash(new Hash(chanHash));
                info.setIdentKey(new SigningPublicKey(identKey));
                info.setEncryptKey(new PublicKey(encryptKey));
                info.setEdition(edition);
                info.setName(name);
                info.setDescription(desc);
                info.setAllowPublicPosts(allowPost);
                info.setAllowPublicReplies(allowReply);
                if (exp != null)
                    info.setExpiration(exp.getTime());
                else
                    info.setExpiration(-1);
                info.setReadKeyUnknown(readKeyMissing);
                info.setPassphrasePrompt(pbePrompt);
            } else {
                return null;
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the channel's info", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        
        stmt = null;
        rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_CHANNEL_TAG);
            stmt.setLong(1, channelId);
            rs = stmt.executeQuery();
            Set encrypted = new HashSet();
            Set unencrypted = new HashSet();
            while (rs.next()) {
                // tag, wasEncrypted
                String tag = rs.getString(1);
                boolean enc = rs.getBoolean(2);
                if (rs.wasNull())
                    enc = true;
                if (enc)
                    encrypted.add(tag);
                else
                    unencrypted.add(tag);
            }
            info.setPublicTags(unencrypted);
            info.setPrivateTags(encrypted);
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the channel's tags", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        
        stmt = null;
        rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_CHANNEL_POST_KEYS);
            stmt.setLong(1, channelId);
            rs = stmt.executeQuery();
            Set keys = new HashSet();
            while (rs.next()) {
                // authPub
                byte key[] = rs.getBytes(1);
                if (!rs.wasNull())
                    keys.add(new SigningPublicKey(key));
            }
            info.setAuthorizedPosters(keys);
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the channel's posters", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        
        stmt = null;
        rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_CHANNEL_MANAGE_KEYS);
            stmt.setLong(1, channelId);
            rs = stmt.executeQuery();
            Set keys = new HashSet();
            while (rs.next()) {
                // authPub
                byte key[] = rs.getBytes(1);
                if (!rs.wasNull())
                    keys.add(new SigningPublicKey(key));
            }
            info.setAuthorizedManagers(keys);
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the channel's managers", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        
        stmt = null;
        rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_CHANNEL_ARCHIVES);
            stmt.setLong(1, channelId);
            rs = stmt.executeQuery();
            Set pubIds = new HashSet();
            Set privIds = new HashSet();
            while (rs.next()) {
                // archiveId, wasEncrypted
                long archiveId = rs.getLong(1);
                if (rs.wasNull())
                    archiveId = -1;
                boolean enc = rs.getBoolean(2);
                if (rs.wasNull())
                    enc = true;
                if (enc)
                    privIds.add(new Long(archiveId));
                else
                    pubIds.add(new Long(archiveId));
            }
            rs.close();
            rs = null;
            stmt.close();
            stmt = null;
            
            Set pub = new HashSet();
            Set priv = new HashSet();
            for (Iterator iter = pubIds.iterator(); iter.hasNext(); ) {
                Long id = (Long)iter.next();
                ArchiveInfo archive = getArchive(id.longValue());
                if (archive != null)
                    pub.add(archive);
            }
            for (Iterator iter = privIds.iterator(); iter.hasNext(); ) {
                Long id = (Long)iter.next();
                ArchiveInfo archive = getArchive(id.longValue());
                if (archive != null)
                    priv.add(archive);
            }
            
            info.setPublicArchives(pub);
            info.setPrivateArchives(priv);
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the channel's managers", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        
        stmt = null;
        rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_CHANNEL_READ_KEYS);
            stmt.setLong(1, channelId);
            rs = stmt.executeQuery();
            Set keys = new HashSet();
            while (rs.next()) {
                // readKey
                byte key[] = rs.getBytes(1);
                if (!rs.wasNull())
                    keys.add(new SessionKey(key));
            }
            info.setReadKeys(keys);
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the channel's managers", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }

        stmt = null;
        rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_CHANNEL_META_HEADERS);
            stmt.setLong(1, channelId);
            rs = stmt.executeQuery();
            Properties pub = new Properties();
            Properties priv = new Properties();
            while (rs.next()) {
                // headerName, headerValue, wasEncrypted
                String name = rs.getString(1);
                String val = rs.getString(2);
                boolean enc = rs.getBoolean(3);
                if (rs.wasNull())
                    enc = true;
                if (enc)
                    priv.setProperty(name, val);
                else
                    pub.setProperty(name, val);
            }
            info.setPublicHeaders(pub);
            info.setPrivateHeaders(priv);
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the channel's managers", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }


        stmt = null;
        rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_CHANNEL_REFERENCES);
            stmt.setLong(1, channelId);
            rs = stmt.executeQuery();
            List refs = new ArrayList();
            while (rs.next()) {
                // groupId, parentGroupId, siblingOrder, name, description, 
                // uriId, referenceType, wasEncrypted 
                
                // ORDER BY parentGroupId, siblingOrder
                long groupId = rs.getLong(1);
                if (rs.wasNull()) groupId = -1;
                long parentGroupId = rs.getLong(2);
                if (rs.wasNull()) parentGroupId = -1;
                int order = rs.getInt(3);
                if (rs.wasNull()) order = 0;
                String name = rs.getString(4);
                String desc = rs.getString(5);
                long uriId = rs.getLong(6);
                if (rs.wasNull()) uriId = -1;
                String type = rs.getString(7);
                boolean enc = rs.getBoolean(8);
                if (rs.wasNull()) enc = true;
                
                SyndieURI uri = getURI(uriId);
                DBReferenceNode ref = new DBReferenceNode(name, uri, desc, type, uriId, groupId, parentGroupId, order, enc);
                boolean parentFound = false;
                for (int i = 0; i < refs.size(); i++) {
                    DBReferenceNode cur = (DBReferenceNode)refs.get(i);
                    if (cur.getGroupId() == parentGroupId) {
                        cur.addChild(ref);
                        parentFound = true;
                    }
                }
                if (!parentFound)
                    refs.add(ref); // rewt
            }
            info.setReferences(refs);
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the channel's managers", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        
        return info;
    }
    
    private class DBReferenceNode extends ReferenceNode {
        private long _uriId;
        private long _groupId;
        private long _parentGroupId;
        private int _siblingOrder;
        private boolean _encrypted;
        
        public DBReferenceNode(String name, SyndieURI uri, String description, String type, long uriId, long groupId, long parentGroupId, int siblingOrder, boolean encrypted) {
            super(name, uri, description, type);
            _uriId = uriId;
            _groupId = groupId;
            _parentGroupId = parentGroupId;
            _siblingOrder = siblingOrder;
            _encrypted = encrypted;
        }
        public long getURIId() { return _uriId; }
        public long getGroupId() { return _groupId; }
        public long getParentGroupId() { return _parentGroupId; }
        public int getSiblingOrder() { return _siblingOrder; }
        public boolean getEncrypted() { return _encrypted; }
    }
    
    private static final String SQL_GET_ARCHIVE = "SELECT postAllowed, readAllowed, uriId FROM archive WHERE archiveId = ?";
    private ArchiveInfo getArchive(long archiveId) { 
        ensureLoggedIn();
        ArchiveInfo info = new ArchiveInfo();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_ARCHIVE);
            stmt.setLong(1, archiveId);
            rs = stmt.executeQuery();
            Set encrypted = new HashSet();
            Set unencrypted = new HashSet();
            while (rs.next()) {
                // postAllowed, readAllowed, uriId
                boolean post = rs.getBoolean(1);
                if (rs.wasNull()) post = false;
                boolean read = rs.getBoolean(2);
                if (rs.wasNull()) read = false;
                long uriId = rs.getLong(3);
                if (rs.wasNull()) uriId = -1;
                if (uriId >= 0) {
                    SyndieURI uri = getURI(uriId);
                    info.setArchiveId(archiveId);
                    info.setPostAllowed(post);
                    info.setReadAllowed(read);
                    info.setURI(uri);
                    return info;
                }
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the archive", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        return null;
    }

    private static final String SQL_GET_MESSAGES_PRIVATE = "SELECT msgId, messageId FROM channelMessage WHERE targetChannelId = ? AND wasPrivate = TRUE AND wasAuthenticated = TRUE ORDER BY messageId ASC";
    public List getMessageIdsPrivate(Hash chan) {
        ensureLoggedIn();
        List rv = new ArrayList();
        long chanId = getChannelId(chan);
        if (chanId >= 0) {
            PreparedStatement stmt = null;
            ResultSet rs = null;
            try {
                stmt = _con.prepareStatement(SQL_GET_MESSAGES_PRIVATE);
                stmt.setLong(1, chanId);
                rs = stmt.executeQuery();
                while (rs.next()) {
                    // msgId, messageId
                    long msgId = rs.getLong(1);
                    if (!rs.wasNull())
                        rv.add(new Long(msgId));
                }
            } catch (SQLException se) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error retrieving the message list", se);
            } finally {
                if (rs != null) try { rs.close(); } catch (SQLException se) {}
                if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
            }

        }
        return rv;
    }
    
    private static final String SQL_GET_MESSAGES_AUTHORIZED = "SELECT msgId, messageId FROM channelMessage WHERE targetChannelId = ? AND wasPrivate = FALSE AND wasAuthorized = TRUE ORDER BY messageId ASC";
    public List getMessageIdsAuthorized(Hash chan) {
        ensureLoggedIn();
        List rv = new ArrayList();
        long chanId = getChannelId(chan);
        if (chanId >= 0) {
            PreparedStatement stmt = null;
            ResultSet rs = null;
            try {
                stmt = _con.prepareStatement(SQL_GET_MESSAGES_AUTHORIZED);
                stmt.setLong(1, chanId);
                rs = stmt.executeQuery();
                while (rs.next()) {
                    // msgId, messageId
                    long msgId = rs.getLong(1);
                    if (!rs.wasNull())
                        rv.add(new Long(msgId));
                }
            } catch (SQLException se) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error retrieving the message list", se);
            } finally {
                if (rs != null) try { rs.close(); } catch (SQLException se) {}
                if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
            }

        }
        return rv;
    }
    private static final String SQL_GET_MESSAGES_AUTHENTICATED = "SELECT msgId, messageId FROM channelMessage WHERE targetChannelId = ? AND wasPrivate = FALSE AND wasAuthorized = FALSE AND wasAuthenticated = TRUE ORDER BY messageId ASC";
    public List getMessageIdsAuthenticated(Hash chan) {
        ensureLoggedIn();
        List rv = new ArrayList();
        long chanId = getChannelId(chan);
        if (chanId >= 0) {
            PreparedStatement stmt = null;
            ResultSet rs = null;
            try {
                stmt = _con.prepareStatement(SQL_GET_MESSAGES_AUTHENTICATED);
                stmt.setLong(1, chanId);
                rs = stmt.executeQuery();
                while (rs.next()) {
                    // msgId, messageId
                    long msgId = rs.getLong(1);
                    if (!rs.wasNull())
                        rv.add(new Long(msgId));
                }
            } catch (SQLException se) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error retrieving the message list", se);
            } finally {
                if (rs != null) try { rs.close(); } catch (SQLException se) {}
                if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
            }

        }
        return rv;
    }
    private static final String SQL_GET_MESSAGES_UNAUTHENTICATED = "SELECT msgId, messageId FROM channelMessage WHERE targetChannelId = ? AND wasPrivate = FALSE AND wasAuthorized = FALSE AND wasAuthenticated = FALSE ORDER BY messageId ASC";
    public List getMessageIdsUnauthenticated(Hash chan) {
        ensureLoggedIn();
        List rv = new ArrayList();
        long chanId = getChannelId(chan);
        if (chanId >= 0) {
            PreparedStatement stmt = null;
            ResultSet rs = null;
            try {
                stmt = _con.prepareStatement(SQL_GET_MESSAGES_UNAUTHENTICATED);
                stmt.setLong(1, chanId);
                rs = stmt.executeQuery();
                while (rs.next()) {
                    // msgId, messageId
                    long msgId = rs.getLong(1);
                    if (!rs.wasNull())
                        rv.add(new Long(msgId));
                }
            } catch (SQLException se) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error retrieving the message list", se);
            } finally {
                if (rs != null) try { rs.close(); } catch (SQLException se) {}
                if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
            }

        }
        return rv;
    }
    
    
    private static final String SQL_GET_INTERNAL_MESSAGE_ID = "SELECT msgId FROM channelMessage WHERE scopeChannelId = ? AND messageId = ?";
    public MessageInfo getMessage(long scopeId, Long messageId) {
        ensureLoggedIn();
        if (messageId == null) return null;
        return getMessage(scopeId, messageId.longValue());
    }
    public MessageInfo getMessage(long scopeId, long messageId) {
        long msgId = getMessageId(scopeId, messageId);
        if (msgId >= 0)
            return getMessage(msgId);
        else
            return null;
    }
    public long getMessageId(long scopeId, long messageId) {
        long msgId = -1;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_INTERNAL_MESSAGE_ID);
            stmt.setLong(1, scopeId);
            stmt.setLong(2, messageId);
            rs = stmt.executeQuery();
            if (rs.next()) {
                msgId = rs.getLong(1);
                if (rs.wasNull())
                    msgId = -1;
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the message's id", se);
            return -1;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        return msgId;
    }
    
    private static final String SQL_GET_MESSAGE_INFO = "SELECT authorChannelId, messageId, targetChannelId, subject, overwriteScopeHash, overwriteMessageId, " +
                                                       "forceNewThread, refuseReplies, wasEncrypted, wasPrivate, wasAuthorized, wasAuthenticated, isCancelled, expiration, scopeChannelId, wasPBE, readKeyMissing, replyKeyMissing, pbePrompt " +
                                                       "FROM channelMessage WHERE msgId = ?";
    private static final String SQL_GET_MESSAGE_HIERARCHY = "SELECT referencedChannelHash, referencedMessageId FROM messageHierarchy WHERE msgId = ? ORDER BY referencedCloseness ASC";
    private static final String SQL_GET_MESSAGE_TAG = "SELECT tag, isPublic FROM messageTag WHERE msgId = ?";
    private static final String SQL_GET_MESSAGE_PAGE_COUNT = "SELECT COUNT(*) FROM messagePage WHERE msgId = ?";
    private static final String SQL_GET_MESSAGE_ATTACHMENT_COUNT = "SELECT COUNT(*) FROM messageAttachment WHERE msgId = ?";
    public MessageInfo getMessage(long internalMessageId) {
        ensureLoggedIn();
        MessageInfo info = new MessageInfo();
        info.setInternalId(internalMessageId);
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_MESSAGE_INFO);
            stmt.setLong(1, internalMessageId);
            rs = stmt.executeQuery();
            if (rs.next()) {
                // authorChannelId, messageId, targetChannelId, subject, overwriteScopeHash, overwriteMessageId,
                // forceNewThread, refuseReplies, wasEncrypted, wasPrivate, wasAuthorized, 
                // wasAuthenticated, isCancelled, expiration, scopeChannelId, wasPBE
                long authorId = rs.getLong(1);
                if (rs.wasNull()) authorId = -1;
                //byte author[] = rs.getBytes(1);
                long messageId = rs.getLong(2);
                if (rs.wasNull()) messageId = -1;
                long targetChannelId = rs.getLong(3);
                String subject = rs.getString(4);
                byte overwriteChannel[] = rs.getBytes(5);
                long overwriteMessage = rs.getLong(6);
                if (rs.wasNull()) overwriteMessage = -1;
                boolean forceNewThread = rs.getBoolean(7);
                if (rs.wasNull()) forceNewThread = false;
                boolean refuseReplies = rs.getBoolean(8);
                if (rs.wasNull()) refuseReplies = false;
                boolean wasEncrypted = rs.getBoolean(9);
                if (rs.wasNull()) wasEncrypted = true;
                boolean wasPrivate = rs.getBoolean(10);
                if (rs.wasNull()) wasPrivate = false;
                boolean wasAuthorized = rs.getBoolean(11);
                if (rs.wasNull()) wasAuthorized = false;
                boolean wasAuthenticated = rs.getBoolean(12);
                if (rs.wasNull()) wasAuthenticated = false;
                boolean cancelled = rs.getBoolean(13);
                if (rs.wasNull()) cancelled = false;
                java.sql.Date exp = rs.getDate(14);
                long scopeChannelId = rs.getLong(15);
                boolean wasPBE = rs.getBoolean(16);
                if (rs.wasNull())
                    wasPBE = false;
                
                boolean readKeyMissing = rs.getBoolean(17);
                if (rs.wasNull()) readKeyMissing = false;
                boolean replyKeyMissing = rs.getBoolean(18);
                if (rs.wasNull()) replyKeyMissing = false;
                String pbePrompt = rs.getString(19);
                info.setReadKeyUnknown(readKeyMissing);
                info.setReplyKeyUnknown(replyKeyMissing);
                info.setPassphrasePrompt(pbePrompt);
                
                if (authorId >= 0) info.setAuthorChannelId(authorId);
                //if (author != null) info.setAuthorChannel(new Hash(author));
                info.setMessageId(messageId);
                info.setScopeChannelId(scopeChannelId);
                ChannelInfo scope = getChannel(scopeChannelId);
                if (scope != null)
                    info.setURI(SyndieURI.createMessage(scope.getChannelHash(), messageId));
                info.setTargetChannelId(targetChannelId);
                ChannelInfo chan = getChannel(targetChannelId);
                if (chan != null)
                    info.setTargetChannel(chan.getChannelHash());
                info.setSubject(subject);
                if ( (overwriteChannel != null) && (overwriteMessage >= 0) ) {
                    info.setOverwriteChannel(new Hash(overwriteChannel));
                    info.setOverwriteMessage(overwriteMessage);
                }
                info.setForceNewThread(forceNewThread);
                info.setRefuseReplies(refuseReplies);
                info.setWasEncrypted(wasEncrypted);
                info.setWasPassphraseProtected(wasPBE);
                info.setWasPrivate(wasPrivate);
                info.setWasAuthorized(wasAuthorized);
                info.setWasAuthenticated(wasAuthenticated);
                info.setIsCancelled(cancelled);
                if (exp != null)
                    info.setExpiration(exp.getTime());
                else
                    info.setExpiration(-1);
            } else {
                return null;
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the message's info", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }

        stmt = null;
        rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_MESSAGE_HIERARCHY);
            stmt.setLong(1, internalMessageId);
            rs = stmt.executeQuery();
            List uris = new ArrayList();
            while (rs.next()) {
                // referencedChannelHash, referencedMessageId
                byte chan[] = rs.getBytes(1);
                long refId = rs.getLong(2);
                if (!rs.wasNull() && (chan != null) )
                    uris.add(SyndieURI.createMessage(new Hash(chan), refId));
            }
            info.setHierarchy(uris);
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the message list", se);
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }

        stmt = null;
        rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_MESSAGE_TAG);
            stmt.setLong(1, internalMessageId);
            rs = stmt.executeQuery();
            Set encrypted = new HashSet();
            Set unencrypted = new HashSet();
            while (rs.next()) {
                // tag, wasEncrypted
                String tag = rs.getString(1);
                boolean isPublic = rs.getBoolean(2);
                if (rs.wasNull())
                    isPublic = false;
                if (isPublic)
                    unencrypted.add(tag);
                else
                    encrypted.add(tag);
            }
            info.setPublicTags(unencrypted);
            info.setPrivateTags(encrypted);
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the message's tags", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    
        stmt = null;
        rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_MESSAGE_PAGE_COUNT);
            stmt.setLong(1, internalMessageId);
            rs = stmt.executeQuery();
            if (rs.next()) {
                int pages = rs.getInt(1);
                if (!rs.wasNull())
                    info.setPageCount(pages);
            } else {
                info.setPageCount(0);
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the message's tags", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        
        stmt = null;
        rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_MESSAGE_ATTACHMENT_COUNT);
            stmt.setLong(1, internalMessageId);
            rs = stmt.executeQuery();
            if (rs.next()) {
                int pages = rs.getInt(1);
                if (!rs.wasNull())
                    info.setAttachmentCount(pages);
            } else {
                info.setAttachmentCount(0);
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the message's tags", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        
        // get the refs...
        MessageReferenceBuilder builder = new MessageReferenceBuilder(this);
        try {
            info.setReferences(builder.loadReferences(internalMessageId));
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the message references", se);
            return null;
        }
        
        return info;
    }

    private static final String SQL_GET_MESSAGE_PAGE_DATA = "SELECT dataString FROM messagePageData WHERE msgId = ? AND pageNum = ?";
    public String getMessagePageData(long internalMessageId, int pageNum) {
        ensureLoggedIn();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_MESSAGE_PAGE_DATA);
            stmt.setLong(1, internalMessageId);
            stmt.setInt(2, pageNum);
            rs = stmt.executeQuery();
            if (rs.next())
                return rs.getString(1);
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the page data", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        return null;
    }

    private static final String SQL_GET_MESSAGE_PAGE_CONFIG = "SELECT dataString FROM messagePageConfig WHERE msgId = ? AND pageNum = ?";
    public String getMessagePageConfig(long internalMessageId, int pageNum) {
        ensureLoggedIn();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_MESSAGE_PAGE_CONFIG);
            stmt.setLong(1, internalMessageId);
            stmt.setInt(2, pageNum);
            rs = stmt.executeQuery();
            if (rs.next())
                return rs.getString(1);
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the page config", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        return null;
    }
    
    private static final String SQL_GET_MESSAGE_ATTACHMENT_DATA = "SELECT dataBinary FROM messageAttachmentData WHERE msgId = ? AND attachmentNum = ?";
    public byte[] getMessageAttachmentData(long internalMessageId, int attachmentNum) {
        ensureLoggedIn();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_MESSAGE_ATTACHMENT_DATA);
            stmt.setLong(1, internalMessageId);
            stmt.setInt(2, attachmentNum);
            rs = stmt.executeQuery();
            if (rs.next())
                return rs.getBytes(1);
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the attachment data", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        return null;
    }

    private static final String SQL_GET_MESSAGE_ATTACHMENT_CONFIG = "SELECT dataString FROM messageAttachmentConfig WHERE msgId = ? AND attachmentNum = ?";
    public String getMessageAttachmentConfig(long internalMessageId, int attachmentNum) {
        ensureLoggedIn();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_MESSAGE_ATTACHMENT_CONFIG);
            stmt.setLong(1, internalMessageId);
            stmt.setInt(2, attachmentNum);
            rs = stmt.executeQuery();
            if (rs.next())
                return rs.getString(1);
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the attachment config", se);
            return null;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        return null;
    }

    private static final String SQL_GET_PUBLIC_POSTING_CHANNELS = "SELECT channelId FROM channel WHERE allowPubPost = TRUE";
    /** list of channel ids (Long) that anyone is allowed to post to */
    public List getPublicPostingChannelIds() {
        ensureLoggedIn();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_PUBLIC_POSTING_CHANNELS);
            rs = stmt.executeQuery();
            List rv = new ArrayList();
            while (rs.next()) {
                long id = rs.getLong(1);
                if (!rs.wasNull())
                    rv.add(new Long(id));
            }
            return rv;
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the public posting channels", se);
            return Collections.EMPTY_LIST;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    }
    
    private static final String SQL_GET_BANNED = "SELECT channelHash FROM banned";
    /** list of channels (Hash) that this archive wants nothing to do with */
    public List getBannedChannels() {
        ensureLoggedIn();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_BANNED);
            rs = stmt.executeQuery();
            List rv = new ArrayList();
            while (rs.next()) {
                byte chan[] = rs.getBytes(1);
                if ( (chan != null) && (chan.length == Hash.HASH_LENGTH) )
                    rv.add(new Hash(chan));
            }
            return rv;
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving the banned channels", se);
            return Collections.EMPTY_LIST;
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    }
    
    /**
     * ban the author or channel so that no more posts from that author
     * or messages by any author in that channel will be allowed into the
     * Syndie archive.  If delete is specified, the messages themselves
     * will be removed from the archive as well as the database
     */
    public void ban(Hash bannedChannel, UI ui, boolean delete) {
        ensureLoggedIn();
        addBan(bannedChannel, ui);
        if (delete)
            executeDelete(bannedChannel, ui);
    }
    private static final String SQL_BAN = "INSERT INTO banned (channelHash) VALUES (?)";
    private void addBan(Hash bannedChannel, UI ui) {
        if (getBannedChannels().contains(bannedChannel)) {
            ui.debugMessage("Channel already banned");
            return;
        }
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_BAN);
            stmt.setBytes(1, bannedChannel.getData());
            int rows = stmt.executeUpdate();
            if (rows != 1) {
                throw new SQLException("Ban added " + rows + " rows?");
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error banning the channel", se);
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    }
    
    private static final String SQL_UNBAN = "DELETE FROM banned WHERE channelHash = ?";
    public void unban(Hash bannedChannel) {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_UNBAN);
            stmt.setBytes(1, bannedChannel.getData());
            int rows = stmt.executeUpdate();
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error unbanning the channel", se);
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    }
    
    private void executeDelete(Hash bannedChannel, UI ui) {
        // delete the banned channel itself from the archive
        // then list any messages posted by that author in other channels and
        // delete them too
        // (implicit index regen?)
        List urisToDelete = getURIsToDelete(bannedChannel);
        ui.debugMessage("Delete the following URIs: " + urisToDelete);
        for (int i = 0; i < urisToDelete.size(); i++) {
            SyndieURI uri = (SyndieURI)urisToDelete.get(i);
            deleteFromArchive(uri, ui);
            deleteFromDB(uri, ui);
        }
    }
    private void deleteFromArchive(SyndieURI uri, UI ui) {
        File archiveDir = getArchiveDir();
        File chanDir = new File(archiveDir, uri.getScope().toBase64());
        if (uri.getMessageId() == null) {
            // delete the whole channel - all posts, metadata, and even the dir
            File f[] = chanDir.listFiles();
            for (int i = 0; i < f.length; i++) {
                f[i].delete();
                ui.debugMessage("Deleted channel file " + f[i].getPath());
            }
            chanDir.delete();
            ui.debugMessage("Deleted channel dir " + chanDir.getPath());
            ui.statusMessage("Deleted " + (f.length-1) + " messages and the metadata for channel " + uri.getScope().toBase64() + " from the archive");
        } else {
            // delete just the given message
            File msgFile = new File(chanDir, uri.getMessageId().longValue() + Constants.FILENAME_SUFFIX);
            msgFile.delete();
            ui.debugMessage("Deleted message file " + msgFile.getPath());
            ui.statusMessage("Deleted the post " + uri.getScope().toBase64() + " from the archive");
        }
    }
    private static final String SQL_DELETE_MESSAGE = "DELETE FROM channelMessage WHERE msgId = ?";
    private static final String SQL_DELETE_CHANNEL = "DELETE FROM channel WHERE channelId = ?";
    void deleteFromDB(SyndieURI uri, UI ui) {
        if (uri.getMessageId() == null) {
            // delete the whole channel, though all of the posts
            // will be deleted separately
            long scopeId = getChannelId(uri.getScope());
            try {
                exec(ImportMeta.SQL_DELETE_TAGS, scopeId);
                exec(ImportMeta.SQL_DELETE_POSTKEYS, scopeId);
                exec(ImportMeta.SQL_DELETE_MANAGEKEYS, scopeId);
                exec(ImportMeta.SQL_DELETE_ARCHIVE_URIS, scopeId);
                exec(ImportMeta.SQL_DELETE_ARCHIVES, scopeId);
                exec(ImportMeta.SQL_DELETE_CHAN_ARCHIVES, scopeId);
                exec(ImportMeta.SQL_DELETE_READ_KEYS, scopeId);
                exec(ImportMeta.SQL_DELETE_CHANNEL_META_HEADER, scopeId);
                exec(ImportMeta.SQL_DELETE_CHANNEL_REF_URIS, scopeId);
                exec(ImportMeta.SQL_DELETE_CHANNEL_REFERENCES, scopeId);
                exec(SQL_DELETE_CHANNEL, scopeId);
                ui.statusMessage("Deleted the channel " + uri.getScope().toBase64() + " from the database");
            } catch (SQLException se) {
                ui.errorMessage("Unable to delete the channel " + uri.getScope().toBase64(), se);
            }
        } else {
            // delete just the given message
            long scopeId = getChannelId(uri.getScope());
            long internalId = getMessageId(scopeId, uri.getMessageId().longValue());
            try {
                exec(ImportPost.SQL_DELETE_MESSAGE_HIERARCHY, internalId);
                exec(ImportPost.SQL_DELETE_MESSAGE_TAGS, internalId);
                exec(ImportPost.SQL_DELETE_MESSAGE_ATTACHMENT_DATA, internalId);
                exec(ImportPost.SQL_DELETE_MESSAGE_ATTACHMENT_CONFIG, internalId);
                exec(ImportPost.SQL_DELETE_MESSAGE_ATTACHMENTS, internalId);
                exec(ImportPost.SQL_DELETE_MESSAGE_PAGE_DATA, internalId);
                exec(ImportPost.SQL_DELETE_MESSAGE_PAGE_CONFIG, internalId);
                exec(ImportPost.SQL_DELETE_MESSAGE_PAGES, internalId);
                exec(ImportPost.SQL_DELETE_MESSAGE_REF_URIS, internalId);
                exec(ImportPost.SQL_DELETE_MESSAGE_REFS, internalId);
                exec(SQL_DELETE_MESSAGE, internalId);
                ui.statusMessage("Deleted the post " + uri.getScope().toBase64() + ":" + uri.getMessageId() + " from the database");
            } catch (SQLException se) {
                ui.errorMessage("Error deleting the post " + uri, se);
            }
        }
    }
    
    private static final String SQL_GET_SCOPE_MESSAGES = "SELECT msgId, scopeChannelId, messageId FROM channelMessage WHERE scopeChannelId = ? OR authorChannelId = ? OR targetChannelId = ?";
    private List getURIsToDelete(Hash bannedChannel) {
        List urisToDelete = new ArrayList();
        urisToDelete.add(SyndieURI.createScope(bannedChannel));
        long scopeId = getChannelId(bannedChannel);
        if (scopeId >= 0) {
            PreparedStatement stmt = null;
            ResultSet rs = null;
            try {
                stmt = _con.prepareStatement(SQL_GET_SCOPE_MESSAGES);
                stmt.setLong(1, scopeId);
                stmt.setLong(2, scopeId);
                stmt.setLong(3, scopeId);
                rs = stmt.executeQuery();
                while (rs.next()) {
                    long msgId = rs.getLong(1);
                    if (rs.wasNull())
                        msgId = -1;
                    long scopeChanId = rs.getLong(2);
                    if (rs.wasNull())
                        scopeChanId = -1;
                    long messageId = rs.getLong(3);
                    if (rs.wasNull())
                        messageId = -1;
                    if ( (messageId >= 0) && (scopeChanId >= 0) ) {
                        ChannelInfo chanInfo = getChannel(scopeChanId);
                        if (chanInfo != null)
                            urisToDelete.add(SyndieURI.createMessage(chanInfo.getChannelHash(), messageId));
                    }
                }
            } catch (SQLException se) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error retrieving the messages to delete", se);
                return Collections.EMPTY_LIST;
            } finally {
                if (rs != null) try { rs.close(); } catch (SQLException se) {}
                if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
            }
            return urisToDelete;
        } else {
            // not known.  noop
            return urisToDelete;
        }
    }

    private static final String SQL_GET_NYMPREFS = "SELECT prefName, prefValue FROM nymPref WHERE nymId = ?";
    public Properties getNymPrefs(long nymId) {
        ensureLoggedIn();
        Properties rv = new Properties();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_NYMPREFS);
            stmt.setLong(1, nymId);
            rs = stmt.executeQuery();
            while (rs.next()) {
                String name = rs.getString(1);
                String val = rs.getString(2);
                rv.setProperty(name, val);
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error getting the nym's preferences", se);
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        return rv;
    }
    private static final String SQL_SET_NYMPREFS = "INSERT INTO nymPref (nymId, prefName, prefValue) VALUES (?, ?, ?)";
    private static final String SQL_DELETE_NYMPREFS = "DELETE FROM nymPref WHERE nymId = ?";
    public void setNymPrefs(long nymId, Properties prefs) {
        ensureLoggedIn();
        PreparedStatement stmt = null;
        try {
            exec(SQL_DELETE_NYMPREFS, nymId);
            stmt = _con.prepareStatement(SQL_SET_NYMPREFS);
            for (Iterator iter = prefs.keySet().iterator(); iter.hasNext(); ) {
                String name = (String)iter.next();
                String val = prefs.getProperty(name);
                stmt.setLong(1, nymId);
                stmt.setString(2, name);
                stmt.setString(3, val);
                stmt.executeUpdate();
            }
        } catch (SQLException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error setting the nym's preferences", se);
        } finally {
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    }
    
    private void ensureLoggedIn() throws IllegalStateException {
        try {
            if ( (_con != null) && (!_con.isClosed()) && (_nymId >= 0) )
                return;
        } catch (SQLException se) {
            // problem detecting isClosed?
        }
        throw new IllegalStateException("Not logged in");
    }

    public void backup(UI ui, String out, boolean includeArchive) {
        String dbFileRoot = getDBFileRoot();
        if (dbFileRoot == null) {
            ui.errorMessage("Unable to determine the database file root.  Is this a HSQLDB file URL?");
            ui.commandComplete(-1, null);
            return;
        }
        long now = System.currentTimeMillis();
        ui.debugMessage("Backing up the database from " + dbFileRoot + " to " + out);
        try {
            exec("CHECKPOINT");
        } catch (SQLException se) {
            ui.errorMessage("Error halting the database to back it up!", se);
            ui.commandComplete(-1, null);
            return;
        }
        try {
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
            
            ZipEntry entry = new ZipEntry("db.properties");
            File f = new File(dbFileRoot + ".properties");
            entry.setSize(f.length());
            entry.setTime(now);
            zos.putNextEntry(entry);
            copy(f, zos);
            zos.closeEntry();
            
            entry = new ZipEntry("db.script");
            f = new File(dbFileRoot + ".script");
            entry.setSize(f.length());
            entry.setTime(now);
            zos.putNextEntry(entry);
            copy(f, zos);
            zos.closeEntry();
            
            entry = new ZipEntry("db.backup");
            f = new File(dbFileRoot + ".backup");
            entry.setSize(f.length());
            entry.setTime(now);
            zos.putNextEntry(entry);
            copy(f, zos);
            zos.closeEntry();
            
            // since we just did a CHECKPOINT, no need to back up the .data file
            entry = new ZipEntry("db.data");
            entry.setSize(0);
            entry.setTime(now);
            zos.putNextEntry(entry);
            zos.closeEntry();
            
            if (includeArchive)
                backupArchive(ui, zos);
            
            zos.finish();
            zos.close();
            
            ui.statusMessage("Database backed up to " + out);
            ui.commandComplete(0, null);
        } catch (IOException ioe) {
            ui.errorMessage("Error backing up the database", ioe);
            ui.commandComplete(-1, null);
        }
    }
    
    private void backupArchive(UI ui, ZipOutputStream out) throws IOException {
        ui.errorMessage("Backing up the archive is not yet supported.");
        ui.errorMessage("However, you can just, erm, tar cjvf the $data/archive/ dir");
    }
    
    private String getDBFileRoot() { return getDBFileRoot(_url); }
    private String getDBFileRoot(String url) {
        if (url.startsWith("jdbc:hsqldb:file:")) {
            String file = url.substring("jdbc:hsqldb:file:".length());
            int end = file.indexOf(";");
            if (end != -1)
                file = file.substring(0, end);
            return file;
        } else {
            return null;
        }
    }
    
    private void copy(File in, OutputStream out) throws IOException {
        byte buf[] = new byte[4096];
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(in);
            int read = -1;
            while ( (read = fis.read(buf)) != -1)
                out.write(buf, 0, read);
            fis.close();
            fis = null;
        } finally {
            if (fis != null) fis.close();
        }
    }

    /**
     * @param in zip archive containing db.{properties,script,backup,data}
     *           to be extracted onto the given db
     * @param db JDBC url (but it must be an HSQLDB file URL).  If the database
     *           already exists (and is of a nonzero size), it will NOT be
     *           overwritten
     */
    public void restore(UI ui, String in, String db) {
        File inFile = new File(in);
        if ( (!inFile.exists()) || (inFile.length() <= 0) ) {
            ui.errorMessage("Database backup does not exist: " + inFile.getPath());
            ui.commandComplete(-1, null);
            return;
        }
        
        String root = getDBFileRoot(db);
        if (root == null) {
            ui.errorMessage("Database restoration is only possible with file urls");
            ui.commandComplete(-1, null);
            return;
        }
        File prop = new File(root + ".properties");
        File script = new File(root + ".script");
        File backup = new File(root + ".backup");
        File data = new File(root + ".data");
        if ( (prop.exists() && (prop.length() > 0)) ||
             (script.exists() && (script.length() > 0)) ||
             (backup.exists() && (backup.length() > 0)) ||
             (data.exists() && (data.length() > 0)) ) {
            ui.errorMessage("Not overwriting existing non-empty database files: ");
            ui.errorMessage(prop.getPath());
            ui.errorMessage(script.getPath());
            ui.errorMessage(backup.getPath());
            ui.errorMessage(data.getPath());
            ui.errorMessage("If they are corrupt or you really want to replace them,");
            ui.errorMessage("delete them first, then rerun the restore command");
            ui.commandComplete(-1, null);
            return;
        }

        String url = _url;
        String login = _login;
        String pass = _pass;
        long nymId = _nymId;
        
        if (_con != null) {
            ui.statusMessage("Disconnecting from the database to restore...");
            close();
        }
        
        ui.statusMessage("Restoring the database from " + in + " to " + root);
        
        try {
            ZipInputStream zis = new ZipInputStream(new FileInputStream(in));
            
            while (true) {
                ZipEntry entry = zis.getNextEntry();
                if (entry == null)
                    break;
                String name = entry.getName();
                if ("db.properties".equals(name)) {
                    copy(zis, prop);
                } else if ("db.script".equals(name)) {
                    copy(zis, script);
                } else if ("db.backup".equals(name)) {
                    copy(zis, backup);
                } else if ("db.data".equals(name)) {
                    copy(zis, data);
                } else {
                    ui.debugMessage("Ignoring backed up file " + name + " for now");
                }
            }
            
            zis.close();
            
            ui.statusMessage("Database restored from " + in);
            
            if ( (url != null) && (login != null) && (pass != null) ) {
                ui.statusMessage("Reconnecting to the database");
                try {
                    connect(url, login, pass);
                } catch (SQLException se) {
                    ui.errorMessage("Not able to log back into the database", se);
                }
            }
            ui.commandComplete(0, null);
        } catch (IOException ioe) {
            ui.errorMessage("Error backing up the database", ioe);
            ui.commandComplete(-1, null);
        }
    }
    
    private void copy(InputStream in, File out) throws IOException {
        byte buf[] = new byte[4096];
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(out);
            int read = -1;
            while ( (read = in.read(buf)) != -1)
                fos.write(buf, 0, read);
            fos.close();
            fos = null;
        } finally {
            if (fos != null) fos.close();
        }
    }

    private static final String SQL_GET_ALIASES = "SELECT aliasName, aliasValue FROM nymCommandAlias WHERE nymId = ? ORDER BY aliasName ASC";
    /** map of command name (String) to command line (String) */
    public Map getAliases(long nymId) {
        TreeMap rv = new TreeMap();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = _con.prepareStatement(SQL_GET_ALIASES);
            stmt.setLong(1, nymId);
            rs = stmt.executeQuery();
            while (rs.next()) {
                String name = (String)rs.getString(1);
                String value = rs.getString(2);
                if ( (name != null) && (value != null) && (name.length() > 0) )
                    rv.put(name, value);
            }
            rs.close();
            rs = null;
            stmt.close();
            stmt = null;
        } catch (SQLException se) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error fetching aliases", se);
        } finally {
            if (rs != null) try { rs.close(); } catch (SQLException se) {}
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
        return rv;
    }

    private static final String SQL_DELETE_ALIAS = "DELETE FROM nymCommandAlias WHERE nymId = ? AND aliasName = ?";
    private static final String SQL_ADD_ALIAS = "INSERT INTO nymCommandAlias (nymId, aliasName, aliasValue) VALUES (?, ?, ?)";
    public void addAlias(long nymId, String name, String value) {
        PreparedStatement stmt = null;
        try {
            stmt = _con.prepareStatement(SQL_DELETE_ALIAS);
            stmt.setLong(1, nymId);
            stmt.setString(2, name);
            stmt.executeUpdate();
            stmt.close();
            
            if ( (value != null) && (value.length() > 0) ) {
                stmt = _con.prepareStatement(SQL_ADD_ALIAS);
                stmt.setLong(1, nymId);
                stmt.setString(2, name);
                stmt.setString(3, value);
                stmt.executeUpdate();
                stmt.close();
            }
            stmt = null;
        } catch (SQLException se) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error updating alias", se);
        } finally {
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }
    }
}
