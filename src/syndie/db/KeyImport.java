package syndie.db;

import java.io.*;
import java.sql.*;
import java.util.List;
import net.i2p.I2PAppContext;
import net.i2p.data.*;
import syndie.Constants;
import syndie.data.NymKey;

/**
 *CLI keyimport
 * --db $dbURL
 * --login $login
 * --pass $pass
 * --keyfile $keyFile      // keytype: (manage|reply|read)\nscope: $base64(channelHash)\nraw: $base64(data)\n
 * [--authentic $boolean]
 * [--expireExisting $boolean] // if true, expire all other existing keys of the same type for the scope
 */
public class KeyImport extends CommandImpl {
    public KeyImport() {}
    public DBClient runCommand(Opts args, UI ui, DBClient client) {
        if ( (client == null) || (!client.isLoggedIn()) ) {
            List missing = args.requireOpts(new String[] { "db", "login", "pass", "keyfile" });
            if (missing.size() > 0) {
                ui.errorMessage("Invalid options, missing " + missing);
                ui.commandComplete(-1, null);
                return client;
            }
        }
        
        String db = args.getOptValue("db");
        String login = args.getOptValue("login");
        String pass = args.getOptValue("pass");
        String keyFile = args.getOptValue("keyfile");
        boolean authentic = args.getOptBoolean("authentic", false);
        boolean expireExisting = args.getOptBoolean("expireExisting", false);
        
        return importKey(ui, client, db, login, pass, keyFile, authentic, expireExisting);
    }
    
    private DBClient importKey(UI ui, DBClient client, String db, String login, String pass, String keyFile, boolean authentic, boolean expireExisting) {
        File f = new File(keyFile);
        if (!f.exists()) {
            ui.errorMessage("Key file does not exist: " + keyFile);
            ui.commandComplete(-1, null);
            return client;
        }
        FileInputStream fin = null;
        try {
            fin = new FileInputStream(f);
            return importKey(ui, client, db, login, pass, fin, authentic, expireExisting);
        } catch (IOException ioe) {
            ui.errorMessage("Error importing the key", ioe);
            ui.commandComplete(-1, null);
            return client;
        } finally {
            if (fin != null) try { fin.close(); } catch (IOException ioe) {}
        }
    }
    public static DBClient importKey(UI ui, DBClient client, InputStream fin, boolean authentic, boolean expireExisting) throws IOException {
        return importKey(ui, client, null, null, null, fin, authentic, expireExisting);
    }
    public static DBClient importKey(UI ui, DBClient client, String db, String login, String pass, InputStream fin, boolean authentic, boolean expireExisting) throws IOException {
        String line = DataHelper.readLine(fin);
        if (!line.startsWith("keytype: ") || (line.length() < ("keytype: ".length() + 1)))
            throw new IOException("Invalid type line: " + line);
        String type = line.substring("keytype: ".length()).trim();

        line = DataHelper.readLine(fin);
        if (!line.startsWith("scope: ") || (line.length() < ("scope: ".length() + 1)))
            throw new IOException("Invalid scope line: " + line);
        String scope = line.substring("scope: ".length()).trim();

        line = DataHelper.readLine(fin);
        if (!line.startsWith("raw: ") || (line.length() < ("raw: ".length() + 1)))
            throw new IOException("Invalid raw line: " + line);
        String raw = line.substring("raw: ".length()).trim();

        byte scopeData[] = Base64.decode(scope);
        if ( (scopeData != null) && (scopeData.length != Hash.HASH_LENGTH) )
            scopeData = null;
        byte rawData[] = Base64.decode(raw);

        //ui.debugMessage("importing from " + f.getPath() +": type=" + type + " scope=" + scope + " raw=" + raw);
        client = importKey(ui, client, db, login, pass, type, new Hash(scopeData), rawData, authentic, expireExisting);
        fin = null;
        return client;
    }
    
