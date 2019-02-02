package org.pepsoft.worldpainter.wurm;

import com.wurmonline.mesh.*;
import com.wurmonline.wurmapi.api.MapData;
import com.wurmonline.wurmapi.api.WurmAPI;
import org.pepsoft.minecraft.Block;
import org.pepsoft.minecraft.ChunkFactory;
import org.pepsoft.util.*;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.exporting.WorldExporter;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.util.FileInUseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import static com.wurmonline.mesh.Tiles.Tile.*;
import static com.wurmonline.mesh.TreeData.TreeType.*;
import static org.pepsoft.worldpainter.Constants.*;

/**
 * An exporter of WorldPainter worlds to Wurm Unlimited maps.
 *
 * Created by pepijn on 23-10-15.
 */
public class WurmUnlimitedExporter implements WorldExporter {
    public WurmUnlimitedExporter(World2 world) {
        this.world = world;
    }

    // WorldExporter

    @Override
    public World2 getWorld() {
        return world;
    }

    @Override
    public File selectBackupDir(File worldDir) throws IOException {
        File baseDir = worldDir.getParentFile();
        File minecraftDir = baseDir.getParentFile();
        File backupsDir = new File(minecraftDir, "backups");
        if ((! backupsDir.isDirectory()) &&  (! backupsDir.mkdirs())) {
            backupsDir = new File(System.getProperty("user.home"), "WorldPainter Backups");
            if ((! backupsDir.isDirectory()) && (! backupsDir.mkdirs())) {
                throw new IOException("Could not create " + backupsDir);
            }
        }
        return new File(backupsDir, worldDir.getName() + "." + DATE_FORMAT.format(new Date()));
    }

    @Override
    public Map<Integer, ChunkFactory.Stats> export(File baseDir, String name, File backupDir, ProgressReceiver progressReceiver) throws IOException, ProgressReceiver.OperationCancelled {
        logger.info("WurmUnlimitedExporter {} starting", Version.VERSION);

        Properties config = new Properties();
        config.put("tile.kelp.minimumDepth", "3");

        // Get settings from user
        Dimension dim = world.getDimension(DIM_NORMAL);
        if (dim == null) {
            throw new IllegalArgumentException("World does not have a surface dimension");
        }
        WurmSettingsDialog settingsDialog = new WurmSettingsDialog(App.getInstanceIfExists(), dim);
        settingsDialog.setVisible(true);
        if (settingsDialog.isCancelled()) {
            throw new ProgressReceiver.OperationCancelled("Export cancelled by user");
        }
        ScalingMode scalingMode = settingsDialog.getScalingMode();
        switch (scalingMode) {
            case MINECRAFT:
                logger.info("Selected scaling mode: Minecraft (horizontal: 4:1, vertical: 1:1)");
                break;
            case WURM_UNSCALED:
                logger.info("Selected scaling mode: Wurm Unlimited Unscaled (horizontal: 1:1, vertical: 1:1)");
                break;
            case WURM_SCALED:
                logger.info("Selected scaling mode: Wurm Unlimited Scaled (horizontal: 1:1, vertical: 1:4)");
                break;
        }

        // Calculate dimensions
        final int waterLevel = ((HeightMapTileFactory) dim.getTileFactory()).getWaterHeight();
        final int widthInTiles = dim.getWidth(), heightInTiles = dim.getHeight();
        final int sizeInTiles = Math.max(widthInTiles, heightInTiles);
        final boolean scaledHorizontally = scalingMode == ScalingMode.MINECRAFT, scaledVertically = scalingMode == ScalingMode.WURM_SCALED;
        if (scaledHorizontally) {
            logger.info("WorldPainter world size: {}x{} m (Wurm Unlimited: {}x{} tiles)", widthInTiles << TILE_SIZE_BITS, heightInTiles << TILE_SIZE_BITS, (widthInTiles << TILE_SIZE_BITS) / 4, (heightInTiles << TILE_SIZE_BITS) / 4);
        } else {
            logger.info("WorldPainter world size: {}x{} m (Wurm Unlimited: {}x{} tiles)", widthInTiles << TILE_SIZE_BITS, heightInTiles << TILE_SIZE_BITS, widthInTiles << TILE_SIZE_BITS, heightInTiles << TILE_SIZE_BITS);
        }
        if (scaledVertically) {
            logger.info("WorldPainter max. height: {} m (water: {} m; Wurm max. height: {} dirts)", world.getMaxHeight(), waterLevel, (world.getMaxHeight() - waterLevel) * 40);
        } else {
            logger.info("WorldPainter max. height: {} m (water: {} m; Wurm max. height: {} dirts)", world.getMaxHeight(), waterLevel, (world.getMaxHeight() - waterLevel) * 10);
        }
        int powerOfTwo = scaledHorizontally
                ? Math.max((int) Math.ceil(Math.log((sizeInTiles << TILE_SIZE_BITS) / 4) / LOG_2), 10)
                : Math.max((int) Math.ceil(Math.log(sizeInTiles << TILE_SIZE_BITS) / LOG_2), 10);
        if (powerOfTwo > 15) {
            logger.warn("This world is larger than the maximum Wurm Unlimited map size (2¹⁵); only the northwest part of it will be exported");
            powerOfTwo = 15;
        }
        final int offsetX = -dim.getLowestX() << TILE_SIZE_BITS, offsetY = -dim.getLowestY() << TILE_SIZE_BITS;
        final int tileX1 = dim.getLowestX(), tileX2 = tileX1 + Math.min(dim.getWidth(), scaledHorizontally ? 256 : 1024) - 1, tileY1 = dim.getLowestY(), tileY2 = tileY1 + Math.min(dim.getHeight(), scaledHorizontally ? 256 : 1024) - 1;

        // Initialise noise fields
        long seed = dim.getSeed();
        dandelionNoise.setSeed(seed + DANDELION_SEED_OFFSET);
        roseNoise.setSeed(seed + ROSE_SEED_OFFSET);
        flowerTypeField.setSeed(seed + FLOWER_TYPE_FIELD_OFFSET);
        grassNoise.setSeed(seed + GRASS_SEED_OFFSET);
        tallGrassNoise.setSeed(seed + DOUBLE_TALL_GRASS_SEED_OFFSET);
        kelpNoise.setSeed(seed + KELP_SEED_OFFSET);
        reedNoise.setSeed(seed + REED_SEED_OFFSET);

        // Backup existing level
        File worldDir = new File(baseDir, FileUtils.sanitiseName(name));
        logger.info("Creating Wurm Unlimited map named \"{}\" of size 2^{} ({} tiles) at {}", world.getName(), powerOfTwo, (int) Math.pow(2, powerOfTwo), worldDir);
        if (worldDir.isDirectory()) {
            if (backupDir != null) {
                logger.info("Directory already exists; backing up to " + backupDir);
                if (! worldDir.renameTo(backupDir)) {
                    throw new FileInUseException("Could not move " + worldDir + " to " + backupDir);
                }
            } else {
                throw new IllegalStateException("Directory already exists and no backup directory specified");
            }
        }

        BitSet unsupportedBlocksSet = new BitSet(4096);
        WurmAPI wurmAPI = WurmAPI.create(worldDir.getAbsolutePath(), powerOfTwo);
        int totalTiles = (tileX2 - tileX1 + 1) * (tileY2 - tileY1 + 1), tileCount = 0;
        try {
            MapData mapData = wurmAPI.getMapData();
            for (int tileX = tileX1; tileX <= tileX2; tileX++) {
                for (int tileY = tileY1; tileY <= tileY2; tileY++) {
                    if (dim.getTile(tileX, tileY) != null) {
                        processTile(dim, waterLevel, offsetX, offsetY, mapData, tileX, tileY, unsupportedBlocksSet, scalingMode, config);
                    }
                    tileCount++;
                    if (progressReceiver != null) {
                        progressReceiver.setProgress((float) totalTiles / tileCount);
                    }
                }
            }
            mapData.saveChanges();
        } finally {
            wurmAPI.close();
        }

        // Report on unsupported features
        StringBuilder warnings = new StringBuilder();
        if (! unsupportedBlocksSet.isEmpty()) {
            warnings.append("Unsupported materials exported as dirt:\n");
            StringBuilder sb = new StringBuilder();
            unsupportedBlocksSet.stream().forEach(blockId -> {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(Block.BLOCKS[blockId]);
                warnings.append("  ");
                warnings.append(Block.BLOCKS[blockId]);
                warnings.append('\n');
            });
            logger.warn("Unsupported materials exported as dirt: {}", sb.toString());
        }
        Set<Layer> layers = dim.getAllLayers(false);
        layers.removeAll(Arrays.asList(Frost.INSTANCE, DeciduousForest.INSTANCE, PineForest.INSTANCE, Jungle.INSTANCE, SwampLand.INSTANCE, ReadOnly.INSTANCE));
        if (! layers.isEmpty()) {
            if (warnings.length() > 0) {
                warnings.append('\n');
            }
            warnings.append("Unsupported layers ignored:\n");
            logger.warn("Unsupported layers ignored: {}", layers);
            layers.forEach(layer -> {
                warnings.append(layer);
                warnings.append('\n');
            });
        }
        if (warnings.length() > 0) {
            JOptionPane.showMessageDialog(App.getInstanceIfExists(), warnings, "Export Warnings", JOptionPane.WARNING_MESSAGE);
        }

        logger.info("WurmUnlimitedExporter finished");
        return Collections.singletonMap(DIM_NORMAL, new ChunkFactory.Stats());
    }

