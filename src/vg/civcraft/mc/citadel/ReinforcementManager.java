package vg.civcraft.mc.citadel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;

import vg.civcraft.mc.citadel.database.CitadelReinforcementData;
import vg.civcraft.mc.citadel.misc.CitadelStatics;
import vg.civcraft.mc.citadel.misc.LoadingCacheNullException;
import vg.civcraft.mc.citadel.reinforcement.NullReinforcement;
import vg.civcraft.mc.citadel.reinforcement.Reinforcement;

public class ReinforcementManager {

	private CitadelReinforcementData db;

	/*
		By reducing the precision of the Location the LoadingCache should have more hits for the same block.
	*/
	private Location reduceLocationPrecisionToInt(Location loc)
	{
		return new Location(
				loc.getWorld(),
				(double)loc.getBlockX(),
				(double)loc.getBlockY(),
				(double)loc.getBlockZ()
		);
	}

	// This shit is cool
	private RemovalListener<Location, Reinforcement> removalListener = new RemovalListener<Location, Reinforcement>() {
		public void onRemoval(
				RemovalNotification<Location, Reinforcement> removal) {
			Reinforcement rein = removal.getValue();
			if (rein instanceof NullReinforcement){
				return;
			}
			if (rein.isDirty())
				saveReinforcement(rein);
		}
	};
	private LoadingCache<Location, Reinforcement> reinforcements = CacheBuilder
			.newBuilder().maximumSize(CitadelConfigManager.getMaxCacheSize())
			.expireAfterAccess(CitadelConfigManager.getMaxCacheMinutes(), TimeUnit.MINUTES)
			.removalListener(removalListener)
			.build(new CacheLoader<Location, Reinforcement>() {
				public Reinforcement load(Location loc) throws Exception {
					Reinforcement rein = db.getReinforcement(loc);
					if (rein == null) {
						return new NullReinforcement(loc);
					}
					CitadelStatics.updateHitStat(CitadelStatics.LOAD);
					// decrement cache because it gets increased from getReinforcement()
					CitadelStatics.decrementHitStat(CitadelStatics.CACHE);
					return rein;
				}
			});
	
	public ReinforcementManager(CitadelReinforcementData db) {
		this.db = db;
		scheduleSave();
	}

	/**
	 * Saves the reinforcement to the database. If the reinforcement durability
	 * is less than or equal to zero it will delete it from the database.
	 * @param The Reinforcement to save
	 */
	public void saveReinforcement(Reinforcement rein) {
		if (rein.getDurability() <= 0)
			deleteReinforcement(rein);
		CitadelStatics.updateHitStat(CitadelStatics.UPDATE);
		db.saveReinforcement(rein);
		rein.setDirty(false);
	}

	/**
	 * Saves the initial reinforcement into the database.
	 * @param The Reinforcement to save
	 */
	public void saveInitialReinforcement(Reinforcement rein) {
		// Do a check first, there might be an edge case for example half slabs where there is a reinforcement
		// but it got this far.  Lets just keep the one already there and ignore this new one.
		// If this is some other case then the code already in place should have deleted the reinforcement EX: Air.
		if (getReinforcement(rein.getLocation()) == null) {
			reinforcements.put(reduceLocationPrecisionToInt(rein.getLocation()), rein);
			CitadelStatics.updateHitStat(CitadelStatics.INSERT);
			db.insertReinforcement(rein);
		}
	}

	/**
	 * Returns the Reinforcement from the specified location.
	 * 
	 * @param loc
	 * @return Reinforcement
	 */
	public Reinforcement getReinforcement(Location loc) {
		try {
			Reinforcement rein = reinforcements.get(reduceLocationPrecisionToInt(loc));
			if (rein instanceof NullReinforcement)
				return null;
			CitadelStatics.updateHitStat(CitadelStatics.CACHE);
			return rein;
		} catch (Exception e) {
			if (!(e.getCause() instanceof LoadingCacheNullException))
				e.printStackTrace();
		}
		return null;
	}

	/**
	 * Returns the Reinforcement from the specified block.
	 * 
	 * @param block
	 * @return Reinforcement
	 */
	public Reinforcement getReinforcement(Block block) {
		return getReinforcement(block.getLocation());
	}

	/**
	 * Deletes the reinforcement. Should get called from the saveReinforcement
	 * method if the durability of the reinforcement is less than or equal to 0.
	 * 
	 * @param rein
	 */
	public void deleteReinforcement(Reinforcement rein) {
		reinforcements.invalidate(reduceLocationPrecisionToInt(rein.getLocation());
		CitadelStatics.updateHitStat(CitadelStatics.DELETE);
		db.deleteReinforcement(rein);
	}

	/**
	 * Used to flush all the reinforcements to the db on shutdown. Can be called
	 * else where if too a manual flush is wanted.
	 */
	public void invalidateAllReinforcements() {
		reinforcements.invalidateAll();
	}

	/**
	 * Returns if the location is reinforced or not.
	 * 
	 * @param loc
	 *            - The location of the potential reinforcement.
	 * @return Returns true if one was found.
	 */
	public boolean isReinforced(Location loc) {
		return getReinforcement(loc) != null;
	}

	/**
	 * Returns if the block is reinforced or not.
	 * 
	 * @param block
	 *            - The block of the potential reinforcement.
	 * @return Returns true if one was found.
	 */
	public boolean isReinforced(Block block) {
		return isReinforced(block.getLocation());
	}
	
	// Saves periodicly all the reinforcements.
	private void scheduleSave(){
		Bukkit.getScheduler().runTaskTimerAsynchronously(Citadel.getInstance(), new Runnable(){

			@Override
			public void run() {
				List<Reinforcement> reins = new ArrayList<Reinforcement>();
				for (Reinforcement r: reinforcements.asMap().values())
					reins.add(r);
				for (Reinforcement r: reins) {
					if (r.isDirty())
						saveReinforcement(r);
				}
			}
			
		}, 100, CitadelConfigManager.getTickRepeatingSave());
	}
	
	/**
	 * This gets all reinforcements in a chunk.  This should not be called regularly synchronously as this will call the database first.
	 * After it grabs the reinforcements it checks if they are already in the cache and if they are it skips it and if not it puts it in there.
	 * Then returns the list of reinforcements in the Chunk.
	 */
	public List<Reinforcement> getReinforcementsByChunk(Chunk chunk){
		List<Reinforcement> reins = db.getReinforcements(chunk);
		List<Reinforcement> reins_new = new ArrayList<Reinforcement>();
		for (Reinforcement rein: reins){
			if (reinforcements.getIfPresent(reduceLocationPrecisionToInt(rein.getLocation())) == null){
				reinforcements.put(reduceLocationPrecisionToInt(rein.getLocation()), rein);
				reins_new.add(rein);
			}
			else {
				Reinforcement r = null;
				try {
					r = reinforcements.get(reduceLocationPrecisionToInt(rein.getLocation()));
				} catch (ExecutionException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				reins_new.add(r);
			}
		}
		return reins_new;
	}
	
	public void loadReinforcementChunk(Chunk chunk) {
		List<Reinforcement> reins = db.getReinforcements(chunk);
		for (Reinforcement rein: reins){
			Reinforcement r = reinforcements.getIfPresent(reduceLocationPrecisionToInt(rein.getLocation()));
			if (r == null || r instanceof NullReinforcement) {
				reinforcements.put(reduceLocationPrecisionToInt(rein.getLocation()), rein);
			}
		}
	}
}
