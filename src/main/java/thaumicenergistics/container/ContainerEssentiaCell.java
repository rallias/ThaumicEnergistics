package thaumicenergistics.container;

import java.util.ArrayList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import thaumicenergistics.ThaumicEnergistics;
import thaumicenergistics.aspect.AspectStack;
import thaumicenergistics.aspect.AspectStackComparator.ComparatorMode;
import thaumicenergistics.integration.tc.EssentiaItemContainerHelper;
import thaumicenergistics.inventory.HandlerItemEssentiaCell;
import thaumicenergistics.items.ItemEssentiaCell;
import thaumicenergistics.network.packet.client.PacketClientEssentiaCellTerminal;
import thaumicenergistics.network.packet.server.PacketServerEssentiaCellTerminal;
import thaumicenergistics.parts.AbstractAEPartBase;
import thaumicenergistics.util.EffectiveSide;
import thaumicenergistics.util.PrivateInventory;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.PlayerSource;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.me.GridAccessException;
import appeng.tile.storage.TileChest;

/**
 * Inventory container for essentia cells in a ME chest.
 * 
 * @author Nividica
 * 
 */
public class ContainerEssentiaCell
	extends AbstractContainerCellTerminalBase
{
	/**
	 * The ME chest the cell is stored in.
	 */
	private TileChest hostChest;

	/**
	 * Network source representing the player who is interacting with the
	 * container.
	 */
	private PlayerSource playerSource = null;

	/**
	 * Compiler safe reference to the TileChest when using the
	 * ISaveProvider interface.
	 */
	private ISaveProvider chestSaveProvider;

	/**
	 * Import and export inventory slots.
	 */
	private PrivateInventory privateInventory = new PrivateInventory( ThaumicEnergistics.MOD_ID + ".item.essentia.cell.inventory", 2, 64 )
	{
		@Override
		public boolean isItemValidForSlot( final int slotID, final ItemStack itemStack )
		{
			return EssentiaItemContainerHelper.INSTANCE.isContainerOrLabel( itemStack );
		}
	};

	/**
	 * Creates the container.
	 * 
	 * @param player
	 * The player that owns this container.
	 * @param world
	 * The world the ME chest is in.
	 * @param x
	 * X position of the ME chest.
	 * @param y
	 * Y position of the ME chest.
	 * @param z
	 * Z position of the ME chest.
	 */
	public ContainerEssentiaCell( final EntityPlayer player, final World world, final int x, final int y, final int z )
	{
		// Call the super-constructor
		super( player );

		// Is this server side?
		if( EffectiveSide.isServerSide() )
		{
			// Get the tile entity for the chest
			this.hostChest = (TileChest)world.getTileEntity( x, y, z );

			/*
			 * Note: Casting the hostChest to an object is required to prevent the compiler
			 * from seeing the soft-dependencies of AE2, such a buildcraft, which it attempts
			 * to resolve at compile time.
			 * */
			Object hostObject = this.hostChest;
			this.chestSaveProvider = ( (ISaveProvider)hostObject );

			// Create the action source
			this.playerSource = new PlayerSource( this.player, (IActionHost)hostObject );

			try
			{
				// Get the chest handler
				IMEInventoryHandler<IAEFluidStack> handler = this.hostChest.getHandler( StorageChannel.FLUIDS );

				// Get the monitor
				if( handler != null )
				{
					// Get the cell inventory monitor
					this.monitor = (IMEMonitor<IAEFluidStack>)handler;

					// Attach to the monitor
					this.attachToMonitor();
				}
			}
			catch( Exception e )
			{
				e.printStackTrace();
			}
		}
		else
		{
			// Request a full update from the server
			new PacketServerEssentiaCellTerminal().createFullUpdateRequest( player ).sendPacketToServer();
			this.hasRequested = true;
		}

		// Bind our inventory
		this.bindToInventory( this.privateInventory );

	}

	/**
	 * Gets a handler for the essentia cell.
	 * 
	 * @return
	 */
	private HandlerItemEssentiaCell getCellHandler()
	{
		// Ensure we have a host
		if( this.hostChest == null )
		{
			return null;
		}

		// Get the cell
		ItemStack essentiaCell = this.hostChest.getStackInSlot( 1 );

		// Ensure we have the cell
		if( ( essentiaCell == null ) || !( essentiaCell.getItem() instanceof ItemEssentiaCell ) )
		{
			return null;
		}

		// Get the handler
		return new HandlerItemEssentiaCell( essentiaCell, this.chestSaveProvider );
	}

	@Override
	protected boolean extractPowerForEssentiaTransfer( final int amountOfEssentiaTransfered, final Actionable mode )
	{
		try
		{
			// Get the energy grid
			IEnergyGrid eGrid = this.hostChest.getProxy().getEnergy();

			// Did we get the grid
			if( eGrid != null )
			{
				// Calculate the amount of power to drain
				double powerRequired = AbstractAEPartBase.POWER_DRAIN_PER_ESSENTIA * amountOfEssentiaTransfered;

				// Drain power
				return( eGrid.extractAEPower( powerRequired, mode, PowerMultiplier.CONFIG ) >= powerRequired );
			}
		}
		catch( GridAccessException e )
		{
		}

		// Unable to drain power.
		return false;
	}

	/**
	 * Transfers essentia.
	 */
	@Override
	public void doWork( final int elapsedTicks )
	{
		// Transfer essentia if needed.
		this.transferEssentia( this.playerSource );
	}

	/**
	 * Gets the current list from the AE monitor and sends
	 * it to the client.
	 */
	@Override
	public void onClientRequestFullUpdate()
	{

		// Get the handler
		HandlerItemEssentiaCell cellHandler = this.getCellHandler();

		// Did we get the handler?
		if( cellHandler != null )
		{
			// Send the sorting mode
			new PacketClientEssentiaCellTerminal().createSortModeUpdate( this.player, cellHandler.getSortingMode() ).sendPacketToPlayer();
		}

		// Send the list
		if( ( this.monitor != null ) && ( this.hostChest.isPowered() ) )
		{
			new PacketClientEssentiaCellTerminal().createUpdateFullList( this.player, this.aspectStackList ).sendPacketToPlayer();
		}
		else
		{
			new PacketClientEssentiaCellTerminal().createUpdateFullList( this.player, new ArrayList<AspectStack>() ).sendPacketToPlayer();

		}
	}

	@Override
	public void onClientRequestSortModeChange( final ComparatorMode sortingMode, final EntityPlayer player )
	{
		// Get the handler
		HandlerItemEssentiaCell cellHandler = this.getCellHandler();

		// Inform the handler of the change
		cellHandler.setSortingMode( sortingMode );

		// Send confirmation back to client
		new PacketClientEssentiaCellTerminal().createSortModeUpdate( player, sortingMode ).sendPacketToPlayer();
	}

	/**
	 * Drops any items in the import and export inventory.
	 */
	@Override
	public void onContainerClosed( final EntityPlayer player )
	{
		super.onContainerClosed( player );

		if( EffectiveSide.isServerSide() )
		{
			for( int i = 0; i < 2; i++ )
			{
				this.player.dropPlayerItemWithRandomChoice( ( (Slot)this.inventorySlots.get( i ) ).getStack(), false );
			}
		}
	}
}