    private static void processTile(final Dimension dim, final int waterLevel, final int offsetX, final int offsetY, final MapData mapData, final int tileX, final int tileY, final BitSet unsupportedBlocksSet, final ScalingMode scalingMode, final Properties config) {
        if (logger.isDebugEnabled()) {
            logger.debug("Processing tile {},{}", tileX, tileY);
        }

        final float tileKelpMinimumDepth = Float.parseFloat(config.getProperty("tile.kelp.minimumDepth"));
        final int scaledWaterLevel = (scalingMode == ScalingMode.WURM_SCALED) ? (waterLevel * 4) : waterLevel;

        // Copy information from tile, scaling and averaging if necessary
        final long seed = dim.getSeed();
        final int wTileSize, wOffsetX, wOffsetY;
        final boolean scaledHorizontally = scalingMode == ScalingMode.MINECRAFT, scaledVertically = scalingMode == ScalingMode.WURM_SCALED;
        if (scaledHorizontally) {
            wTileSize = TILE_SIZE / 4;
            wOffsetX = ((tileX << TILE_SIZE_BITS) + offsetX) / 4;
            wOffsetY = ((tileY << TILE_SIZE_BITS) + offsetY) / 4;
            for (int dx = 0; dx <= TILE_SIZE; dx += 4) {
                for (int dy = 0; dy <= TILE_SIZE; dy += 4) {
                    final int x = (tileX << TILE_SIZE_BITS) + dx, y = (tileY << TILE_SIZE_BITS) + dy;
                    final int wDx = dx >> 2, wDy = dy >> 2;
                    cornerHeights[wDx][wDy] = getAverageHeight(dim, x, y);
                    if ((dx < TILE_SIZE) && (dy < TILE_SIZE)) {
                        topLayerDepths[wDx][wDy] = getAverageTopLayerDepth(dim, x, y);
                        terrains[wDx][wDy] = getPrevalentTerrain(dim, x, y);
                        blocks[wDx][wDy] = getPrevalentBlockID(dim, x, y, unsupportedBlocksSet);
                    }
                    if ((wDx > 0) && (wDy > 0)) {
                        tileHeights[wDx - 1][wDy - 1] = (cornerHeights[wDx - 1][wDy - 1] + cornerHeights[wDx - 1][wDy] + cornerHeights[wDx][wDy - 1] + cornerHeights[wDx][wDy]) / 4;
                        slopes[wDx - 1][wDy - 1] = (max(cornerHeights[wDx - 1][wDy - 1], cornerHeights[wDx - 1][wDy], cornerHeights[wDx][wDy - 1], cornerHeights[wDx][wDy]) - min(cornerHeights[wDx - 1][wDy - 1], cornerHeights[wDx - 1][wDy], cornerHeights[wDx][wDy - 1], cornerHeights[wDx][wDy])) / 4;
                    }
                }
            }
        } else {
            wTileSize = TILE_SIZE;
            wOffsetX = (tileX << TILE_SIZE_BITS) + offsetX;
            wOffsetY = (tileY << TILE_SIZE_BITS) + offsetY;
            for (int dx = 0; dx <= TILE_SIZE; dx++) {
                for (int dy = 0; dy <= TILE_SIZE; dy++) {
                    final int x = (tileX << TILE_SIZE_BITS) + dx, y = (tileY << TILE_SIZE_BITS) + dy;
                    final float height = dim.getHeightAt(x, y);
                    final int intHeight = (int) (height + 0.5f);
                    cornerHeights[dx][dy] = scaledVertically ? (height * 4) : height;
                    if ((dx < TILE_SIZE) && (dy < TILE_SIZE)) {
                        topLayerDepths[dx][dy] = scaledVertically ? (dim.getTopLayerDepth(x, y, intHeight) * 4) : dim.getTopLayerDepth(x, y, intHeight);
                        final Terrain terrain = dim.getTerrainAt(x, y);
                        terrains[dx][dy] = terrain;
                        blocks[dx][dy] = terrain.getMaterial(seed, x, y, height, intHeight).blockType;
                    }
                    if ((dx > 0) && (dy > 0)) {
                        tileHeights[dx - 1][dy - 1] = (cornerHeights[dx - 1][dy - 1] + cornerHeights[dx - 1][dy] + cornerHeights[dx][dy - 1] + cornerHeights[dx][dy]) / 4;
                        slopes[dx - 1][dy - 1] = (max(cornerHeights[dx - 1][dy - 1], cornerHeights[dx - 1][dy], cornerHeights[dx][dy - 1], cornerHeights[dx][dy]) - min(cornerHeights[dx - 1][dy - 1], cornerHeights[dx - 1][dy], cornerHeights[dx][dy - 1], cornerHeights[dx][dy])) / 4;
                    }
                }
            }
        }

        // Generate terrain
        Random random = new Random(dim.getSeed() + tileX * 65537 + tileY + 4099);
        for (int dx = 0; dx < wTileSize; dx++) {
            for (int dy = 0; dy < wTileSize; dy++) {
                final int wX = wOffsetX + dx, wY = wOffsetY + dy;
                final float cornerHeight = cornerHeights[dx][dy];
                final short wurmHeight = (short) ((cornerHeight - scaledWaterLevel) * 10 + 0.5f);
                final float tileHeight = tileHeights[dx][dy];
                mapData.setRockHeight(wX, wY, (short) ((cornerHeight - topLayerDepths[dx][dy] - scaledWaterLevel) * 10 + 0.5f));
                Terrain terrain = terrains[dx][dy];
                switch (terrain) {
                    case GRASS:
                        if (tileHeight >= scaledWaterLevel) {
                            placeGrass(mapData, wX, wY, cornerHeight, scaledWaterLevel, dim.getSeed());
                            if (random.nextInt(64) == 0) {
                                mapData.setSurfaceTile(wX, wY, TILE_MOSS);
                            }
                        } else if ((scaledWaterLevel - tileHeight) > tileKelpMinimumDepth){
                            if (kelpNoise.getPerlinNoise(wX / TINY_BLOBS, wY / TINY_BLOBS, tileHeight / TINY_BLOBS) > KELP_CHANCE) {
                                mapData.setSurfaceTile(wX, wY, TILE_KELP, wurmHeight);
                            } else {
                                mapData.setSurfaceTile(wX, wY, TILE_DIRT, wurmHeight);
                            }
                        } else {
                            mapData.setSurfaceTile(wX, wY, TILE_DIRT, wurmHeight);
                        }
                        break;
                    case BEACHES:
                        Tiles.Tile tileType;
                        int blockId = blocks[dx][dy];
                        if ((blockId != -1) && (blockId < BLOCK_MAPPING.length) && (BLOCK_MAPPING[blockId] != null)) {
                            tileType = BLOCK_MAPPING[blockId];
                        } else {
                            tileType = DEFAULT_TILE_TYPE;
                        }
                        if (tileHeight < scaledWaterLevel) {
                            if (((scaledWaterLevel - tileHeight) < 1)
                                    && ((tileType == TILE_GRASS) || (tileType == TILE_SAND))
                                    && (reedNoise.getPerlinNoise(wX / SMALL_BLOBS, wY / SMALL_BLOBS, tileHeight / SMALL_BLOBS) > REED_CHANCE)) {
                                mapData.setSurfaceTile(wX, wY, TILE_REED, wurmHeight);
                            } else if (tileType == TILE_GRASS) {
                                if ((kelpNoise.getPerlinNoise(wX / TINY_BLOBS, wY / TINY_BLOBS, tileHeight / TINY_BLOBS) > KELP_CHANCE) && ((scaledWaterLevel - tileHeight) > tileKelpMinimumDepth)) {
                                    mapData.setSurfaceTile(wX, wY, TILE_KELP, wurmHeight);
                                } else {
                                    mapData.setSurfaceTile(wX, wY, TILE_DIRT, wurmHeight);
                                }
                            } else {
                                mapData.setSurfaceTile(wX, wY, tileType, wurmHeight);
                            }
                        } else {
                            mapData.setSurfaceTile(wX, wY, tileType, wurmHeight);
                        }
                        break;
                    case CUSTOM_1:
                    case CUSTOM_2:
                    case CUSTOM_3:
                    case CUSTOM_4:
                    case CUSTOM_5:
                    case CUSTOM_6:
                    case CUSTOM_7:
                    case CUSTOM_8:
                    case CUSTOM_9:
                    case CUSTOM_10:
                    case CUSTOM_11:
                    case CUSTOM_12:
                    case CUSTOM_13:
                    case CUSTOM_14:
                    case CUSTOM_15:
                    case CUSTOM_16:
                    case CUSTOM_17:
                    case CUSTOM_18:
                    case CUSTOM_19:
                    case CUSTOM_20:
                    case CUSTOM_21:
                    case CUSTOM_22:
                    case CUSTOM_23:
                    case CUSTOM_24:
                    case CUSTOM_25:
                    case CUSTOM_26:
                    case CUSTOM_27:
                    case CUSTOM_28:
                    case CUSTOM_29:
                    case CUSTOM_30:
                    case CUSTOM_31:
                    case CUSTOM_32:
                    case CUSTOM_33:
                    case CUSTOM_34:
                    case CUSTOM_35:
                    case CUSTOM_36:
                    case CUSTOM_37:
                    case CUSTOM_38:
                    case CUSTOM_39:
                    case CUSTOM_40:
                    case CUSTOM_41:
                    case CUSTOM_42:
                    case CUSTOM_43:
                    case CUSTOM_44:
                    case CUSTOM_45:
                    case CUSTOM_46:
                    case CUSTOM_47:
                    case CUSTOM_48:
                    case CUSTOM_49:
                    case CUSTOM_50:
                    case CUSTOM_51:
                    case CUSTOM_52:
                    case CUSTOM_53:
                    case CUSTOM_54:
                    case CUSTOM_55:
                    case CUSTOM_56:
                    case CUSTOM_57:
                    case CUSTOM_58:
                    case CUSTOM_59:
                    case CUSTOM_60:
                    case CUSTOM_61:
                    case CUSTOM_62:
                    case CUSTOM_63:
                    case CUSTOM_64:
                    case CUSTOM_65:
                    case CUSTOM_66:
                    case CUSTOM_67:
                    case CUSTOM_68:
                    case CUSTOM_69:
                    case CUSTOM_70:
                    case CUSTOM_71:
                    case CUSTOM_72:
                    case CUSTOM_73:
                    case CUSTOM_74:
                    case CUSTOM_75:
                    case CUSTOM_76:
                    case CUSTOM_77:
                    case CUSTOM_78:
                    case CUSTOM_79:
                    case CUSTOM_80:
                    case CUSTOM_81:
                    case CUSTOM_82:
                    case CUSTOM_83:
                    case CUSTOM_84:
                    case CUSTOM_85:
                    case CUSTOM_86:
                    case CUSTOM_87:
                    case CUSTOM_88:
                    case CUSTOM_89:
                    case CUSTOM_90:
                    case CUSTOM_91:
                    case CUSTOM_92:
                    case CUSTOM_93:
                    case CUSTOM_94:
                    case CUSTOM_95:
                    case CUSTOM_96:
                        String name = terrain.getName();
                        if (name.equalsIgnoreCase("W:Steppe")) {
                            mapData.setSurfaceTile(wX, wY, TILE_STEPPE, wurmHeight);
                            break;
                        } else if (name.equalsIgnoreCase("W:Tundra")) {
                            mapData.setSurfaceTile(wX, wY, TILE_TUNDRA, wurmHeight);
                            break;
                        }
                        // Fall through
                    default:
                        tileType = TERRAIN_MAPPING[terrain.ordinal()];
                        if (tileType == null) {
                            blockId = blocks[dx][dy];
                            if ((blockId != -1) && (blockId < BLOCK_MAPPING.length) && (BLOCK_MAPPING[blockId] != null)) {
                                tileType = BLOCK_MAPPING[blockId];
                            } else {
                                tileType = DEFAULT_TILE_TYPE;
                            }
                        }
                        if ((tileHeight < scaledWaterLevel) && (tileType == TILE_GRASS)) {
                            if ((kelpNoise.getPerlinNoise(wX / TINY_BLOBS, wY / TINY_BLOBS, tileHeight / TINY_BLOBS) > KELP_CHANCE) && ((scaledWaterLevel - tileHeight) > tileKelpMinimumDepth)) {
                                mapData.setSurfaceTile(wX, wY, TILE_KELP, wurmHeight);
                            } else {
                                mapData.setSurfaceTile(wX, wY, TILE_DIRT, wurmHeight);
                            }
                        } else {
                            if (tileType == TILE_ROCK) {
                                if (slopes[dx][dy] > 1.0) {
                                    mapData.setSurfaceTile(wX, wY, TILE_CLIFF, wurmHeight);
                                } else {
                                    mapData.setSurfaceTile(wX, wY, tileType, wurmHeight);
                                }
                            } else {
                                mapData.setSurfaceTile(wX, wY, tileType, wurmHeight);
                            }
                        }
                        break;
                }
            }
        }

        // Process layers
        final Tile tile = dim.getTile(tileX, tileY);
        final int scale = scaledHorizontally ? 4 : 1;
        for (Layer layer: tile.getLayers()) {
            if (! (layer.equals(Frost.INSTANCE) || (layer instanceof TreeLayer))) {
                continue;
            }
            for (int dx = 0; dx < TILE_SIZE; dx += scale) {
                for (int dy = 0; dy < TILE_SIZE; dy += scale) {
                    final float tileHeight = scaledHorizontally ? tileHeights[dx >> 2][dy >> 2] : tileHeights[dx][dy];
                    final boolean flooded = tileHeight < waterLevel;
                    final int wX = wOffsetX + (scaledHorizontally ? (dx >> 2) : dx), wY = wOffsetY + (scaledHorizontally ? (dy >> 2) : dy);
                    if ((! flooded)
                            && layer.equals(Frost.INSTANCE)
                            && (scaledHorizontally
                                ? getAverageBitLayerValue(tile, dx, dy, Frost.INSTANCE)
                                : tile.getBitLayerValue(Frost.INSTANCE, dx, dy))) {
                        mapData.setSurfaceTile(wX, wY, TILE_SNOW);
                    } else if (layer instanceof TreeLayer) {
                        int level = scaledHorizontally ? getAverageNibbleLayervalue(tile, dx, dy, layer) : tile.getLayerValue(layer, dx, dy);
                        if (level > 0) {
                            Tiles.Tile existingTile = mapData.getSurfaceTile(wX, wY);
                            if ((existingTile != TILE_GRASS) && (existingTile != TILE_DIRT) && (existingTile != TILE_MARSH) && (existingTile != TILE_MOSS)) {
                                continue;
                            }
                            if (layer instanceof SwampLand) {
                                if ((Math.abs(tileHeight - waterLevel) < 1) && ((existingTile == TILE_GRASS) || (existingTile == TILE_DIRT))) {
                                    mapData.setSurfaceTile(wX, wY, TILE_MARSH);
                                } else if ((random.nextInt(16) <= level) && (mapData.getSurfaceTile(wX, wY) == TILE_GRASS)) {
                                    mapData.setSurfaceTile(wX, wY, TILE_MOSS);
                                }
                            } else if ((! flooded) && (random.nextInt(32) <= level) && (mapData.getSurfaceTile(wX, wY) == TILE_GRASS)) {
                                mapData.setSurfaceTile(wX, wY, TILE_MOSS);
                            }
                            if ((! flooded) && (random.nextInt(16) <= level)) {
                                if (random.nextInt(5) == 0) {
                                    mapData.setBush(wX, wY, BushData.BushType.fromInt(random.nextInt(6)), FoliageAge.fromByte((byte) random.nextInt(16)), GrassData.GrowthTreeStage.fromInt(random.nextInt(4)));
                                } else {
                                    TreeData.TreeType[] treeTypes = TREE_TYPE_MAPPING.get(layer);
                                    mapData.setTree(wX, wY, treeTypes[random.nextInt(treeTypes.length)], FoliageAge.fromByte((byte) random.nextInt(16)), GrassData.GrowthTreeStage.fromInt(random.nextInt(4)));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static int getAverageNibbleLayervalue(final Tile tile, final int x, final int y, final Layer layer) {
        int total = 0;
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 4; dy++) {
                total += tile.getLayerValue(layer, x + dx, y + dy);
            }
        }
        return total / 16;
    }

    private static boolean getAverageBitLayerValue(final Tile tile, final int x, final int y, final Frost layer) {
        int layerCount = 0;
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 4; dy++) {
                if (tile.getBitLayerValue(layer, x + dx, y + dy)) {
                    layerCount++;
                }
            }
        }
        return layerCount >= 8;
    }

    private static void placeGrass(final MapData mapData, final int x, final int y, final float height, final int waterLevel, final long seed) {
        final int wpX = x * 4, wpY = y * 4;
        mapData.setSurfaceTile(x, y, TILE_GRASS, (short) ((height - waterLevel) * 10 + 0.5f));
        final Random rnd = new Random(seed + (wpX * 65537) + (wpY * 4099));
        final int rndNr = rnd.nextInt(FLOWER_INCIDENCE);
        if (rndNr == 0) {
            // Keep the "1 / SMALLBLOBS" and the two noise generators for constistency with existing maps
            if ((dandelionNoise.getPerlinNoise(wpX / SMALL_BLOBS, wpY / SMALL_BLOBS, 1 / SMALL_BLOBS) > FLOWER_CHANCE)
                    || (roseNoise.getPerlinNoise(wpX / SMALL_BLOBS, wpY / SMALL_BLOBS, 1 / SMALL_BLOBS) > FLOWER_CHANCE)) {
                int flowerType = flowerTypeField.getValue(wpX, wpY);
                mapData.setGrass(x, y, GrassData.GrowthStage.SHORT, GrassData.FlowerType.fromInt(flowerType));
            }
        } else {
            // Keep the "1 / SMALLBLOBS" for constistency with existing maps
            final float grassValue = grassNoise.getPerlinNoise(wpX / SMALL_BLOBS, wpY / SMALL_BLOBS, 1 / SMALL_BLOBS) + (rnd.nextFloat() * 0.3f - 0.15f);
            if (grassValue > GRASS_CHANCE) {
                if (tallGrassNoise.getPerlinNoise(wpX / SMALL_BLOBS, wpY / SMALL_BLOBS, 1 / SMALL_BLOBS) > 0) {
                    // Double tallness
                    if (grassValue > DOUBLE_TALL_GRASS_CHANCE) {
                        if (rnd.nextInt(4) == 0) {
                            mapData.setGrass(x, y, GrassData.GrowthStage.WILD, GrassData.FlowerType.NONE);
                        } else {
                            mapData.setGrass(x, y, GrassData.GrowthStage.TALL, GrassData.FlowerType.NONE);
                        }
                    } else  {
                        mapData.setGrass(x, y, GrassData.GrowthStage.MEDIUM, GrassData.FlowerType.NONE);
                    }
                } else {
                    mapData.setGrass(x, y, GrassData.GrowthStage.MEDIUM, GrassData.FlowerType.NONE);
                }
            }
        }
    }

    private static int getPrevalentBlockID(final Dimension dim, final int x, final int y, final BitSet unsupportedBlocksSet) {
        Arrays.fill(BLOCK_ID_BUCKETS, 0);
        int highestBlockId = -1, highestBlockCount = 0;
        final long seed = dim.getSeed();
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 4; dy++) {
                final int worldX = x + dx, worldY = y + dy;
                final float height = dim.getHeightAt(worldX, worldY);
                final int blockId = dim.getTerrainAt(worldX, worldY).getMaterial(seed, worldX, worldY, height, (int) (height + 0.5f)).blockType;
                if ((blockId >= BLOCK_MAPPING.length) || (BLOCK_MAPPING[blockId] == null)) {
                    unsupportedBlocksSet.set(blockId);
                    continue;
                }
                BLOCK_ID_BUCKETS[blockId]++;
                if (BLOCK_ID_BUCKETS[blockId] > highestBlockCount) {
                    highestBlockCount = BLOCK_ID_BUCKETS[blockId];
                    highestBlockId = blockId;
                }
            }
        }
        return highestBlockId;
    }

    private static Terrain getPrevalentTerrain(final Dimension dim, final int x, final int y) {
        Arrays.fill(TERRAIN_BUCKETS, 0);
        int highestTerrainIndex = -1, highestTerrainCount = 0;
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 4; dy++) {
                final int terrainIndex = dim.getTerrainAt(x + dx, y + dy).ordinal();
                TERRAIN_BUCKETS[terrainIndex]++;
                if (TERRAIN_BUCKETS[terrainIndex] > highestTerrainCount) {
                    highestTerrainCount = TERRAIN_BUCKETS[terrainIndex];
                    highestTerrainIndex = terrainIndex;
                }
            }
        }
        return Terrain.values()[highestTerrainIndex];
    }

