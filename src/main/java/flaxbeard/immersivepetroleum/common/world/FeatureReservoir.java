package flaxbeard.immersivepetroleum.common.world;

import flaxbeard.immersivepetroleum.api.reservoir.Reservoir;
import flaxbeard.immersivepetroleum.api.reservoir.ReservoirHandler;
import flaxbeard.immersivepetroleum.api.reservoir.ReservoirPolygon;
import flaxbeard.immersivepetroleum.api.reservoir.ReservoirType;
import flaxbeard.immersivepetroleum.common.datastorage.reservoir.ReservoirRegionDataStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FeatureReservoir extends Feature<NoneFeatureConfiguration>{
	public static final Map<ResourceKey<Level>, Set<ChunkPos>> generatedReservoirChunks = new ConcurrentHashMap<>();
	
	public FeatureReservoir(){
		super(NoneFeatureConfiguration.CODEC);
	}
	
	public static void clearGeneratedChunks(){
		generatedReservoirChunks.clear();
	}
	
	@Override
	public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> pContext){
		WorldGenLevel reader = pContext.level();
		BlockPos pos = pContext.origin();
		ReservoirHandler.initGenerator(reader);
		
		if(ReservoirRegionDataStorage.get() == null)
			return false;
		
		ServerLevel level = reader.getLevel();
		ChunkPos chunkPos = new ChunkPos(pos);
		ResourceKey<Level> dimension = level.dimension();
		
		Set<ChunkPos> claimed = generatedReservoirChunks.computeIfAbsent(dimension, k -> ConcurrentHashMap.newKeySet());
		if(!claimed.add(chunkPos))
			return false;
		
		scanChunkForNewReservoirs(reader, chunkPos, pContext.random());
		return true;
	}
	
	public static void scanChunkForNewReservoirs(ServerLevel world, ChunkPos chunkPos, RandomSource randomSource){
		scanChunkForNewReservoirs((WorldGenLevel) world, chunkPos, randomSource);
	}
	
	public static void scanChunkForNewReservoirs(WorldGenLevel reader, ChunkPos chunkPos, RandomSource randomSource){
		int chunkX = chunkPos.getMinBlockX();
		int chunkZ = chunkPos.getMinBlockZ();
		
		ResourceKey<Level> dimension = reader.getLevel().dimension();
		
		final ReservoirRegionDataStorage storage = ReservoirRegionDataStorage.get();
		if(storage == null)
			return;
		
		final ChunkAccess chunk = reader.getChunk(chunkPos.x, chunkPos.z);
		final int quartY = QuartPos.fromBlock(64);
		
		int i, j;
		for(j = 0;j < 16;j++){
			for(i = 0;i < 16;i++){
				int x = chunkX + i;
				int z = chunkZ + j;
				
				// This is visually more pleasing to me
				if(ReservoirHandler.getValueOf(reader, x, z) <= -1)
					continue;
				
				final ColumnPos current = new ColumnPos(x, z);
				if(storage.existsAt(current))
					return;
				
				// Read biome directly from the current ChunkAccess to avoid ServerChunkCache.getChunk() deadlocks
				Holder<Biome> biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), quartY, QuartPos.fromBlock(z));
				
				RecipeHolder<ReservoirType> type = null;
				int totalWeight = ReservoirHandler.getTotalWeight(dimension, biome);
				if(totalWeight > 0){
					int weight = Math.abs(randomSource.nextInt() % totalWeight);
					for(RecipeHolder<ReservoirType> holder: ReservoirType.map.values()){
						ReservoirType res = holder.value();
						
						if(res.getDimensions().isValid(dimension) && res.getBiomes().isValid(biome)){
							weight -= res.weight;
							if(weight < 0){
								type = holder;
								break;
							}
						}
					}
					
					if(type != null){
						ReservoirPolygon reservoirPolygon = ReservoirPolygon.make(reader, new ColumnPos(x, z));
						
						if(!reservoirPolygon.isEmpty()){
							int amount = (int) Mth.lerp(randomSource.nextFloat(), type.value().minSize, type.value().maxSize);
							
							Reservoir reservoir = new Reservoir(reservoirPolygon, type, amount);
							storage.addReservoir(dimension, reservoir);
						}
					}
				}
			}
		}
	}
}
