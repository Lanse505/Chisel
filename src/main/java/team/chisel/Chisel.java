package team.chisel;

import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.ImmutableMap;
import com.tterrag.registrate.Registrate;
import com.tterrag.registrate.providers.ProviderType;
import com.tterrag.registrate.util.NonNullLazyValue;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.GenerationStage;
import net.minecraft.world.gen.GenerationStage.Decoration;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.OreFeatureConfig;
import net.minecraft.world.gen.placement.CountRangeConfig;
import net.minecraft.world.gen.placement.Placement;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent.MissingMappings;
import net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.simple.SimpleChannel;
import net.minecraftforge.registries.ForgeRegistries;
import team.chisel.api.ChiselAPIProps;
import team.chisel.api.carving.CarvingUtils;
import team.chisel.client.data.VariantTemplates;
import team.chisel.client.gui.PacketChiselButton;
import team.chisel.client.gui.PacketHitechSettings;
import team.chisel.client.util.ChiselLangKeys;
import team.chisel.common.Reference;
import team.chisel.common.block.MessageAutochiselFX;
import team.chisel.common.block.MessageUpdateAutochiselSource;
import team.chisel.common.carving.CarvingVariationRegistry;
import team.chisel.common.carving.ChiselModeRegistry;
import team.chisel.common.init.ChiselItems;
import team.chisel.common.init.ChiselSounds;
import team.chisel.common.init.ChiselTabs;
import team.chisel.common.init.ChiselTileEntities;
import team.chisel.common.integration.imc.IMCHandler;
import team.chisel.common.item.ChiselController;
import team.chisel.common.item.ChiselMode;
import team.chisel.common.item.PacketChiselMode;
import team.chisel.common.util.PerChunkData.MessageChunkData;

@Mod(Reference.MOD_ID)
public class Chisel implements Reference {

    public static final Logger logger = LogManager.getLogger(MOD_NAME);

//    @SidedProxy(clientSide = CLIENT_PROXY, serverSide = COMMON_PROXY, modId = MOD_ID)
//    public static CommonProxy proxy;

    public static final boolean debug = false;// StringUtils.isEmpty(System.getProperty("chisel.debug"));

