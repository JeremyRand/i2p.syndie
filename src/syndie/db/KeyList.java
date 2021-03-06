package syndie.db;

import java.io.File;
import java.sql.SQLException;
import java.util.*;

import net.i2p.I2PAppContext;
import net.i2p.crypto.KeyGenerator;
import net.i2p.data.Hash;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.SecureFile;

import syndie.Constants;
import syndie.data.NymKey;

/**
 *CLI keylist
 * --db $url
 * --login $login
 * --pass $pass
 * [--channel $base64(channelHash)]
 * [--function (read|manage|reply|post)]
 */
public class KeyList extends CommandImpl {

    public static String getHelp(String cmd) {
        return "           : lists private keys";
    }

    public DBClient runCommand(Opts args, UI ui, DBClient client) {
        if ( (client == null) || (!client.isLoggedIn()) ) {
            List missing = args.requireOpts(new String[] { "db", "login", "pass" });
            if (missing.size() > 0) {
                ui.errorMessage("Invalid options, missing " + missing);
                ui.commandComplete(-1, null);
                return client;
            }
        }
        
        try {
            long nymId = -1;
            if (args.dbOptsSpecified()) {
                if (client == null)
                    client = new DBClient(I2PAppContext.getGlobalContext(), new SecureFile(TextEngine.getRootPath()));
                else
                    client.close();
                nymId = client.connect(args.getOptValue("db"), args.getOptValue("login"), args.getOptValue("pass"));
                if (nymId < 0) {
                    ui.errorMessage("Login invalid");
                    ui.commandComplete(-1, null);
                    return client;
                }
            } 
            if ( (client != null) && (nymId < 0) )
                nymId = client.getLoggedInNymId();
            if (nymId < 0) {
                ui.errorMessage("Not logged in and no db specified");
                ui.commandComplete(-1, null);
                return client;
            }
            byte val[] = args.getOptBytes("channel");
            Hash channel = null;
            if ( (val != null) && (val.length == Hash.HASH_LENGTH) )
                channel = Hash.create(val);
            String fn = args.getOptValue("function");
            List<NymKey> keys = client.getNymKeys(nymId, client.getPass(), channel, fn, false);
            Collections.sort(keys, new NKComp());
            ui.statusMessage("Found " + keys.size() + " private keys:");
            Hash last = null;
            for (NymKey key : keys) {
                Hash cur = key.getChannel();
                if (!cur.equals(last)) {
                    ui.statusMessage("Private keys for " + cur.toBase64());
                    last = cur;
                }
                ui.statusMessage("    " + key.toString());
                if (Constants.KEY_TYPE_DSA.equals(key.getType())) {
                    SigningPrivateKey priv = new SigningPrivateKey(key.getData());
                    SigningPublicKey pub = KeyGenerator.getSigningPublicKey(priv);
                    Hash pubIdent = pub.calculateHash();
                    if (key.getChannel().equals(pubIdent)) {
                        ui.statusMessage("     - verifies as an identity key (size: " + key.getData().length + "/" + SigningPrivateKey.KEYSIZE_BYTES + ")");
                    } else {
                        ui.statusMessage("     - DOES NOT verify as an identity key (size: " + key.getData().length + "/" + SigningPrivateKey.KEYSIZE_BYTES + ")");
                    }
                }
            }
            List<NymKey> goodKeys = client.getNymKeys(nymId, client.getPass(), channel, fn, true);
            // O(n**2)
            keys.removeAll(goodKeys);
            if (!keys.isEmpty()) {
                ui.statusMessage("The following keys did not verify with password:");
                for (NymKey key : keys) {
                    ui.statusMessage("    " + key.toString());
                }
            }
            ui.commandComplete(0, null);
        } catch (SQLException se) {
            ui.errorMessage("Invalid database URL", se);
            ui.commandComplete(-1, null);
        //} finally {
        //    if (client != null) client.close();
        }
        return client;
    }
    
    /**
     *  Sort the keys for pretty printing
     *  @since 1.102b-9
     */
    private static class NKComp implements Comparator<NymKey> {
        public int compare(NymKey l, NymKey r) {
            int rv = l.getChannel().toBase64().compareTo(r.getChannel().toBase64());
            if (rv != 0)
                return rv;
            rv = l.getFunction().compareTo(r.getFunction());
            if (rv != 0)
                return rv;
            return l.getType().compareTo(r.getType());
        }
    }

/****
    public static void main(String args[]) {
        try {
        CLI.main(new String[] { "keylist", 
                                "--db", "jdbc:hsqldb:file:/tmp/cli",
                                "--login", "j",
                                "--pass", "j" });
        } catch (Exception e) { e.printStackTrace(); }
    }
****/
}
