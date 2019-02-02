package org.pepsoft.worldpainter.wurm;

import org.pepsoft.util.DesktopUtils;
import org.pepsoft.worldpainter.Platform;
import org.pepsoft.worldpainter.World2;
import org.pepsoft.worldpainter.exporting.WorldExporter;
import org.pepsoft.worldpainter.mapexplorer.MapRecognizer;
import org.pepsoft.worldpainter.plugins.AbstractPlugin;
import org.pepsoft.worldpainter.plugins.PlatformProvider;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;

import static org.pepsoft.minecraft.Constants.DEFAULT_MAX_HEIGHT_2;
import static org.pepsoft.worldpainter.Constants.DIM_NORMAL;
import static org.pepsoft.worldpainter.GameType.SURVIVAL;
import static org.pepsoft.worldpainter.Generator.DEFAULT;

public class WurmPlatformProvider extends AbstractPlugin implements PlatformProvider {
    public WurmPlatformProvider() {
        super("WurmUnlimitedExporter", Version.VERSION);
    }

    // PlatformProvider

    @Override
    public WorldExporter getExporter(World2 world) {
        Platform platform = world.getPlatform();
        if (! platform.equals(WURM_UNLIMITED)) {
            throw new IllegalArgumentException("Platform " + platform + " not supported");
        }
        return new WurmUnlimitedExporter(world);
    }

    @Override
    public File getDefaultExportDir(Platform platform) {
        if (! platform.equals(WURM_UNLIMITED)) {
            throw new IllegalArgumentException("Platform " + platform + " not supported");
        }
        // TODO: get actual Wurm Unlimited maps location
        return DesktopUtils.getDocumentsFolder();
    }

    @Override
    public MapRecognizer getMapRecognizer() {
        return null; // TODO: implement
    }

    @Override
    public Collection<Platform> getKeys() {
        return Collections.singleton(WURM_UNLIMITED);
    }

    public static final Platform WURM_UNLIMITED = new Platform(
            "org.pepsoft.wurm",
            "Wurm Unlimited",
            32, DEFAULT_MAX_HEIGHT_2, 2048,
            Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE,
            Collections.singletonList(SURVIVAL),
            Collections.singletonList(DEFAULT),
            Collections.singletonList(DIM_NORMAL),
            EnumSet.noneOf(Platform.Capability.class));
}