    public static final SimpleChannel network = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );
    
    static {
        network.registerMessage(0, PacketChiselButton.class, PacketChiselButton::encode, PacketChiselButton::decode, PacketChiselButton::handle);
        network.registerMessage(1, PacketHitechSettings.class, PacketHitechSettings::encode, PacketHitechSettings::decode, PacketHitechSettings::handle);
        network.registerMessage(2, MessageChunkData.class, MessageChunkData::encode, MessageChunkData::decode, MessageChunkData::handle);
        network.registerMessage(3, PacketChiselMode.class, PacketChiselMode::encode, PacketChiselMode::decode, PacketChiselMode::handle);
        network.registerMessage(4, MessageUpdateAutochiselSource.class, MessageUpdateAutochiselSource::encode, MessageUpdateAutochiselSource::decode, MessageUpdateAutochiselSource::handle);
        network.registerMessage(5, MessageAutochiselFX.class, MessageAutochiselFX::encode, MessageAutochiselFX::decode, MessageAutochiselFX::handle);
    }

    private static Map<String, Block> remaps = ImmutableMap.of();

    private static final NonNullLazyValue<Registrate> REGISTRATE = new NonNullLazyValue<Registrate>(() -> {
        Registrate ret = Registrate.create(Reference.MOD_ID).itemGroup(() -> ChiselTabs.tab);
        ret.addDataGenerator(ProviderType.LANG, prov -> prov.add(ChiselTabs.tab, "Chisel"));
        return ret;
    });
    
    public Chisel() {
        CarvingUtils.chisel = new CarvingVariationRegistry();
        CarvingUtils.modes = ChiselModeRegistry.INSTANCE;
        ChiselMode.values(); // static init our modes
        ChiselAPIProps.MOD_ID = MOD_ID;
        
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::setup);
        modBus.addListener(this::imcEnqueue);
        modBus.addListener(this::imcProcess);
        modBus.addGenericListener(Block.class, this::onMissingBlock);
        modBus.addGenericListener(Item.class, this::onMissingItem);
        
        ChiselSounds.init();
        ChiselItems.init();
        ChiselTileEntities.init();
        
        Features.init();
        ChiselLangKeys.init(registrate());
    }
    
    public static Registrate registrate() {
        return REGISTRATE.getValue();
    }

    private void setup(FMLCommonSetupEvent event) {
        
//        File configFile = event.getSuggestedConfigurationFile();
//        Configurations.configExists = configFile.exists();
//        Configurations.config = new Configuration(configFile);
//        Configurations.config.load();
//        Configurations.refreshConfig();

// TODO
//        MinecraftForge.EVENT_BUS.register(PerChunkData.INSTANCE);
        MinecraftForge.EVENT_BUS.register(ChiselController.class);        
        for (Biome b : ForgeRegistries.BIOMES.getValues()) {
            if (BiomeDictionary.hasType(b, BiomeDictionary.Type.OVERWORLD)) {
                // TODO add special basalt generation
                b.addFeature(Decoration.UNDERGROUND_ORES, Feature.ORE
                        .configure(new OreFeatureConfig(OreFeatureConfig.FillerBlockType.NATURAL_STONE, Features.LIMESTONE.get(VariantTemplates.Rock.RAW.getName()).get().getDefaultState(), 33))
                        .createDecoratedFeature(Placement.COUNT_RANGE.configure(new CountRangeConfig(6, 64, 0, 48))));
                b.addFeature(Decoration.UNDERGROUND_ORES, Feature.ORE
                        .configure(new OreFeatureConfig(OreFeatureConfig.FillerBlockType.NATURAL_STONE, Features.MARBLE.get(VariantTemplates.Rock.RAW.getName()).get().getDefaultState(), 33))
                        .createDecoratedFeature(Placement.COUNT_RANGE.configure(new CountRangeConfig(6, 24, 0, 48))));
            }
        }
//        GameRegistry.registerWorldGenerator(GenerationHandler.INSTANCE, 2);
//        MinecraftForge.EVENT_BUS.register(GenerationHandler.INSTANCE);

        //EntityRegistry.registerModEntity(EntityFallingBlockCarvable.class, "falling_block", 60, Chisel.instance, 64, 3, false);
    }

    private void imcEnqueue(InterModEnqueueEvent event) {
        // BlockRegistry.init(event);
        
        // If we add a future vanilla block, it should be added here once the vanilla version exists
        remaps = ImmutableMap.<String, Block>builder()
                .build();

        // TODO 1.14 compat
//        addCompactorPressRecipe(1000, new ItemStack(Blocks.BONE_BLOCK), new ItemStack(ChiselBlocks.limestone2, 1, 7));
//        addCompactorPressRecipe(1000, new ItemStack(ChiselBlocks.limestone2, 1, 7), new ItemStack(ChiselBlocks.marble2, 1, 7));

        /*
//      Example of IMC
                
        FMLInterModComms.sendMessage(MOD_ID, IMC.ADD_VARIATION.toString(), "marble|minecraft:dirt|0");
        CompoundNBT testtag = new CompoundNBT();
        testtag.setString("group", "marble");
        testtag.setTag("stack", new ItemStack(Items.DIAMOND_PICKAXE, 1, 100).serializeNBT());
        FMLInterModComms.sendMessage(MOD_ID, IMC.ADD_VARIATION_V2.toString(), testtag);
        testtag = new CompoundNBT();
        testtag.setString("group", "marble");
        testtag.setString("block", Blocks.WOOL.getRegistryName().toString());
        FMLInterModComms.sendMessage(MOD_ID, IMC.ADD_VARIATION_V2.toString(), testtag);
        testtag = new CompoundNBT();
        testtag.setString("group", "marble");
        testtag.setString("block", Blocks.WOOL.getRegistryName().toString());
        testtag.setInteger("meta", Blocks.WOOL.getMetaFromState(Blocks.WOOL.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.BROWN)));
        FMLInterModComms.sendMessage(MOD_ID, IMC.ADD_VARIATION_V2.toString(), testtag);
        testtag = new CompoundNBT();
        testtag.setString("group", "marble");
        testtag.setTag("stack", new ItemStack(Items.REDSTONE).serializeNBT());
        testtag.setString("block", Blocks.REDSTONE_WIRE.getRegistryName().toString());
        FMLInterModComms.sendMessage(MOD_ID, IMC.ADD_VARIATION_V2.toString(), testtag);
        
        testtag = new CompoundNBT();
        testtag.setString("group", "marble");
        testtag.setTag("stack", new ItemStack(ChiselBlocks.marble, 1, 3).serializeNBT());
        FMLInterModComms.sendMessage(MOD_ID, IMC.REMOVE_VARIATION_V2.toString(), testtag);
        testtag = new CompoundNBT();
        testtag.setString("group", "marble");
        testtag.setString("block", ChiselBlocks.marbleextra.getRegistryName().toString());
        FMLInterModComms.sendMessage(MOD_ID, IMC.REMOVE_VARIATION_V2.toString(), testtag);
        testtag = new CompoundNBT();
        testtag.setString("group", "marble");
        testtag.setString("block", ChiselBlocks.marbleextra.getRegistryName().toString());
        testtag.setInteger("meta", 5);
        FMLInterModComms.sendMessage(MOD_ID, IMC.REMOVE_VARIATION_V2.toString(), testtag);
        */
    }

    private static void addCompactorPressRecipe(int energy, ItemStack input, ItemStack output) {
        CompoundNBT message = new CompoundNBT();

        message.putInt("energy", energy);
        message.put("input", new CompoundNBT());
        message.put("output", new CompoundNBT());

        input.write(message.getCompound("input"));
        output.write(message.getCompound("output"));

        InterModComms.sendTo("thermalexpansion", "addcompactorpressrecipe", () -> message);
    }

    private void imcProcess(InterModProcessEvent event) {
        event.getIMCStream().forEach(IMCHandler.INSTANCE::handleMessage);
        IMCHandler.INSTANCE.imcCounts.object2IntEntrySet().forEach(e ->
            Chisel.logger.info("Received {} IMC messages from mod {}.", e.getKey(), e.getIntValue())
        );
        IMCHandler.INSTANCE.imcCounts.clear();
    }

    /**
     * Sends a debug message, basically a wrapper for the logger that only prints when debugging is enabled
     *
     * @param message
     */
    public static void debug(String message) {
        if (debug) {
            logger.info(message);
        }
    }

    public static void debug(float[] array) {
        if (debug) {
            String message = "[";
            for (float obj : array) {
                message = message + obj + " ";
            }
            debug(message + "]");
        }
    }

    private void onMissingBlock(MissingMappings<Block> event) {
        for (Mapping<Block> mapping : event.getMappings()) {
            Optional.ofNullable(remaps.get(mapping.key.getPath())).ifPresent(mapping::remap);
        }
    }
    
    private void onMissingItem(MissingMappings<Item> event) {
        for (Mapping<Item> mapping : event.getMappings()) {
            Optional.ofNullable(remaps.get(mapping.key.getPath())).map(Block::asItem).ifPresent(mapping::remap);
        }
    }
}
