package org.qcri.rheem.sqlite3;

import org.qcri.rheem.sqlite3.platform.Sqlite3Platform;
import org.qcri.rheem.sqlite3.plugin.Sqlite3Plugin;

/**
 * Register for relevant components of this module.
 */
public class Sqlite3 {

    private final static Sqlite3Plugin PLUGIN = new Sqlite3Plugin();

    /**
     * Retrieve the {@link Sqlite3Plugin}.
     *
     * @return the {@link Sqlite3Plugin}
     */
    public static Sqlite3Plugin plugin() {
        return PLUGIN;
    }


    /**
     * Retrieve the {@link Sqlite3Platform}.
     *
     * @return the {@link Sqlite3Platform}
     */
    public static Sqlite3Platform platform() {
        return Sqlite3Platform.getInstance();
    }
    
}