    private static final String SQL_INSERT_KEY = "INSERT INTO nymKey " +
                                                 "(nymId, keyChannel, keyFunction, keyType, keyData, keySalt, authenticated, keyPeriodBegin, keyPeriodEnd)" +
                                                 " VALUES " +
                                                 "(?, ?, ?, ?, ?, ?, ?, NULL, NULL)";
    private static final String SQL_EXPIRE = "UPDATE nymKey SET keyPeriodEnd = NOW() WHERE nymId = ? AND keyChannel = ? and keyFunction = ?";
    public static DBClient importKey(UI ui, DBClient client, String type, Hash scope, byte[] raw, boolean authenticated, boolean expireExisting) {
        return importKey(ui, client, null, null, null, type, scope, raw, authenticated, expireExisting);
    }
    public static DBClient importKey(UI ui, DBClient client, String db, String login, String pass, String type, Hash scope, byte[] raw, boolean authenticated, boolean expireExisting) {
        PreparedStatement stmt = null;
        try {
            long nymId = -1;
            if ( (db != null) && (login != null) && (pass != null) ) {
                if (client == null)
                    client = new DBClient(I2PAppContext.getGlobalContext(), new File(TextEngine.getRootPath()));
                else
                    client.close();
                client.connect(db);
                nymId = client.getNymId(login, pass);
            } else if (client != null) {
                nymId = client.getLoggedInNymId();
                pass = client.getPass();
            }
            if (nymId == -1)
                throw new SQLException("Login unknown");
            else if (nymId == -2)
                throw new SQLException("Password invalid");

            List existing = client.getNymKeys(nymId, pass, scope, type);
            for (int i = 0; i < existing.size(); i++) {
                NymKey cur = (NymKey)existing.get(i);
                if (DataHelper.eq(cur.getData(), raw)) {
                    ui.statusMessage("Key already imported (type: " + type + ", " + cur.getFunction() + "/" 
                                     + cur.getType() + " raw.length=" + raw.length + ", " + cur.getData().length);
                    //ui.commandComplete(0, null);
                    return client;
                }
            }
            
            // wait until after the above already-exists check
            if (expireExisting) {
                Connection con = client.con();
                //"UPDATE nymKey SET keyPeriodEnd = NOW() WHERE nymId = ? AND keyChannel = ? and keyFunction = ?";
                stmt = con.prepareStatement(SQL_EXPIRE);
                stmt.setLong(1, nymId);
                stmt.setBytes(2, scope.getData());
                stmt.setString(3, type);
                stmt.executeUpdate();
                stmt.close();
                stmt = null;
            }
            
            if (Constants.KEY_FUNCTION_MANAGE.equals(type) || Constants.KEY_FUNCTION_POST.equals(type)) {
                SigningPrivateKey priv = new SigningPrivateKey(raw);
                SigningPublicKey pub = client.ctx().keyGenerator().getSigningPublicKey(priv);
                if (pub.calculateHash().equals(scope)) {
                    ui.statusMessage("Importing an identity key for " + scope.toBase64());
                } else {
                    ui.debugMessage("Importing a key that is NOT an identity key for " + scope.toBase64() + "?");
                    ui.debugMessage("calculated pub: " + pub.calculateHash().toBase64());
                    ui.debugMessage("aka " + pub.toBase64());
                }
            }
            
            byte salt[] = new byte[16]; // overwritten by pbeEncrypt
            byte encrypted[] = client.pbeEncrypt(raw, salt);
            
            Connection con = client.con();
            stmt = con.prepareStatement(SQL_INSERT_KEY);
            stmt.setLong(1, nymId);
            stmt.setBytes(2, scope.getData());
            stmt.setString(3, type);
            if (Constants.KEY_FUNCTION_READ.equals(type))
                stmt.setString(4, Constants.KEY_TYPE_AES256);
            else if (Constants.KEY_FUNCTION_MANAGE.equals(type))
                stmt.setString(4, Constants.KEY_TYPE_DSA);
            else if (Constants.KEY_FUNCTION_POST.equals(type))
                stmt.setString(4, Constants.KEY_TYPE_DSA);
            else if (Constants.KEY_FUNCTION_REPLY.equals(type))
                stmt.setString(4, Constants.KEY_TYPE_ELGAMAL2048);
            
            stmt.setBytes(5, encrypted);
            stmt.setBytes(6, salt);
            stmt.setBoolean(7, authenticated);
            int rows = stmt.executeUpdate();
            if (rows == 1) {
                ui.statusMessage("Keys imported (type " + type + " scope " + scope.toBase64() + " hash:" + client.ctx().sha().calculateHash(raw).toBase64() + " rows " + rows + ")");
            } else {
                throw new SQLException("Error importing keys: row count of " + rows);
            }
            con.commit();
            stmt.close();
            stmt = null;
            
            ui.commandComplete(0, null);
        } catch (SQLException se) {
            ui.errorMessage("Error importing the key", se);
            ui.commandComplete(-1, null);
        } finally {
            if (stmt != null) try { stmt.close(); } catch (SQLException se) {}
        }

        return client;
    }
    
    public static void main(String args[]) {
        try {
        CLI.main(new String[] { "keyimport", 
                                "--db", "jdbc:hsqldb:file:/tmp/cli",
                                "--login", "j", "--pass", "j", 
                                "--keyfile", "/tmp/manageOut" });
        } catch (Exception e) { e.printStackTrace(); }
    }
}
