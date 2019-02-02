package org.pepsoft.worldpainter.wurm;

import java.io.IOException;
import java.util.Properties;

/**
 * Created by pepijn on 25-10-2015.
 */
public class Version {
    public static final String VERSION;

    static {
        Properties props = new Properties();
        try {
            props.load(Version.class.getResourceAsStream("/org.pepsoft.worldpainter.wurm.version.properties"));
            VERSION = props.getProperty("version");
        } catch (IOException e) {
            throw new RuntimeException("I/O error reading version number from classpath", e);
        }
    }
}
