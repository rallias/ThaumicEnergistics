package thaumicenergistics.container;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotFurnace;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.lang3.tuple.ImmutablePair;
import thaumcraft.api.aspects.Aspect;
import thaumicenergistics.aspect.AspectStack;
import thaumicenergistics.aspect.AspectStackComparator.ComparatorMode;
import thaumicenergistics.container.slot.SlotRestrictive;
import thaumicenergistics.fluids.GaseousEssentia;
import thaumicenergistics.integration.tc.EssentiaConversionHelper;
import thaumicenergistics.integration.tc.EssentiaItemContainerHelper;
import thaumicenergistics.network.packet.client.PacketClientEssentiaCellTerminal;
import thaumicenergistics.network.packet.server.PacketServerEssentiaCellTerminal;
import thaumicenergistics.util.EffectiveSide;
import thaumicenergistics.util.IInventoryUpdateReceiver;
import thaumicenergistics.util.PrivateInventory;
import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.security.PlayerSource;
import appeng.api.networking.storage.IBaseMonitor;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IMEMonitorHandlerReceiver;
import appeng.api.storage.data.IAEFluidStack;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Base class for cell and terminal inventory containers
 * 
 * @author Nividica
 * 
 */
public abstract class AbstractContainerCellTerminalBase
	extends ContainerWithPlayerInventory
	implements IMEMonitorHandlerReceiver<IAEFluidStack>, IAspectSelectorContainer, IInventoryUpdateReceiver
{
	/**
	 * X position for the output slot
	 */
	private static final int OUTPUT_POSITION_X = 26;

	/**
	 * Y position for the output slot
	 */
	private static final int OUTPUT_POSITION_Y = 74;

	/**
	 * X position for the input slot
	 */
	private static final int INPUT_POSITION_X = 8;

	/**
	 * Y position for the input slot
	 */
	private static final int INPUT_POSITION_Y = 74;

	/**
	 * Y position for the player inventory
	 */
	private static final int PLAYER_INV_POSITION_Y = 104;

	/**
	 * Y position for the hotbar inventory
	 */
	private static final int HOTBAR_INV_POSITION_Y = 162;

	/**
	 * The minimum amount of time to wait before playing
	 * sounds again. In ms.
	 */
	private static final int MINIMUM_SOUND_WAIT = 900;

	/**
	 * The number of ticks required to pass before doWork is called.
	 */
	private static final int WORK_TICK_RATE = 3;

	/**
	 * The maximum amount of essentia to try and transfer each time
	 * the transfer method is called.
	 * This is a soft-cap.
	 */
	private static final int ESSENTIA_TRANSFER_PER_WORK_CYCLE = 64;

	/**
	 * Slot ID for the output
	 */
	public static int OUTPUT_SLOT_ID = 1;

	/**
	 * Slot ID for the input
	 */
	public static int INPUT_SLOT_ID = 0;

	/**
	 * AE network monitor
	 */
	protected IMEMonitor<IAEFluidStack> monitor;

	/**
	 * List of aspects on the network
	 */
	protected List<AspectStack> aspectStackList = new ArrayList<AspectStack>();

	/**
	 * The aspect the user has selected.
	 */
	protected Aspect selectedAspect;

	/**
	 * The player that owns this container.
	 */
	protected EntityPlayer player;

	/**
	 * Import and export inventory
	 */
	protected PrivateInventory inventory;

	/**
	 * The last known stack size stored in the export slot
	 */
	private int lastInventorySecondSlotCount = 0;

	/**
	 * The last time, in ms, the splashy sound played
	 */
	private long lastSoundPlaytime = 0;

	/**
	 * Slot number of the input slot
	 */
	private int inputSlotNumber = -1;

	/**
	 * Slot number of the output slot
	 */
	private int outputSlotNumber = -1;

	/**
	 * Holds a list of changes sent to the gui before the
	 * full list is sent.
	 */
	private List<AspectStack> pendingChanges = new ArrayList<AspectStack>();

	/**
	 * Tracks the number of ticks
	 */
	private int tickCounter = 0;

	/**
	 * Set to true once a full list request is sent to the server.
	 */
	protected boolean hasRequested = false;

	/**
	 * Create the container and register the owner
	 * 
	 * @param player
	 */
	public AbstractContainerCellTerminalBase( final EntityPlayer player )
	{
		this.player = player;

		if( EffectiveSide.isClientSide() )
		{
			this.lastSoundPlaytime = System.currentTimeMillis();
		}
		else
		{
			this.hasRequested = true;
		}
	}

	/**
	 * Drains the container in the output slot and places it in the output slot.
	 * 
	 * @param actionSource
	 * @return Amount of essentia transfered.
	 */
	private final int drainContainerAndMoveToOutput( final BaseActionSource actionSource )
	{
		// Get the input slot
		Slot inputSlot = this.getSlot( AbstractContainerCellTerminalBase.INPUT_SLOT_ID );

		// Ensure the input slot has a stack
		if( !inputSlot.getHasStack() )
		{
			// No input stack
			return 0;
		}

		// Create a copy of the container.
		ItemStack container = inputSlot.getStack().copy();
		container.stackSize = 1;

		// Get the fluid stack from the item
		IAEFluidStack containerFluid = EssentiaConversionHelper.INSTANCE.createAEFluidStackFromItemEssentiaContainer( container );

		// Get the proposed drain amount.
		int proposedDrainAmount_FU = (int)containerFluid.getStackSize();

		// Simulate a network injection
		IAEFluidStack notInjected = this.monitor.injectItems( containerFluid, Actionable.SIMULATE, actionSource );

		// Was any rejected?
		if( notInjected != null )
		{
			// Decrease the proposed amount
			proposedDrainAmount_FU -= (int)notInjected.getStackSize();

			// Can the network accept any?
			if( proposedDrainAmount_FU <= 0 )
			{
				// Network is full
				return 0;
			}
		}

		// Convert proposed amount to Essentia units
		int proposedDrainAmount_EU = (int)EssentiaConversionHelper.INSTANCE.convertFluidAmountToEssentiaAmount( proposedDrainAmount_FU );

		// Is there enough power?
		if( !this.extractPowerForEssentiaTransfer( proposedDrainAmount_EU, Actionable.SIMULATE ) )
		{
			// Not enough power.
			return 0;
		}

		// Attempt to drain the container
		ImmutablePair<Integer, ItemStack> drainedContainer = EssentiaItemContainerHelper.INSTANCE.extractFromContainer( container,
			proposedDrainAmount_EU );

		// Was the drain successful?
		if( drainedContainer == null )
		{
			// Unable to drain anything from the container.
			return 0;
		}

		// Merge the drained container with the output slot.
		if( this.mergeContainerWithOutputSlot( drainedContainer.getRight() ) )
		{
			// Adjust the drain amount
			proposedDrainAmount_EU = drainedContainer.left;

			// Adjust the amount to inject into the network to the amount that was drained from the container.
			containerFluid.setStackSize( EssentiaConversionHelper.INSTANCE.convertEssentiaAmountToFluidAmount( proposedDrainAmount_EU ) );

			// Inject into the to network
			this.monitor.injectItems( containerFluid, Actionable.MODULATE, actionSource );

			// Drain power
			this.extractPowerForEssentiaTransfer( proposedDrainAmount_EU, Actionable.MODULATE );

			// Decrease the input slot
			inputSlot.decrStackSize( 1 );

			// Container was drained from, and merged with the output slot.
			return proposedDrainAmount_EU;
		}

		// Unable to merge, nothing changed
		return 0;
	}

	/**
	 * Fills the container in the input slot with the selected aspect's gas.
	 * If filled, the container will be placed in the output slot.
	 * 
	 * @param actionSource
	 * @param container
	 * @return Amount of essentia transfered.
	 */
	private int fillContainerAndMoveToOutput( final BaseActionSource actionSource )
	{
		// Is there an aspect selected?
		if( this.selectedAspect == null )
		{
			// No aspect is selected
			return 0;
		}

		// Get the input slot
		Slot inputSlot = this.getSlot( AbstractContainerCellTerminalBase.INPUT_SLOT_ID );

		// Ensure the input slot has a stack
		if( !inputSlot.getHasStack() )
		{
			// No input stack
			return 0;
		}

		// Create a copy of the container.
		ItemStack container = inputSlot.getStack().copy();
		container.stackSize = 1;

		// Get the available capacity of the container, in Essentia Units
		int containerCapacity_EU = EssentiaItemContainerHelper.INSTANCE.getContainerCapacity( container );

		// Is there any room for more essentia?
		if( containerCapacity_EU == 0 )
		{
			return 0;
		}

		// Get the gas form of the essentia
		GaseousEssentia essentiaGas = GaseousEssentia.getGasFromAspect( this.selectedAspect );

		// Is there a fluid form of the aspect?
		if( essentiaGas == null )
		{
			// No gas form of the selected aspect.
			return 0;
		}

		// Simulate an extraction from the network
		IAEFluidStack result = this.monitor.extractItems(
			EssentiaConversionHelper.INSTANCE.createAEFluidStackInEssentiaUnits( essentiaGas, containerCapacity_EU ), Actionable.SIMULATE,
			actionSource );

		// Is there anything to extract?
		if( result == null )
		{
			// Gas is not present on network.
			return 0;
		}

		// Get how much can be taken from the network, in Essentia Units
		int resultAmount_EU = (int)EssentiaConversionHelper.INSTANCE.convertFluidAmountToEssentiaAmount( result.getStackSize() );

		// Calculate the proposed amount, based on how much we need and how much
		// is available
		int proposedFillAmount_EU = Math.min( containerCapacity_EU, resultAmount_EU );

		// Is there enough power?
		if( !this.extractPowerForEssentiaTransfer( proposedFillAmount_EU, Actionable.SIMULATE ) )
		{
			// Not enough power.
			return 0;
		}

		// Create a new container filled to the proposed amount
		ImmutablePair<Integer, ItemStack> filledContainer = EssentiaItemContainerHelper.INSTANCE.injectIntoContainer( container, new AspectStack(
						this.selectedAspect, proposedFillAmount_EU ) );

		// Was the fill successful?
		if( filledContainer == null )
		{
			// Unable to inject into container.
			return 0;
		}

		// Can the new container be merged with the output slot?
		if( this.mergeContainerWithOutputSlot( filledContainer.getRight() ) )
		{
			// Adjust the fill amount
			proposedFillAmount_EU = filledContainer.left;

			// Drain the essentia from the network
			this.monitor.extractItems( EssentiaConversionHelper.INSTANCE.createAEFluidStackInEssentiaUnits( essentiaGas, proposedFillAmount_EU ),
				Actionable.MODULATE, actionSource );

			// Drain power
			this.extractPowerForEssentiaTransfer( proposedFillAmount_EU, Actionable.MODULATE );

			// Decrease the input slot
			inputSlot.decrStackSize( 1 );

			// Container was injected into, and merged with the output slot.
			return proposedFillAmount_EU;
		}

		// Unable to merge, nothing was changed.
		return 0;
	}

	/**
	 * Determines if the specified aspect stack is a different from an existing
	 * stack in the specified list.
	 * 
	 * @param potentialChange
	 * @return
	 * If a match is found: Pair <ExistingIndex, ChangedStack>
	 * If the item is new: Pair <-1, potentialChange>
	 * 
	 */
	private ImmutablePair<Integer, AspectStack> isChange( final AspectStack potentialChange, final List<AspectStack> comparedAgainst )
	{
		AspectStack matchingStack = null;

		for( int index = 0; index < comparedAgainst.size(); index++ )
		{
			// Tenativly set the matching stack
			matchingStack = comparedAgainst.get( index );

			// Check if it is a match
			if( potentialChange.aspect == matchingStack.aspect )
			{
				// Found a match, determine how much it has changed
				long changeAmount = potentialChange.amount - matchingStack.amount;

				// Create the changed stack
				AspectStack changedStack = new AspectStack( matchingStack.aspect, changeAmount );

				return new ImmutablePair<Integer, AspectStack>( index, changedStack );
			}
		}

		// No match change is new item
		return new ImmutablePair<Integer, AspectStack>( -1, potentialChange );
	}

	/**
	 * Merges a change with the cached aspect list
	 * 
	 * @param changeDetails
	 * @return
	 */
	private boolean mergeAspectChangeWithCache( final ImmutablePair<Integer, AspectStack> changeDetails )
	{
		// Get the index that changed
		int changedIndex = changeDetails.getLeft();

		// Get the stack that changed
		AspectStack changedStack = changeDetails.getRight();

		// Did anything change?
		if( changedStack.amount == 0 )
		{
			// Nothing changed
			return false;
		}

		// Was there a match?
		if( changedIndex != -1 )
		{
			// Get the new amount
			long newAmount = this.aspectStackList.get( changedIndex ).amount + changedStack.amount;

			// Was the stack drained?
			if( newAmount <= 0 )
			{
				// Remove from list
				this.aspectStackList.remove( changedIndex );
			}
			else
			{
				// Update the list
				this.aspectStackList.get( changedIndex ).amount = newAmount;
			}
		}
		// New addition
		else
		{
			this.aspectStackList.add( changedStack );
		}

		// List updated.
		return true;
	}

	/**
	 * Merges the specified stack with the output slot.
	 * 
	 * @param stackToMerge
	 * @return
	 */
	private boolean mergeContainerWithOutputSlot( final ItemStack stackToMerge )
	{
		// Ensure the stack is not null.
		if( stackToMerge == null )
		{
			// Invalid itemstack
			return false;
		}

		// Get the output slot
		Slot outputSlot = this.getSlot( AbstractContainerCellTerminalBase.OUTPUT_SLOT_ID );

		// Ensure the slot is valid
		if( outputSlot == null )
		{
			// Invalid output slot
			return false;
		}

		// Is the slot empty?
		if( !outputSlot.getHasStack() )
		{
			// Set the output
			outputSlot.putStack( stackToMerge );

			// Output was set.
			return true;
		}

		// Can the item be merged?

		// Compare ignoring stack size
		ItemStack o = outputSlot.getStack().copy();
		ItemStack n = stackToMerge.copy();
		o.stackSize = 1;
		n.stackSize = 1;

		if( ItemStack.areItemStacksEqual( o, n ) )
		{
			// Get the amount that was merged
			int amountMerged = this.inventory.incrStackSize( AbstractContainerCellTerminalBase.OUTPUT_SLOT_ID, stackToMerge.stackSize );

			if( amountMerged > 0 )
			{
				// Decrease the merge stack size.
				stackToMerge.stackSize -= amountMerged;

				// Stack was merged
				return true;
			}
		}

		return false;
	}

	@SideOnly(Side.CLIENT)
	private void playTransferAudio()
	{
		// Get the itemstack in the output slot
		ItemStack itemStack = this.inventory.getStackInSlot( AbstractContainerCellTerminalBase.OUTPUT_SLOT_ID );

		// Is there anything in the second slot?
		if( itemStack != null )
		{
			// Has the count changed?
			if( this.lastInventorySecondSlotCount != itemStack.stackSize )
			{
				// Has enough time passed to play the sound again?
				if( ( System.currentTimeMillis() - this.lastSoundPlaytime ) > AbstractContainerCellTerminalBase.MINIMUM_SOUND_WAIT )
				{
					// Play swimy sound
					Minecraft.getMinecraft().getSoundHandler()
									.playSound( PositionedSoundRecord.func_147674_a( new ResourceLocation( "game.neutral.swim" ), 1.0F ) );

					// Set the playtime
					this.lastSoundPlaytime = System.currentTimeMillis();
				}

				// Set the count
				this.lastInventorySecondSlotCount = itemStack.stackSize;
			}
		}
		else
		{
			// Reset the count
			this.lastInventorySecondSlotCount = 0;
		}
	}

	/**
	 * Set's the labels aspect to the currently selected aspect
	 */
	private void setLabelAspect()
	{
		// Is there a selected aspect?
		if( this.selectedAspect == null )
		{
			// Nothing to set
			return;
		}

		// Ensure the output slot is not full
		Slot outputSlot = this.getSlot( AbstractContainerCellTerminalBase.OUTPUT_SLOT_ID );
		if( ( outputSlot.getHasStack() ) && ( outputSlot.getStack().stackSize == 64 ) )
		{
			// Output full
			return;
		}

		// Re-validate the label
		ItemStack label = this.inventory.getStackInSlot( AbstractContainerCellTerminalBase.INPUT_SLOT_ID );
		if( !EssentiaItemContainerHelper.INSTANCE.isLabel( label ) )
		{
			// Not a label
			return;
		}

		// Set the label
		EssentiaItemContainerHelper.INSTANCE.setLabelAspect( label, this.selectedAspect );
		this.inventory.markDirty();

	}

	/**
	 * Attach this container to the AE monitor
	 */
	protected void attachToMonitor()
	{
		if( ( EffectiveSide.isServerSide() ) && ( this.monitor != null ) )
		{
			this.monitor.addListener( this, this.monitor.hashCode() );

			// Update our cached list of aspects
			this.aspectStackList = EssentiaConversionHelper.INSTANCE.convertIIAEFluidStackListToAspectStackList( this.monitor.getStorageList() );
		}
	}

	/**
	 * Binds the container to the specified inventory and the players inventory.
	 * 
	 * @param inventory
	 */
	protected void bindToInventory( final PrivateInventory inventory )
	{
		// Set the inventory
		this.inventory = inventory;

		// Register the container as an update receiver
		this.inventory.setReceiver( this );

		// Create the input slot
		Slot workSlot = new SlotRestrictive( inventory, AbstractContainerCellTerminalBase.INPUT_SLOT_ID,
						AbstractContainerCellTerminalBase.INPUT_POSITION_X, AbstractContainerCellTerminalBase.INPUT_POSITION_Y );

		// Add the input slot
		this.addSlotToContainer( workSlot );

		// Set the input slot number
		this.inputSlotNumber = workSlot.slotNumber;

		// Create the output slot
		workSlot = new SlotFurnace( this.player, inventory, AbstractContainerCellTerminalBase.OUTPUT_SLOT_ID,
						AbstractContainerCellTerminalBase.OUTPUT_POSITION_X, AbstractContainerCellTerminalBase.OUTPUT_POSITION_Y );

		// Add the output slot
		this.addSlotToContainer( workSlot );

		// Set the output slot number
		this.outputSlotNumber = workSlot.slotNumber;

		// Bind to the player's inventory
		this.bindPlayerInventory( this.player.inventory, AbstractContainerCellTerminalBase.PLAYER_INV_POSITION_Y,
			AbstractContainerCellTerminalBase.HOTBAR_INV_POSITION_Y );

	}

	/**
	 * Detaches from the monitor if attached.
	 */
	protected void detachFromMonitor()
	{
		if( EffectiveSide.isServerSide() )
		{
			if( this.monitor != null )
			{
				// Stop listening
				this.monitor.removeListener( this );

				// Null the monitor
				this.monitor = null;

				// Clear the list
				this.aspectStackList.clear();
			}
		}
	}

	/**
	 * Called periodically so that the container can perform work.
	 */
	protected abstract void doWork( int elapsedTicks );

	/**
	 * Attempts to drain power proportional to the amount of essentia
	 * transfered.
	 * 
	 * @param amountOfEssentiaTransfered
	 * @param mode
	 * @return True if the power was drained, false otherwise.
	 */
	protected abstract boolean extractPowerForEssentiaTransfer( int amountOfEssentiaTransfered, Actionable mode );

	/**
	 * Transfers essentia in or out of the system.
	 * 
	 * @param actionSource
	 */
	protected void transferEssentia( final BaseActionSource actionSource )
	{
		// Permissions
		boolean allowedToExtract = false, allowedToInject = false;

		// Action to perform
		boolean doFill = false, doDrain = false;

		// Ensure the monitor and inventory are valid.
		if( ( this.monitor == null ) || ( this.inventory == null ) )
		{
			// Invalid monitor or inventory.
			return;
		}

		// Get the input stack
		ItemStack inputStack = this.inventory.getStackInSlot( AbstractContainerCellTerminalBase.INPUT_SLOT_ID );

		// Ensure the input stack is not empty
		if( ( inputStack == null ) )
		{
			// Nothing in input slot.
			return;
		}

		// Get the output stack
		ItemStack outputStack = this.inventory.getStackInSlot( AbstractContainerCellTerminalBase.OUTPUT_SLOT_ID );

		// Is the output slot valid and not full?
		if( ( outputStack != null ) && ( outputStack.stackSize >= 64 ) )
		{
			// Output slot is full.
			return;
		}

		// Ensure the input slot is an aspect container item.
		if( !EssentiaItemContainerHelper.INSTANCE.isContainer( inputStack ) )
		{
			// Input is not aspect container, is it a label?
			if( EssentiaItemContainerHelper.INSTANCE.isLabel( inputStack ) )
			{
				this.setLabelAspect();
			}
			return;
		}

		// Determine which action to perform
		if( EssentiaItemContainerHelper.INSTANCE.isContainerEmpty( inputStack ) )
		{
			// Input is empty, can it be filled?
			if( ( this.selectedAspect != null ) )
			{
				// Fill the container
				doFill = true;
			}
			else
			{
				// Container is empty, and no aspect is selected.
				return;
			}
		}
		else
		{
			// Drain the container
			doDrain = true;
		}

		// Do we have a source?
		if( actionSource != null )
		{
			// Get the source node
			IGridNode sourceNode = null;
			if( actionSource instanceof MachineSource )
			{
				sourceNode = ( (MachineSource)actionSource ).via.getActionableNode();
			}
			else if( actionSource instanceof PlayerSource )
			{
				sourceNode = ( (PlayerSource)actionSource ).via.getActionableNode();
			}

			// Ensure there is a node
			if( sourceNode == null )
			{
				return;
			}

			// Get the security grid for the node.
			ISecurityGrid sGrid = sourceNode.getGrid().getCache( ISecurityGrid.class );

			// Get permissions
			allowedToExtract = sGrid.hasPermission( this.player, SecurityPermissions.EXTRACT );
			allowedToInject = sGrid.hasPermission( this.player, SecurityPermissions.INJECT );
		}

		// Validate permissions
		if( ( doFill && !allowedToExtract ) || ( doDrain && !allowedToInject ) )
		{
			// Player does not have required permissions to perform the action
			return;
		}

		// Total amount of essentia transfered this cycle.
		int totalTransfered = 0, tmp = 0;

		// Loop while operation is successful and cap has not been hit
		do
		{
			// Filling?
			if( doFill )
			{
				// Attempt to fill the container.
				tmp = this.fillContainerAndMoveToOutput( actionSource );

			}
			// Draining?
			else if( doDrain )
			{
				// Attempt to drain the container.
				tmp = this.drainContainerAndMoveToOutput( actionSource );
			}
		}
		while( ( tmp > 0 ) && ( ( totalTransfered += tmp ) < AbstractContainerCellTerminalBase.ESSENTIA_TRANSFER_PER_WORK_CYCLE ) );
	}

	/**
	 * Who can interact with the container?
	 */
	@Override
	public boolean canInteractWith( final EntityPlayer player )
	{
		return true;
	}

	/**
	 * Checks if there is any work to perform.
	 * If there is it does so.
	 */
	@Override
	public final void detectAndSendChanges()
	{
		// Call super
		super.detectAndSendChanges();

		// Inc tick tracker
		this.tickCounter += 1;

		if( this.tickCounter > AbstractContainerCellTerminalBase.WORK_TICK_RATE )
		{
			// Do work
			this.doWork( this.tickCounter );

			// Reset the tick counter
			this.tickCounter = 0;
		}
	}

	/**
	 * Gets the list of aspect stacks in the container.
	 * 
	 * @return
	 */
	public List<AspectStack> getAspectStackList()
	{
		return this.aspectStackList;
	}

	/**
	 * Get the player that owns this container
	 * 
	 * @return
	 */
	public EntityPlayer getPlayer()
	{
		return this.player;
	}

	/**
	 * Gets the aspect that the player has selected.
	 * 
	 * @return
	 */
	public Aspect getSelectedAspect()
	{
		return this.selectedAspect;
	}

	/**
	 * Is this container still valid for receiving updates
	 * from the AE monitor?
	 */
	@Override
	public boolean isValid( final Object verificationToken )
	{
		if( this.monitor == null )
		{
			return false;
		}

		// Do the hash codes match?
		if( this.monitor.hashCode() == (Integer)verificationToken )
		{
			return true;
		}

		// No longer valid
		this.monitor = null;
		this.aspectStackList.clear();

		return false;
	}

	/**
	 * Merges a change with the cached aspect list
	 * 
	 * @param change
	 * @return
	 */
	public boolean mergeChange( final AspectStack change )
	{
		// Get the index of the change
		int index = this.isChange( change, this.aspectStackList ).getLeft();

		// Create the change
		ImmutablePair<Integer, AspectStack> changeDetails = new ImmutablePair<Integer, AspectStack>( index, change );

		// Attempt the merger
		return this.mergeAspectChangeWithCache( changeDetails );

	}

	/**
	 * Called when a client requests the state of the container.
	 * Updates our cached list of aspects
	 */
	public abstract void onClientRequestFullUpdate();

	/**
	 * Called when a client sends a sorting mode request.
	 * 
	 * @param sortingMode
	 */
	public abstract void onClientRequestSortModeChange( final ComparatorMode sortingMode, final EntityPlayer player );

	/**
	 * Unregister this container from the monitor.
	 */
	@Override
	public void onContainerClosed( final EntityPlayer player )
	{
		super.onContainerClosed( player );

		this.detachFromMonitor();
	}

	/**
	 * Called when the list of fluids on the ME network changes.
	 */
	@Override
	public void onInventoryChanged( final IInventory sourceInventory )
	{
		// Is this client side?
		if( EffectiveSide.isClientSide() )
		{
			this.playTransferAudio();
		}
	}

	@Override
	public final void onListUpdate()
	{
		// Update our cached list of aspects
		this.aspectStackList = EssentiaConversionHelper.INSTANCE.convertIIAEFluidStackListToAspectStackList( this.monitor.getStorageList() );

		// Send a full update
		this.onClientRequestFullUpdate();
	}

	/**
	 * Called by the gui when the aspect list arrives.
	 * 
	 * @param aspectStackList
	 */
	public void onReceivedAspectList( final List<AspectStack> aspectStackList )
	{
		// Set the aspect list
		this.aspectStackList = aspectStackList;

		// Check pending changes
		if( ( this.aspectStackList != null ) && ( !this.pendingChanges.isEmpty() ) )
		{
			// Update list with pending changes
			for( int index = 0; index < this.pendingChanges.size(); index++ )
			{
				this.onReceivedAspectListChange( this.pendingChanges.get( index ) );
			}

			// Clear pending
			this.pendingChanges.clear();
		}
	}

	/**
	 * Called by the gui when a change arrives.
	 * 
	 * @param change
	 * @return
	 */
	public boolean onReceivedAspectListChange( final AspectStack change )
	{
		// Ignored server side
		if( EffectiveSide.isServerSide() )
		{
			return false;
		}

		// Ensure the change is not null
		if( change == null )
		{
			return false;
		}

		// Have we requested the full list yet?
		if( !this.hasRequested )
		{
			return false;
		}

		// Do we have a list?
		if( this.aspectStackList == null )
		{
			// Not yet received full list, add to pending
			this.pendingChanges.add( change );
			return false;
		}

		// Can we merge this change with the list?
		if( this.mergeChange( change ) )
		{
			// Tell the gui to update
			if( this.pendingChanges.isEmpty() )
			{
				return true;
			}
		}

		return false;
	}

	/**
	 * Called when the the selected aspect has changed.
	 * 
	 * @param selectedAspect
	 */
	public void onReceivedSelectedAspect( final Aspect selectedAspect )
	{
		// Set the selected aspect
		this.selectedAspect = selectedAspect;

		// Is this server side?
		if( EffectiveSide.isServerSide() )
		{
			// Send the change back to the client
			new PacketClientEssentiaCellTerminal().createSelectedAspectUpdate( this.player, this.selectedAspect ).sendPacketToPlayer();
		}
	}

	/**
	 * Called by the AE monitor when the network changes.
	 */

	@Override
	public final void postChange( final IBaseMonitor<IAEFluidStack> monitor, final Iterable<IAEFluidStack> changes, final BaseActionSource source )
	{
		// Ensure there was a change
		if( changes == null )
		{
			return;
		}

		// Loop over the changes
		for( IAEFluidStack change : changes )
		{
			// Ensure the fluid is an essentia gas
			if( !( change.getFluid() instanceof GaseousEssentia ) )
			{
				continue;
			}

			// Update the client
			new PacketClientEssentiaCellTerminal().createListChanged( this.player,
				EssentiaConversionHelper.INSTANCE.convertAEFluidStackToAspectStack( change ) ).sendPacketToPlayer();
		}
	}

	/**
	 * Called when the user has clicked on an aspect.
	 * Sends that change to the server for validation.
	 */
	@Override
	public void setSelectedAspect( final Aspect selectedAspect )
	{
		new PacketServerEssentiaCellTerminal().createUpdateSelectedAspect( this.player, selectedAspect ).sendPacketToServer();
	}

	@Override
	public ItemStack transferStackInSlot( final EntityPlayer player, final int slotNumber )
	{
		// Get the slot that was shift-clicked
		Slot slot = (Slot)this.inventorySlots.get( slotNumber );

		// Is there a valid slot with and item?
		if( ( slot != null ) && ( slot.getHasStack() ) )
		{
			boolean didMerge = false;

			// Get the itemstack in the slot
			ItemStack slotStack = slot.getStack();

			// Was the slot clicked the input slot or output slot?
			if( ( slotNumber == this.inputSlotNumber ) || ( slotNumber == this.outputSlotNumber ) )
			{
				// Attempt to merge with the player inventory
				didMerge = this.mergeSlotWithPlayerInventory( slotStack );
			}
			// Was the slot clicked in the player or hotbar inventory?
			else if( this.slotClickedWasInPlayerInventory( slotNumber ) || this.slotClickedWasInHotbarInventory( slotNumber ) )
			{
				// Is the item valid for the input slot?
				if( ( (Slot)this.inventorySlots.get( this.inputSlotNumber ) ).isItemValid( slotStack ) )
				{
					// Attempt to merge with the input slot
					didMerge = this.mergeItemStack( slotStack, this.inputSlotNumber, this.inputSlotNumber + 1, false );
				}

				// Did we merge?
				if( !didMerge )
				{
					didMerge = this.swapSlotInventoryHotbar( slotNumber, slotStack );
				}

			}

			if( didMerge )
			{
				// Did the merger drain the stack?
				if( slotStack.stackSize == 0 )
				{
					// Set the slot to have no item
					slot.putStack( null );
				}
				else
				{
					// Inform the slot its stack changed;
					slot.onSlotChanged();
				}
			}

		}

		return null;
	}
}