    private static float getAverageHeight(final Dimension dim, final int x, final int y) {
        float total = 0f;
        for (int dx = -2; dx < 2; dx++) {
            for (int dy = -2; dy < 2; dy++) {
                total += dim.getHeightAt(x + dx, y + dy);
            }
        }
        return total / 16;
    }

    private static float getAverageTopLayerDepth(final Dimension dim, final int x, final int y) {
        float total = 0f;
        for (int dx = -2; dx < 2; dx++) {
            for (int dy = -2; dy < 2; dy++) {
                total += dim.getTopLayerDepth(x + dx, y + dy, dim.getIntHeightAt(x + dx, y + dy));
            }
        }
        return total / 16;
    }

    private static float max(float arg1, float arg2, float arg3, float arg4) {
        return Math.max(Math.max(arg1, arg2), Math.max(arg3, arg4));
    }

    private static float min(float arg1, float arg2, float arg3, float arg4) {
        return Math.min(Math.min(arg1, arg2), Math.min(arg3, arg4));
    }

    private World2 world;

    // Buffers
    private static final float[][]
            /**
             * Heights of the northwest corners of the tiles. Has an extra row
             * and column to allow calculations that need all four corners.
             */
            cornerHeights = new float[TILE_SIZE + 1][TILE_SIZE + 1],

            /**
             * Top layer depths of the northwest corners of the tiles.
             */
            topLayerDepths = new float[TILE_SIZE][TILE_SIZE],

            /**
             * Maximum slopes of the tiles. 1.0 means 45 degrees.
             */
            slopes = new float[TILE_SIZE][TILE_SIZE],

            /**
             * Average heights of the tiles.
             */
            tileHeights = new float[TILE_SIZE][TILE_SIZE];

    /**
     * Most prevalent terrain types of the tiles.
     */
    private static final Terrain[][] terrains = new Terrain[TILE_SIZE][TILE_SIZE];

    /**
     * Most prevalent block IDs of the tiles.
     */
    private static final int[][] blocks = new int[TILE_SIZE][TILE_SIZE];

    // Constants

    static final AttributeKey<Integer> SCALING_MODE_KEY = new AttributeKey<>("org.pepsoft.wurm.scalingMode", 0);

    private static final Tiles.Tile DEFAULT_TILE_TYPE = TILE_DIRT;
    private static final int[] TERRAIN_BUCKETS = new int[Terrain.values().length], BLOCK_ID_BUCKETS = new int[256];
    private static final double LOG_2 = Math.log(2);
    private static final Logger logger = LoggerFactory.getLogger(WurmUnlimitedExporter.class);
    private static final Tiles.Tile[] TERRAIN_MAPPING = {
        null, // GRASS
        TILE_DIRT, // DIRT
        TILE_SAND, // SAND
        TILE_ROCK, // SANDSTONE
        TILE_ROCK, // STONE
        TILE_ROCK, // ROCK
        null, // WATER
        TILE_LAVA, // LAVA
        TILE_SNOW, // SNOW
        TILE_SNOW, // DEEP_SNOW
        TILE_GRAVEL, // GRAVEL
        TILE_CLAY, // CLAY
        TILE_COBBLESTONE, // COBBLESTONE
        TILE_COBBLESTONE_ROUGH, // MOSSY_COBBLESTONE
        TILE_ROCK, // NETHERRACK
        TILE_TAR, // SOUL_SAND
        TILE_ROCK, // OBSIDIAN
        TILE_ROCK, // BEDROCK
        TILE_SAND, // DESERT
        TILE_ROCK, // NETHERLIKE
        TILE_ROCK, // RESOURCES
        null, // BEACHES
        null, // CUSTOM_1
        null, // CUSTOM_2
        null, // CUSTOM_3
        null, // CUSTOM_4
        null, // CUSTOM_5
        TILE_MYCELIUM, // MYCELIUM
        TILE_ROCK, // END_STONE
        TILE_GRASS, // BARE_GRASS
        null, // CUSTOM_6
        null, // CUSTOM_7
        null, // CUSTOM_8
        null, // CUSTOM_9
        null, // CUSTOM_10
        null, // CUSTOM_11
        null, // CUSTOM_12
        null, // CUSTOM_13
        null, // CUSTOM_14
        null, // CUSTOM_15
        null, // CUSTOM_16
        null, // CUSTOM_17
        null, // CUSTOM_18
        null, // CUSTOM_19
        null, // CUSTOM_20
        null, // CUSTOM_21
        null, // CUSTOM_22
        null, // CUSTOM_23
        null, // CUSTOM_24
        TILE_DIRT_PACKED, // PERMADIRT
        TILE_PEAT, // PODZOL
        TILE_SAND, // RED_SAND
        TILE_CLAY, // HARDENED_CLAY
        TILE_CLAY, // WHITE_STAINED_CLAY
        TILE_CLAY, // ORANGE_STAINED_CLAY
        TILE_CLAY, // MAGENTA_STAINED_CLAY
        TILE_CLAY, // LIGHT_BLUE_STAINED_CLAY
        TILE_CLAY, // YELLOW_STAINED_CLAY
        TILE_CLAY, // LIME_STAINED_CLAY
        TILE_CLAY, // PINK_STAINED_CLAY
        TILE_CLAY, // GREY_STAINED_CLAY
        TILE_CLAY, // LIGHT_GREY_STAINED_CLAY
        TILE_CLAY, // CYAN_STAINED_CLAY
        TILE_CLAY, // PURPLE_STAINED_CLAY
        TILE_CLAY, // BLUE_STAINED_CLAY
        TILE_CLAY, // BROWN_STAINED_CLAY
        TILE_CLAY, // GREEN_STAINED_CLAY
        TILE_CLAY, // RED_STAINED_CLAY
        TILE_CLAY, // BLACK_STAINED_CLAY
        TILE_ROCK, // MESA
        TILE_SAND, // RED_DESERT
        TILE_ROCK, // RED_SANDSTONE
        TILE_ROCK, // GRANITE
        TILE_ROCK, // DIORITE
        TILE_ROCK, // ANDESITE
        TILE_ROCK, // STONE_MIX
        null, // CUSTOM_25,
        null, // CUSTOM_26,
        null, // CUSTOM_27,
        null, // CUSTOM_28,
        null, // CUSTOM_29,
        null, // CUSTOM_30,
        null, // CUSTOM_31,
        null, // CUSTOM_32,
        null, // CUSTOM_33,
        null, // CUSTOM_34,
        null, // CUSTOM_35,
        null, // CUSTOM_36,
        null, // CUSTOM_37,
        null, // CUSTOM_38,
        null, // CUSTOM_39,
        null, // CUSTOM_40,
        null, // CUSTOM_41,
        null, // CUSTOM_42,
        null, // CUSTOM_43,
        null, // CUSTOM_44,
        null, // CUSTOM_45,
        null, // CUSTOM_46,
        null, // CUSTOM_47,
        null, // CUSTOM_48,
        TILE_GRASS // GRASS_PATH
    };
    private static final Tiles.Tile[] BLOCK_MAPPING = {
        null, // Air
        TILE_ROCK, // Stone
        TILE_GRASS, // Grass
        TILE_DIRT, // Dirt
        TILE_COBBLESTONE, // Cobblestone
        TILE_PLANKS, // Wooden Plank
        null, // Sapling
        TILE_ROCK, // Bedrock
        null, // Water
        null, // Stationary Water
        TILE_LAVA, // Lava
        TILE_LAVA, // Stationary Lava
        TILE_SAND, // Sand
        TILE_GRAVEL, // Gravel
        TILE_ROCK, // Gold Ore
        TILE_ROCK, // Iron Ore
        TILE_ROCK, // Coal Ore
        null, // Wood
        null, // Leaves
        null, // Sponge
        null, // Glass
        TILE_ROCK, // Lapis Lazuli Ore
        null, // Lapis Lazuli Block
        null, // Dispenser
        TILE_ROCK, // Sandstone
        null, // Note Block
        null, // Bed
        null, // Powered Rail
        null, // Detector Rail
        null, // Sticky Piston
        null, // Cobweb
        null, // Tall Grass
        null, // Dead Bush
        null, // Piston
        null, // Piston Extension
        null, // Wool
        null, // 36
        null, // Dandelion
        null, // Flower
        null, // Brown Mushroom
        null, // Red Mushroom
        null, // Gold Block
        null, // Iron Block
        TILE_STONE_SLABS, // Double Slabs
        TILE_STONE_SLABS, // Slab
        TILE_STONE_SLABS, // Brick Block
        null, // TNT
        null, // Bookshelf
        TILE_COBBLESTONE_ROUGH, // Mossy Cobblestone
        TILE_ROCK, // Obsidian
        null, // Torch
        null, // Fire
        null, // Monster Spawner
        null, // Wooden Stairs
        null, // Chest
        null, // Redstone Wire
        TILE_ROCK, // Diamond Ore
        null, // Diamond Block
        null, // Crafting Table
        null, // Wheat
        TILE_DIRT, // Tilled Dirt
        null, // Furnace
        null, // Burning Furnace
        null, // Sign Post
        null, // Wooden Door
        null, // Ladder
        null, // Rails
        null, // Cobblestone Stairs
        null, // Wall Sign
        null, // Lever
        null, // Stone Pressure Plate
        null, // Iron Door
        null, // Wooden Pressure Plate
        TILE_ROCK, // Redstone Ore
        TILE_ROCK, // Glowing Redstone Ore
        null, // Redstone Torch (off)
        null, // Redstone Torch (on)
        null, // Stone Button
        TILE_SNOW, // Snow
        TILE_SNOW, // Ice
        TILE_SNOW, // Snow Block
        null, // Cactus
        TILE_CLAY, // Clay Block
        null, // Sugar Cane
        null, // Jukebox
        null, // Fence
        null, // Pumpkin
        TILE_ROCK, // Netherrack
        TILE_SAND, // Soul Sand
        null, // Glowstone Block
        null, // Portal
        null, // Jack-O-Lantern
        null, // Cake
        null, // Redstone Repeater (off)
        null, // Redstone Repeater (on)
        null, // Stained Glass
        null, // Trapdoor
        TILE_STONE_SLABS, // Hidden Silverfish
        TILE_STONE_SLABS, // Stone Bricks
        null, // Huge Brown Mushroom
        null, // Huge Red Mushroom
        null, // Iron Bars
        null, // Glass Pane
        null, // Melon
        null, // Pumpkin Stem
        null, // Melon Stem
        null, // Vines
        null, // Fence Gate
        null, // Brick Stairs
        null, // Stone Brick Stairs
        TILE_MYCELIUM, // Mycelium
        null, // Lily Pad
        TILE_STONE_SLABS, // Nether Brick
        null, // Nether Brick Fence
        null, // Nether Brick Stairs
        null, // Nether Wart
        null, // Enchantment Table
        null, // Brewing Stand
        null, // Cauldron
        null, // End Portal
        null, // End Portal Frame
        null, // End Stone
        null, // Dragon Egg
        null, // Redstone Lamp (off)
        null, // Redstone Lamp (on)
        TILE_PLANKS, // Wooden Double Slab
        TILE_PLANKS, // Wooden Slab
        null, // Cocoa Plant
        null, // Sandstone Stairs
        TILE_ROCK, // Emerald Ore
        null, // Ender Chest
        null, // Tripwire Hook
        null, // Tripwire
        null, // Emerald Block
        null, // Pine Wood Stairs
        null, // Birch Wood Stairs
        null, // Jungle Wood Stairs
        null, // Command Block
        null, // Beacon
        null, // Cobblestone Wall
        null, // Flower Pot
        null, // Carrots
        null, // Potatoes
        null, // Wooden Button
        null, // Head
        null, // Anvil
        null, // Trapped Chest
        null, // Weighted Pressure Plate (light)
        null, // Weighted Pressure Plate (heavy)
        null, // Redstone Comparator (unpowered)
        null, // Redstone Comparator (powered)
        null, // Daylight Sensor
        null, // Redstone Block
        TILE_ROCK, // Nether Quartz Ore
        null, // Hopper
        null, // Quartz Block
        null, // Quartz Stairs
        null, // Activator Rail
        null, // Dropper
        TILE_CLAY, // Stained Clay
        null, // Stained Glass Pane
        null, // Leaves 2
        null, // Wood 2
        null, // Acacia Wood Stairs
        null, // Dark Oak Wood Stairs
        null, // Slime Block
        null, // Barrier
        null, // Iron Trapdoor
        TILE_ROCK, // Prismarine
        null, // Sea Lantern
        null, // Hay Bale
        null, // Carpet
        TILE_CLAY, // Hardened Clay
        null, // Coal Block
        TILE_SNOW, // Packed Ice
        null, // Large Flower
        null, // Standing Banner
        null, // Wall Banner
        null, // Inverted Daylight Sensor
        TILE_ROCK, // Red Sandstone
        null, // Red Sandstone Stairs
        TILE_STONE_SLABS, // Double Red Sandstone Slab
        TILE_STONE_SLABS, // Red Sandstone Slab
        null, // Pine Wood Fence Gate
        null, // Birch Wood Fence Gate
        null, // Jungle Wood Fence Gate
        null, // ark Oak Wood Fence Gate
        null, // Acacia Wood Fence Gate
        null, // Pine Wood Fence
        null, // Birch Wood Fence
        null, // Jungle Wood Fence
        null, // Dark Oak Wood Fence
        null, // Acacia Wood Fence
        null, // Pine Wood Door
        null, // Birch Wood Door
        null, // Jungle Wood Door
        null, // Acacia Wood Door
        null, // Dark Oak Wood Door
        null, // End Rod
        null, // Chorus Plant
        null, // Chorus Flower
        TILE_STONE_SLABS, // Purpur Block
        null, // Purpur Pillar
        null, // Purpur Stairs
        TILE_STONE_SLABS, // Double Purpur Slab
        TILE_STONE_SLABS, // Purpur Slab
        TILE_STONE_SLABS, // End Stone Bricks
        null, // Beetroots
        TILE_GRASS, // Grass Path
        null, // End Gateway
        null, // Repeating Command Block
        null, // Chain Command Block
        TILE_SNOW // Frosted Ice
    };
    private static final long DANDELION_SEED_OFFSET = 145351781L;
    private static final long ROSE_SEED_OFFSET = 28286488L;
    private static final long GRASS_SEED_OFFSET = 169191195L;
    private static final long FLOWER_TYPE_FIELD_OFFSET = 65226710L;
    private static final long DOUBLE_TALL_GRASS_SEED_OFFSET = 31695680L;
    private static final long KELP_SEED_OFFSET = 18815862L;
    private static final long REED_SEED_OFFSET = 79508482L;
    private static final int FLOWER_INCIDENCE = 5;
    private static final float FLOWER_CHANCE = PerlinNoise.getLevelForPromillage(40);
    private static final float GRASS_CHANCE = PerlinNoise.getLevelForPromillage(400);
    private static final float DOUBLE_TALL_GRASS_CHANCE = PerlinNoise.getLevelForPromillage(200);
    private static final float REED_CHANCE = PerlinNoise.getLevelForPromillage(400);
    private static final float KELP_CHANCE = PerlinNoise.getLevelForPromillage(100);
    private static final PerlinNoise dandelionNoise = new PerlinNoise(0);
    private static final PerlinNoise roseNoise = new PerlinNoise(0);
    private static final PerlinNoise grassNoise = new PerlinNoise(0);
    private static final RandomField flowerTypeField = new RandomField(4, SMALL_BLOBS, 0);
    private static final PerlinNoise tallGrassNoise = new PerlinNoise(0);
    private static final PerlinNoise reedNoise = new PerlinNoise(0);
    private static final PerlinNoise kelpNoise = new PerlinNoise(0);
    private static final Map<Layer, TreeData.TreeType[]> TREE_TYPE_MAPPING = new HashMap<>();
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss");

    static {
        TREE_TYPE_MAPPING.put(DeciduousForest.INSTANCE, new TreeData.TreeType[] {BIRCH, OAK, MAPLE, CHESTNUT, LINDEN});
        TREE_TYPE_MAPPING.put(PineForest.INSTANCE, new TreeData.TreeType[] {PINE, FIR, CEDAR});
        TREE_TYPE_MAPPING.put(Jungle.INSTANCE, new TreeData.TreeType[] {APPLE, LEMON, OLIVE, CHERRY, WALNUT});
        TREE_TYPE_MAPPING.put(SwampLand.INSTANCE, new TreeData.TreeType[] {WILLOW});
    }

    enum ScalingMode {
        /**
         * Scaled 4:1 horizontally, unscaled vertically (meant for converting
         * maps targeted to Minecraft)
         */
        MINECRAFT,

        /**
         * Unscaled horizontally, scaled 1:4 vertically (meant for creating
         * Wurm Unlimited maps in WorldPainter with correct looking proportions
         * in the editor)
         */
        WURM_SCALED,

        /**
         * Unscaled horizontally and vertically (meant for creating Wurm
         * Unlimited maps in WorldPainter with correct height information)
         */
        WURM_UNSCALED}
}